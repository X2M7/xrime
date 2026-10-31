/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import com.osfans.trime.core.RimeApi
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ui.main.EditorProbeActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.File

/** Runs the same real editor panel probe independently of unrelated lifecycle probes. */
internal object TextEditorPanelStandaloneProbe {
    fun run(instrumentation: Instrumentation) {
        val result = Bundle()
        var failure: Throwable? = null
        var activity: Activity? = null
        var session: RimeSession? = null
        var service: TrimeInputMethodService? = null
        var schema: String? = null
        var ascii: Boolean? = null
        val selectedTheme = ThemeManager.prefs.selectedTheme
        val originalTheme = selectedTheme.getValue()
        val hadTheme = selectedTheme.sharedPreferences.contains(selectedTheme.key)
        val originalThemeRaw = selectedTheme.sharedPreferences.getString(selectedTheme.key, null)
        fun <T> main(block: () -> T): T {
            var value: Result<T>? = null
            instrumentation.runOnMainSync { value = runCatching(block) }
            return checkNotNull(value).getOrThrow()
        }
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
        fun input(): InputView? = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
            .filterIsInstance<InputView>().firstOrNull { it.isShown }
        suspend fun api(block: suspend RimeApi.() -> Unit) {
            var value: Result<Unit>? = null
            val job = main { checkNotNull(service).postRimeJob { value = runCatching { block() } } }
            job.join()
            check(!job.isCancelled)
            checkNotNull(value).getOrThrow()
        }
        try {
            check(Build.VERSION.SDK_INT >= 29 && Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "runtime-audit-dedicated").isFile)
            session = main { RimeDaemon.createSession(javaClass.name) }
            runBlocking {
                withTimeout(300_000) { checkNotNull(session).runOnReady { } }
                check(ThemeManager.selectTheme("trime") == "trime")
            }
            val host = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            activity = host
            val editor = main {
                EditText(host).apply {
                    id = View.generateViewId()
                    host.setContentView(this)
                    requestFocus()
                    (host.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
                }
            }
            runBlocking {
                withTimeout(60_000) {
                    while (service == null) {
                        service = main {
                            if (editor.hasWindowFocus()) {
                                (host.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, 0)
                            }
                            input()?.di?.direct?.instance<TrimeInputMethodService>()
                                ?.takeIf { it.currentInputEditorInfo?.fieldId == editor.id }
                        }
                        if (service == null) delay(50)
                    }
                }
                api {
                    schema = selectedSchemaId()
                    ascii = getRuntimeOption("ascii_mode")
                    clearComposition()
                    check(selectSchema("luna_pinyin_t9"))
                    setRuntimeOption("ascii_mode", false)
                }
                withTimeout(30_000) {
                    while (!main { input()?.di?.direct?.instance<KeyboardWindow>()?.currentKeyboard?.isT9Layout == true }) delay(50)
                }
                TextEditorPanelProbe.verify(instrumentation, checkNotNull(service), editor, ::input)
                ClipboardRepeatProbe.verify(instrumentation, checkNotNull(service), editor, ::input)
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            fun cleanup(block: () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (failure == null) failure = error else checkNotNull(failure).addSuppressed(error)
                }
            }
            cleanup {
                if (session != null && schema != null && ascii != null) runBlocking {
                    checkNotNull(session).runOnReady {
                        clearComposition()
                        check(selectSchema(checkNotNull(schema)))
                        setRuntimeOption("ascii_mode", checkNotNull(ascii))
                    }
                }
            }
            cleanup { activity?.let { main { it.finish() } } }
            cleanup {
                runBlocking { ThemeManager.selectTheme(originalTheme) }
                val edit = selectedTheme.sharedPreferences.edit()
                if (hadTheme) edit.putString(selectedTheme.key, checkNotNull(originalThemeRaw)) else edit.remove(selectedTheme.key)
                check(edit.commit())
            }
            cleanup { if (session != null) main { RimeDaemon.destroySession(javaClass.name) } }
        }
        result.putBoolean("passed", failure == null)
        result.putString("stream", failure?.let { "FAIL: ${it.stackTraceToString()}\n" } ?: "PASS: standalone text editor panel\n")
        instrumentation.finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
