// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.core.view.children
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemeYaml
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.KeyboardView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ui.main.EditorProbeActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import org.kodein.di.direct
import org.kodein.di.instance
import splitties.dimensions.dp
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Original custom-patch deployment and real IME geometry on a dedicated emulator. */
internal object CustomThemeGeometryProbe {
    fun run(instrumentation: Instrumentation) {
        val result = Bundle()
        var failure: Throwable? = null
        var activity: Activity? = null
        var session: RimeSession? = null
        var source: File? = null
        var custom: File? = null
        var artifact: File? = null
        var ownsSource = false
        var ownsCustom = false
        var ownsArtifact = false
        var originalTheme: String? = null
        var originalThemePresent = false
        var originalThemeRaw: String? = null
        var originalSchema = ""
        var originalAscii = false
        val keyboardPrefs = AppPrefs.defaultInstance().keyboard
        val oldHeight = keyboardPrefs.keyboardHeight.getValue()
        val oldLandscape = keyboardPrefs.landscapeMode.getValue()
        fun <T> main(block: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            var value: Result<T>? = null
            instrumentation.runOnMainSync { value = runCatching(block) }
            return checkNotNull(value).getOrThrow()
        }
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) view.children.forEach { yieldAll(descendants(it)) }
        }
        fun input(): InputView? = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
            .filterIsInstance<InputView>().firstOrNull { it.isShown }
        fun until(label: String, predicate: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 45_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (main(predicate)) {
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: $label\n") })
                    return
                }
                SystemClock.sleep(50)
            }
            error("Timed out: $label")
        }
        fun phase(label: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: $label\n") })
        try {
            instrumentation.waitForIdleSync()
            check(Build.VERSION.SDK_INT >= 29 && Build.HARDWARE in setOf("ranchu", "goldfish")) { "API 29+ emulator only" }
            check(File(instrumentation.targetContext.getExternalFilesDir(null), "runtime-audit-dedicated").isFile)
            val id = "issue1438-1515-${UUID.randomUUID().toString().replace("-", "")}.trime"
            source = File(DataManager.userDataDir, "$id.yaml")
            custom = File(DataManager.userDataDir, "$id.custom.yaml")
            artifact = File(DataManager.stagingDir, "$id.yaml")
            check(!checkNotNull(artifact).exists() && !Files.isSymbolicLink(checkNotNull(artifact).toPath()))
            check(checkNotNull(source).createNewFile())
            ownsSource = true
            check(checkNotNull(custom).createNewFile())
            ownsCustom = true
            checkNotNull(source).writeText("config_version: '1'\n__include: trime:/\nname: Geometry Patch Fixture\n")
            checkNotNull(custom).writeText(
                """
                patch:
                  'style/vertical_gap': 8
                  'style/horizontal_gap': 4
                  'style/keyboard_height': 250
                  'style/keyboard_height_land': 100
                  'preset_keyboards/default/keyboard_height': 0
                  'preset_keyboards/default/keyboard_height_land': 0
                """.trimIndent(),
            )
            val selected = ThemeManager.prefs.selectedTheme
            originalThemePresent = selected.sharedPreferences.contains(selected.key)
            originalThemeRaw = selected.sharedPreferences.getString(selected.key, null)
            originalTheme = selected.getValue()
            main {
                keyboardPrefs.keyboardHeight.setValue(0)
                keyboardPrefs.landscapeMode.setValue(AppPrefs.Keyboard.LandscapeMode.NEVER)
            }
            session = main { RimeDaemon.createSession(CustomThemeGeometryProbe::class.java.name) }
            runBlocking {
                withTimeout(300_000) {
                    checkNotNull(session).runOnReady { originalSchema = selectedSchemaId(); originalAscii = statusCached.isAsciiMode }
                }
                ThemeManager.init(instrumentation.targetContext.resources.configuration)
                check(ThemeManager.getAllThemes().any { it.configId == id })
                ownsArtifact = true
                check(checkNotNull(session).runOnReady { deployConfigFile(id, "config_version") })
                val deployed = ThemeYaml.parser.decodeFromString<Theme>(checkNotNull(artifact).readText())
                check(deployed.style.verticalGap == 8 && deployed.style.keyboardHeight == 250 && deployed.style.keyboardHeightLand == 100)
                check(deployed.presetKeyboards.getValue("default").verticalGap == 0)
                check(deployed.presetKeyboards.getValue("luna_pinyin_t9").verticalGap == 4)
                check(ThemeManager.selectTheme(id) == id)
                val active = ThemeManager.activeTheme
                check(active.style.verticalGap == 8 && active.style.keyboardHeight == 250 && active.style.keyboardHeightLand == 100)
            }
            phase("native custom.yaml patch applied to deployed and active theme")
            val host = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            activity = host
            val field = main {
                EditText(host).apply {
                    this.id = View.generateViewId()
                    imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    host.setContentView(this)
                    requestFocus()
                }
            }
            val manager = host.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            main { host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            until("portrait editor focus") { field.hasFocus() && field.hasWindowFocus() && host.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            main { manager.showSoftInput(field, 0) }
            until("portrait IME attached") { input() != null && checkNotNull(input()).di.direct.instance<TrimeInputMethodService>().currentInputEditorInfo?.fieldId == field.id }
            fun switch(id: String) = main { checkNotNull(input()).di.direct.instance<KeyboardWindow>().switchKeyboard(id) }
            fun shownKeyboard(t9: Boolean): Boolean {
                val root = input() ?: return false
                return root.di.direct.instance<KeyboardWindow>().currentKeyboard.isT9Layout == t9 &&
                    descendants(root).filterIsInstance<KeyboardView>().count { it.isShown } == 1
            }
            fun verifyGap(expectedDp: Int) = main {
                val root = checkNotNull(input())
                val model = root.di.direct.instance<KeyboardWindow>().currentKeyboard
                val view = descendants(root).filterIsInstance<KeyboardView>().single { it.isShown }
                val expected = view.context.dp(expectedDp)
                check(model.verticalGap == expected)
                val keys = model.keys
                val pair = keys.indices.asSequence().flatMap { a -> keys.indices.asSequence().map { b -> a to b } }.first { (a, b) ->
                    val upper = keys[a]; val lower = keys[b]
                    upper.row == 0 && lower.row == 1 && maxOf(upper.x, lower.x) + view.context.dp(8) < minOf(upper.x + upper.width, lower.x + lower.width)
                }
                val upper = view.getChildAt(pair.first) as KeyView
                val lower = view.getChildAt(pair.second) as KeyView
                val start = upper.bottom - upper.paddingBottom
                val end = lower.top + lower.paddingTop
                check(upper.paddingBottom == expected - expected / 2 && lower.paddingTop == expected / 2 && end - start == expected)
                val x = (maxOf(upper.left + upper.paddingLeft, lower.left + lower.paddingLeft) +
                    minOf(upper.right - upper.paddingRight, lower.right - lower.paddingRight)) / 2
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    check(Color.alpha(bitmap.getPixel(x, (start + end) / 2)) == 0)
                    check(Color.alpha(bitmap.getPixel(x, start - view.context.dp(3))) > 0)
                    check(Color.alpha(bitmap.getPixel(x, end + view.context.dp(3))) > 0)
                } finally { bitmap.recycle() }
            }
            switch("default")
            until("default keyboard in portrait") { shownKeyboard(false) }
            verifyGap(8)
            main {
                val root = checkNotNull(input())
                val keyboard = root.di.direct.instance<KeyboardWindow>().currentKeyboard
                val view = descendants(root).filterIsInstance<KeyboardView>().single { it.isShown }
                check(keyboard.keyboardHeight == view.context.dp(250) && view.height == keyboard.keyboardHeight)
            }
            phase("global 8dp gap and portrait height in actual keyboard drawing")
            switch("luna_pinyin_t9")
            until("T9 keyboard in portrait") { shownKeyboard(true) }
            verifyGap(4)
            phase("T9 keyboard-local 4dp gap overrides the native global patch")
            switch("default")
            until("default keyboard restored") { shownKeyboard(false) }
            // The debug editor declares no configChanges: Android creates a new Activity on rotation.
            // Observe that real lifecycle and install a fresh disposable editor on the new window.
            val monitor = instrumentation.addMonitor(EditorProbeActivity::class.java.name, null, false)
            main { host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            val rotatedHost = try {
                checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 45_000)) {
                    "Landscape did not recreate the real editor Activity"
                }
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            activity = rotatedHost
            val rotatedField = main {
                EditText(rotatedHost).apply {
                    this.id = View.generateViewId()
                    imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    rotatedHost.setContentView(this)
                    requestFocus()
                }
            }
            until("landscape editor focus") { rotatedField.hasFocus() && rotatedField.hasWindowFocus() }
            main { (rotatedHost.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(rotatedField, 0) }
            until("actual display and IME rotated to landscape") {
                val rotation = rotatedHost.window.decorView.display?.rotation
                val root = input()
                (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) &&
                    rotatedHost.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    root != null && root.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    root.di.direct.instance<TrimeInputMethodService>().currentInputEditorInfo?.fieldId == rotatedField.id
            }
            switch("default")
            until("default keyboard after landscape rotation") { shownKeyboard(false) }
            main {
                val root = checkNotNull(input())
                val model = root.di.direct.instance<KeyboardWindow>().currentKeyboard
                val view = descendants(root).filterIsInstance<KeyboardView>().single { it.isShown }
                check(model.keyboardHeight == view.context.dp(100) && view.height == model.keyboardHeight) {
                    "Landscape height mismatch: model=${model.keyboardHeight}, view=${view.height}, expected=${view.context.dp(100)}"
                }
                check(!root.di.direct.instance<TrimeInputMethodService>().isFullscreenMode)
            }
            phase("physical landscape uses native custom 100dp height with landscape layout disabled")
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                main { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; activity?.finish() }
                runBlocking {
                    originalTheme?.let { ThemeManager.selectTheme(it) }
                    if (session != null && originalSchema.isNotEmpty()) checkNotNull(session).runOnReady {
                        clearComposition(); selectSchema(originalSchema); setRuntimeOption("ascii_mode", originalAscii)
                    }
                }
                main {
                    keyboardPrefs.keyboardHeight.setValue(oldHeight)
                    keyboardPrefs.landscapeMode.setValue(oldLandscape)
                }
                originalTheme?.let {
                    val selected = ThemeManager.prefs.selectedTheme
                    val edit = selected.sharedPreferences.edit()
                    if (originalThemePresent) edit.putString(selected.key, checkNotNull(originalThemeRaw)) else edit.remove(selected.key)
                    check(edit.commit())
                }
                if (session != null) main { RimeDaemon.destroySession(CustomThemeGeometryProbe::class.java.name) }
                listOf(source to ownsSource, custom to ownsCustom, artifact to ownsArtifact).forEach { (file, owned) ->
                    if (owned && file != null && file.exists()) check(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.delete())
                }
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        val error = failure
        result.putBoolean("passed", error == null)
        result.putString("stream", error?.stackTraceToString() ?: "PASS: native custom patch, real gap drawing and physical rotation")
        instrumentation.finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
