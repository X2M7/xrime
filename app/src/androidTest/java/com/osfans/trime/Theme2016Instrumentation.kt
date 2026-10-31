/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.core.view.children
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeYaml
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.KeyboardView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ui.main.EditorProbeActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Actual theme discovery, native deployment and editor input on a dedicated emulator. */
class Theme2016Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        var failure: Throwable? = null
        var editor: Activity? = null
        var session: RimeSession? = null
        var originalTheme: String? = null
        var originalSchema = ""
        var originalAscii = false
        var source: File? = null
        var ordinary: File? = null
        var artifact: File? = null
        var ownsSource = false
        var ownsOrdinary = false
        var ownsArtifact = false
        var originalThemePresent = false
        var originalThemeRaw: String? = null
        fun <T> main(block: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            var outcome: Result<T>? = null
            runOnMainSync { outcome = runCatching(block) }
            return checkNotNull(outcome).getOrThrow()
        }
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) view.children.forEach { yieldAll(descendants(it)) }
        }
        fun input(): InputView? = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
            .filterIsInstance<InputView>().firstOrNull { it.isShown }
        fun waitUntil(label: String, predicate: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < until) {
                if (main(predicate)) {
                    sendStatus(0, Bundle().apply { putString("stream", "PASS: $label\n") })
                    return
                }
                SystemClock.sleep(50)
            }
            error("Timed out waiting for $label")
        }
        try {
            waitForIdleSync()
            check(Build.VERSION.SDK_INT == 34 && Build.HARDWARE in setOf("ranchu", "goldfish")) { "Requires dedicated Android 14 emulator" }
            check(File(targetContext.getExternalFilesDir(null), "runtime-audit-dedicated").isFile) { "Requires dedicated runtime-audit marker" }
            val id = "issue2016-${UUID.randomUUID().toString().replace("-", "")}.trime"
            val user = DataManager.userDataDir
            source = File(user, "$id.yaml")
            ordinary = File(user, "${id.removeSuffix(".trime")}.yaml")
            artifact = File(DataManager.stagingDir, "$id.yaml")
            check(!checkNotNull(artifact).exists() && !Files.isSymbolicLink(checkNotNull(artifact).toPath())) { "Fixture artifact path collision" }
            check(checkNotNull(source).createNewFile()) { "Fixture source path collision" }
            ownsSource = true
            check(checkNotNull(ordinary).createNewFile()) { "Fixture ordinary path collision" }
            ownsOrdinary = true
            checkNotNull(ordinary).writeText("preset_keyboards:\n  default: {name: Test Keyboard, keys: [{click: a, label: A}]}\n")
            fun writeTheme(firstLabel: String, version: String) = checkNotNull(source).writeText(
                """
                config_version: '$version'
                __include: trime:/
                name: Issue 2016 Test Theme
                preset_keyboards:
                  default:
                    name: Test Keyboard
                    keys:
                      - {click: a, label: $firstLabel}
                      - {click: b, label: B}
                      - {click: space, label: Space}
                      - {click: Return, label: Enter}
                """.trimIndent(),
            )
            fun deployedLabel(): String = ThemeYaml.parser.decodeFromString<Theme>(checkNotNull(artifact).readText())
                .presetKeyboards.getValue("default").keys.first().label
            writeTheme("A", "1")
            val selected = ThemeManager.prefs.selectedTheme
            originalThemePresent = selected.sharedPreferences.contains(selected.key)
            originalThemeRaw = selected.sharedPreferences.getString(selected.key, null)
            originalTheme = ThemeManager.prefs.selectedTheme.getValue()
            session = main { RimeDaemon.createSession(javaClass.name) }
            runBlocking {
                withTimeout(300_000) { checkNotNull(session).runOnReady { originalSchema = selectedSchemaId(); originalAscii = statusCached.isAsciiMode } }
                ThemeManager.init(targetContext.resources.configuration)
                val choices = ThemeManager.getAllThemes()
                check(choices.any { it.configId == id && it.name == "Issue 2016 Test Theme" })
                check(choices.none { it.configId == id.removeSuffix(".trime") })
                ownsArtifact = true
                check(checkNotNull(session).runOnReady { deployConfigFile(id, "config_version") })
                check(checkNotNull(artifact).isFile) { "Native deployed theme is missing" }
                check(deployedLabel() == "A") { "Native deployed theme lost the initial keyboard patch" }
                check(ThemeManager.selectTheme(id) == id)
                check(ThemeManager.activeTheme.presetColorSchemes.isNotEmpty())
                check(ThemeManager.activeTheme.presetKeyboards.getValue("default").keys.map { it.label } == listOf("A", "B", "Space", "Enter"))
            }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: theme discovery, native config deployment and source selection\n") })
            val activity = startActivitySync(Intent(targetContext, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            editor = activity
            val field = main { EditText(activity).apply { this.id = View.generateViewId(); activity.setContentView(this); requestFocus() } }
            val manager = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            main { manager.showSoftInput(field, 0) }
            waitUntil("custom-theme editor owns visible IME") { input() != null && checkNotNull(input()).di.direct.instance<TrimeInputMethodService>().currentInputEditorInfo?.fieldId == field.id }
            val service = main { checkNotNull(input()).di.direct.instance<TrimeInputMethodService>() }
            suspend fun <T> api(block: suspend RimeApi.() -> T): T {
                var outcome: Result<T>? = null
                val job = main { service.postRimeJob { outcome = runCatching { block() } } }
                withTimeout(300_000) { job.join() }
                check(!job.isCancelled)
                return checkNotNull(outcome).getOrThrow()
            }
            fun keyboardWindow() = checkNotNull(input()).di.direct.instance<KeyboardWindow>()
            val keyboardId = KeyboardWindow::class.java.getDeclaredField("currentKeyboardId").apply { isAccessible = true }
            fun selectDefault() = main { keyboardWindow().switchKeyboard("default") }
            fun shownFirstLabel(expected: String): Boolean {
                val window = input()?.di?.direct?.instance<KeyboardWindow>() ?: return false
                return keyboardId.get(window) == "default" && window.currentKeyboard.keys.firstOrNull()?.getLabel() == expected
            }
            selectDefault()
            waitUntil("custom default keyboard visible") { shownFirstLabel("A") }
            runBlocking { api { clearComposition(); check(selectSchema("luna_pinyin")); setRuntimeOption("ascii_mode", false) } }
            selectDefault()
            waitUntil("custom default keyboard restored after schema selection") { shownFirstLabel("A") }
            main {
                val root = checkNotNull(input())
                val window = keyboardWindow()
                val view = descendants(root).filterIsInstance<KeyboardView>().single { it.isShown }
                val key = view.getChildAt(0) as KeyView
                check(key.isShown && window.currentKeyboard.keys.first().getLabel() == "A")
                key.onPress?.invoke() ?: error("Missing key press callback")
                key.onRelease?.invoke(KeyBehavior.CLICK, false) ?: error("Missing key release callback")
            }
            runBlocking { api { check(getRawInput() == "a"); clearComposition() } }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: real custom A key produces native a\n") })
            SystemClock.sleep(1_100)
            writeTheme("A2", "2")
            runBlocking {
                api { check(deployConfigFile(id, "config_version")) }
                check(deployedLabel() == "A2") { "Native deployed theme lost the updated keyboard patch" }
                api { deploy(skipImport = true) }
            }
            waitUntil("deployment reloads edited theme") {
                ThemeManager.activeTheme.presetKeyboards["default"]?.keys?.firstOrNull()?.label == "A2"
            }
            selectDefault()
            waitUntil("redeployed custom keyboard is visible") { shownFirstLabel("A2") }
            result.putString("stream", "PASS: native deployment and live custom keyboard reload\n")
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                main { editor?.finish() }
                runBlocking {
                    originalTheme?.let { ThemeManager.selectTheme(it) }
                    if (session != null && originalSchema.isNotEmpty()) checkNotNull(session).runOnReady {
                        clearComposition(); selectSchema(originalSchema); setRuntimeOption("ascii_mode", originalAscii)
                    }
                }
                originalTheme?.let {
                    val selected = ThemeManager.prefs.selectedTheme
                    val edit = selected.sharedPreferences.edit()
                    if (originalThemePresent) edit.putString(selected.key, checkNotNull(originalThemeRaw)) else edit.remove(selected.key)
                    check(edit.commit())
                }
                if (session != null) main { RimeDaemon.destroySession(javaClass.name) }
                listOf(source to ownsSource, ordinary to ownsOrdinary, artifact to ownsArtifact).forEach { (file, owned) ->
                    if (owned && file != null && file.exists()) {
                        check(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.delete()) { "Could not remove owned fixture: ${file.name}" }
                    }
                }
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        val error = failure
        result.putBoolean("passed", error == null)
        if (error != null) result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
        finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
