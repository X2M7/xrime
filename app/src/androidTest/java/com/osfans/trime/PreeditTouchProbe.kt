/* SPDX-License-Identifier: GPL-3.0-or-later */
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
import com.osfans.trime.core.Rime
import com.osfans.trime.core.RimeApi
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.composition.PreeditTextView
import com.osfans.trime.ime.core.InlinePreeditMode
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ui.main.EditorProbeActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.File

/** Actual preedit touch positions and returned Rime cursor on a dedicated emulator. */
object PreeditTouchProbe {
    fun run(instrumentation: Instrumentation) {
        instrumentation.waitForIdleSync()
        val result = Bundle()
        var failure: Throwable? = null
        var host: Activity? = null
        var session: RimeSession? = null
        var originalSchema: String? = null
        var originalAscii: Boolean? = null
        var service: TrimeInputMethodService? = null
        val pref = AppPrefs.defaultInstance().general.inlinePreeditMode
        val originalMode = pref.getValue()
        val hadModePreference = pref.sharedPreferences.contains(pref.key)
        val originalModeRaw = pref.sharedPreferences.getString(pref.key, null)
        val selectedTheme = ThemeManager.prefs.selectedTheme
        val originalTheme = selectedTheme.getValue()
        val hadThemePreference = selectedTheme.sharedPreferences.contains(selectedTheme.key)
        val originalThemeRaw = selectedTheme.sharedPreferences.getString(selectedTheme.key, null)
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
        fun visibleViews() = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
        suspend fun api(block: suspend RimeApi.() -> Unit) {
            var value: Result<Unit>? = null
            val job = main { checkNotNull(service).postRimeJob { value = runCatching { block() } } }
            job.join()
            check(!job.isCancelled)
            checkNotNull(value).getOrThrow()
        }
        try {
            instrumentation.waitForIdleSync()
            check(Build.VERSION.SDK_INT >= 29 && Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "runtime-audit-dedicated").isFile)
            main { pref.setValue(InlinePreeditMode.COMMIT_TEXT_PREVIEW) }
            session = main { RimeDaemon.createSession(javaClass.name) }
            runBlocking {
                withTimeout(300_000) { checkNotNull(session).runOnReady { } }
                check(ThemeManager.selectTheme("trime") == "trime")
            }
            val activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            host = activity
            val field = main {
                EditText(activity).apply {
                    id = View.generateViewId()
                    activity.setContentView(this)
                    requestFocus()
                    (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
                }
            }
            runBlocking {
                withTimeout(60_000) {
                    while (service == null) {
                        service = main {
                            if (field.hasWindowFocus()) {
                                (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0)
                            }
                            visibleViews().filterIsInstance<InputView>().firstOrNull { it.isShown }?.di?.direct
                                ?.instance<TrimeInputMethodService>()?.takeIf { it.currentInputEditorInfo?.fieldId == field.id }
                        }
                        if (service == null) delay(50)
                    }
                }
                api {
                    originalSchema = selectedSchemaId()
                    originalAscii = getRuntimeOption("ascii_mode")
                    clearComposition()
                    check(selectSchema("luna_pinyin_simp"))
                    setRuntimeOption("ascii_mode", false)
                }
                for (target in listOf(1, 6, 7, 8)) {
                    api { clearComposition(); "zhongguo".forEach { check(processKey(it.code)) } }
                    var expectedPreedit = ""
                    api { expectedPreedit = compositionCached.preedit.orEmpty() }
                    check(expectedPreedit.length > target && expectedPreedit.filter { it in 'a'..'z' } == "zhongguo") {
                        "Unexpected engine preedit: $expectedPreedit"
                    }
                    val preedit = withTimeout(20_000) {
                        var found: PreeditTextView? = null
                        while (found == null) {
                            found = main { visibleViews().filterIsInstance<PreeditTextView>().firstOrNull { it.isShown && it.text.toString() == expectedPreedit } }
                            if (found == null) delay(50)
                        }
                        checkNotNull(found)
                    }
                    val value = main { preedit.text.toString() }
                    val actual = main {
                        val layout = checkNotNull(preedit.layout)
                        val x = layout.getPrimaryHorizontal(target) + preedit.totalPaddingLeft - preedit.scrollX
                        val y = layout.getLineBaseline(0) + preedit.totalPaddingTop - preedit.scrollY - 1f
                        val offset = preedit.getOffsetForPosition(x, y)
                        val started = SystemClock.uptimeMillis()
                        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x, y, 0)
                            try { check(preedit.dispatchTouchEvent(event)) } finally { event.recycle() }
                        }
                        offset
                    }
                    var cursor = -1
                    api { cursor = Rime.getRimeCaretPos() }
                    // This fixture is ASCII raw input. Spaces and the theme's caret glyph
                    // are display decorations and must not advance its native caret.
                    val expected = value.take(actual).count { it in 'a'..'z' }
                    check(cursor == expected) { "Preedit click offset=$actual text=$value expectedRaw=$expected actualRaw=$cursor" }
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: displayed offset $actual -> raw cursor $cursor\n") })
                }
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
                if (service != null && originalSchema != null && originalAscii != null) runBlocking {
                    checkNotNull(session).runOnReady { clearComposition(); check(selectSchema(checkNotNull(originalSchema))); setRuntimeOption("ascii_mode", checkNotNull(originalAscii)) }
                }
            }
            cleanup { host?.let { main { it.finish() } } }
            cleanup {
                runBlocking { ThemeManager.selectTheme(originalTheme) }
                val edit = selectedTheme.sharedPreferences.edit()
                if (hadThemePreference) edit.putString(selectedTheme.key, checkNotNull(originalThemeRaw)) else edit.remove(selectedTheme.key)
                check(edit.commit())
            }
            cleanup {
                main { pref.setValue(originalMode) }
                val modeEdit = pref.sharedPreferences.edit()
                if (hadModePreference) modeEdit.putString(pref.key, checkNotNull(originalModeRaw)) else modeEdit.remove(pref.key)
                check(modeEdit.commit())
            }
            cleanup { if (session != null) main { RimeDaemon.destroySession(javaClass.name) } }
        }
        result.putBoolean("passed", failure == null)
        result.putString("stream", failure?.let { "FAIL: ${it.stackTraceToString()}\n" } ?: "PASS: preedit actual touch cursor positions\n")
        instrumentation.finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
