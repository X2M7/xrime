// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
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
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemeYaml
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ui.main.EditorProbeActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** The original #1635 YAML, native deployment and real touch-to-editor output. */
internal object Ascii1635Probe {
    fun run(instrumentation: Instrumentation) {
        val result = Bundle()
        var activity: Activity? = null
        var session: RimeSession? = null
        var source: File? = null
        var artifact: File? = null
        var ownsSource = false
        var ownsArtifact = false
        var originalTheme: String? = null
        var originalThemePresent = false
        var originalThemeRaw: String? = null
        var originalSchema = ""
        var originalAscii = false
        var failure: Throwable? = null
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
        fun until(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (main(condition)) {
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: $label\n") })
                    return
                }
                SystemClock.sleep(50)
            }
            error("Timed out waiting for $label")
        }
        try {
            instrumentation.waitForIdleSync()
            check(Build.VERSION.SDK_INT >= 29 && Build.HARDWARE in setOf("ranchu", "goldfish")) { "API 29+ emulator only" }
            check(File(instrumentation.targetContext.getExternalFilesDir(null), "runtime-audit-dedicated").isFile)
            val id = "issue1635-${UUID.randomUUID().toString().replace("-", "")}.trime"
            source = File(DataManager.userDataDir, "$id.yaml")
            artifact = File(DataManager.stagingDir, "$id.yaml")
            check(!checkNotNull(artifact).exists() && !Files.isSymbolicLink(checkNotNull(artifact).toPath()))
            check(checkNotNull(source).createNewFile())
            ownsSource = true
            checkNotNull(source).writeText(
                """
                config_version: '1'
                __include: trime:/
                name: Issue 1635 Test Theme
                preset_keyboards:
                  default:
                    name: ASCII issue fixture
                    width: 25
                    height: 52
                    ascii_mode: 1
                    reset_ascii_mode: false
                    lock: true
                    keys:
                      - {click: "ㅎ", ascii: g}
                      - {click: "ㅎ", ascii: g, send_bindings: false}
                      - {click: space}
                      - {click: Return}
                """.trimIndent(),
            )
            val selected = ThemeManager.prefs.selectedTheme
            originalThemePresent = selected.sharedPreferences.contains(selected.key)
            originalThemeRaw = selected.sharedPreferences.getString(selected.key, null)
            originalTheme = selected.getValue()
            session = main { RimeDaemon.createSession(Ascii1635Probe::class.java.name) }
            runBlocking {
                withTimeout(300_000) {
                    checkNotNull(session).runOnReady { originalSchema = selectedSchemaId(); originalAscii = statusCached.isAsciiMode }
                }
                ThemeManager.init(instrumentation.targetContext.resources.configuration)
                check(ThemeManager.getAllThemes().any { it.configId == id })
                ownsArtifact = true
                check(checkNotNull(session).runOnReady { deployConfigFile(id, "config_version") })
                val deployed = ThemeYaml.parser.decodeFromString<Theme>(checkNotNull(artifact).readText())
                val keys = deployed.presetKeyboards.getValue("default").keys
                check(keys.size >= 2 && keys[0].click == KeyActionToken.Plain("ㅎ") && keys[0].ascii == KeyActionToken.Plain("g"))
                check(keys[1].click == KeyActionToken.Plain("ㅎ") && keys[1].ascii == KeyActionToken.Plain("g") && !keys[1].sendBindings)
                check(ThemeManager.selectTheme(id) == id)
            }
            val host = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            activity = host
            val field = main { EditText(host).apply { this.id = View.generateViewId(); host.setContentView(this); requestFocus() } }
            val manager = host.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            until("editor focused") { field.hasFocus() && field.hasWindowFocus() }
            main { manager.showSoftInput(field, 0) }
            until("custom theme IME attached") { input() != null && checkNotNull(input()).di.direct.instance<TrimeInputMethodService>().currentInputEditorInfo?.fieldId == field.id }
            val service = main { checkNotNull(input()).di.direct.instance<TrimeInputMethodService>() }
            suspend fun <T> api(block: suspend RimeApi.() -> T): T {
                var outcome: Result<T>? = null
                val job = main { service.postRimeJob { outcome = runCatching { block() } } }
                withTimeout(300_000) { job.join() }
                check(!job.isCancelled)
                return checkNotNull(outcome).getOrThrow()
            }
            runBlocking { api { clearComposition(); setRuntimeOption("ascii_mode", true); check(getRuntimeOption("ascii_mode")) } }
            main { checkNotNull(input()).di.direct.instance<KeyboardWindow>().switchKeyboard("default") }
            until("original custom keyboard and English mode") {
                val root = input() ?: return@until false
                val keyboard = root.di.direct.instance<KeyboardWindow>().currentKeyboard
                keyboard.keys.size >= 2 && keyboard.keys[0].getLabel() == "g" && keyboard.keys[1].getLabel() == "g"
            }
            for (index in 0..1) {
                runBlocking { api { check(getRuntimeOption("ascii_mode")); clearComposition() } }
                main { field.setText(""); field.setSelection(0) }
                val point = main {
                    val root = checkNotNull(input())
                    val keyboard = root.di.direct.instance<KeyboardWindow>().currentKeyboard
                    check(keyboard.keys.size >= 2 && keyboard.keys[index].getLabel() == "g")
                    val view = descendants(root).filterIsInstance<KeyboardView>().single { it.isShown }
                    val key = view.getChildAt(index) as KeyView
                    check(key.isShown && key.width > 0 && key.height > 0)
                    val location = IntArray(2).also(key::getLocationOnScreen)
                    (location[0] + key.width / 2f) to (location[1] + key.height / 2f)
                }
                val down = SystemClock.uptimeMillis()
                val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, point.first, point.second, 0)
                val release = MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, point.first, point.second, 0)
                try {
                    instrumentation.sendPointerSync(press)
                    SystemClock.sleep(60)
                    instrumentation.sendPointerSync(release)
                } finally { press.recycle(); release.recycle() }
                until("actual key $index commits exactly g") { field.text.toString() == "g" }
                runBlocking { api { check(getRuntimeOption("ascii_mode")) } }
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                main { activity?.finish() }
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
                if (session != null) main { RimeDaemon.destroySession(Ascii1635Probe::class.java.name) }
                listOf(source to ownsSource, artifact to ownsArtifact).forEach { (file, owned) ->
                    if (owned && file != null && file.exists()) {
                        check(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.delete())
                    }
                }
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        val error = failure
        result.putBoolean("passed", error == null)
        result.putString("stream", error?.stackTraceToString() ?: "PASS: original ASCII keys commit g through real touches")
        instrumentation.finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
