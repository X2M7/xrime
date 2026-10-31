/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.core.view.children
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.KeyboardView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ui.main.EditorProbeActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.File
import java.util.UUID

/** Actual editor and keyboard ordering probe with a disposable theme on a dedicated emulator. */
class MacroOrderInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        var failure: Throwable? = null
        var activity: Activity? = null
        var source: File? = null
        var artifact: File? = null
        var session: RimeSession? = null
        var originalSchema: String? = null
        var originalAscii: Boolean? = null
        var ownsSource = false
        var ownsArtifact = false
        var restoreTheme: (() -> Unit)? = null
        fun <T> main(block: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            var value: Result<T>? = null
            runOnMainSync { value = runCatching(block) }
            return checkNotNull(value).getOrThrow()
        }
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) view.children.forEach { yieldAll(descendants(it)) }
        }
        fun input(): InputView? = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
            .filterIsInstance<InputView>().firstOrNull { it.isShown }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (main(condition)) return
                SystemClock.sleep(50)
            }
            error("Timed out waiting for $label")
        }
        try {
            waitForIdleSync()
            check(Build.VERSION.SDK_INT >= 29 && Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(File(checkNotNull(targetContext.getExternalFilesDir(null)), "runtime-audit-dedicated").isFile)
            val selected = ThemeManager.prefs.selectedTheme
            val original = selected.getValue()
            val hadPreference = selected.sharedPreferences.contains(selected.key)
            val originalRaw = selected.sharedPreferences.getString(selected.key, null)
            restoreTheme = {
                runBlocking { ThemeManager.selectTheme(original) }
                val edit = selected.sharedPreferences.edit()
                if (hadPreference) edit.putString(selected.key, checkNotNull(originalRaw)) else edit.remove(selected.key)
                check(edit.commit())
            }
            val themeId = "macro-order-${UUID.randomUUID().toString().replace("-", "")}.trime"
            source = File(DataManager.userDataDir, "$themeId.yaml")
            artifact = File(DataManager.stagingDir, "$themeId.yaml")
            check(!checkNotNull(artifact).exists() && checkNotNull(source).createNewFile())
            ownsSource = true
            ownsArtifact = true
            val base = targetContext.assets.open("shared/trime.yaml").bufferedReader().use { it.readText() }
                .replace("\r\n", "\n")
            val actions = """
  auditText: {text: 'x'}
  auditCommit: {commit: 'Y'}
  auditNested: {text: '{auditText}'}
  auditTextCommit: {label: 'A', text: '{auditText}{auditCommit}'}
  auditCommitText: {label: 'B', text: '{auditCommit}{auditText}'}
  auditNestedNav: {label: 'C', text: '{auditNested}{End}{auditCommit}'}
  auditFenceText: {text: "```\n\n```"}
  auditFence: {label: 'D', text: '{auditFenceText}{End}{Up}{Up}'}
  auditOnlyNested: {label: 'E', text: '{auditNested}'}
  auditOnlyEndCommit: {label: 'F', text: '{End}{auditCommit}'}
  auditOnlyUp: {label: 'G', text: '{Up}{Up}'}
  auditChineseText: {text: '好'}
  auditChinese: {label: 'H', text: '{auditChineseText}'}
""".trimIndent().prependIndent("  ").trimStart()
            val keyboard = """
  audit_macro:
    ascii_mode: 1
    width: 25
    height: 48
    keys:
      - {click: auditTextCommit}
      - {click: auditCommitText}
      - {click: auditNestedNav}
      - {click: auditFence}
      - {click: auditOnlyNested}
      - {click: auditOnlyEndCommit}
      - {click: auditOnlyUp}
      - {click: auditChinese}
  audit_macro_native:
    ascii_mode: 0
    width: 25
    height: 48
    keys:
      - {click: auditChinese}
""".trimIndent().prependIndent("  ").trimStart()
            check(base.contains("preset_keys:\n") && base.contains("preset_keyboards:\n"))
            checkNotNull(source).writeText(base.replace("preset_keys:\n", "preset_keys:\n  $actions\n")
                .replace("preset_keyboards:\n", "preset_keyboards:\n  $keyboard\n"))
            session = main { RimeDaemon.createSession(javaClass.name) }
            runBlocking {
                withTimeout(120_000) { checkNotNull(session).runOnReady { } }
                ThemeManager.init(targetContext.resources.configuration)
                check(ThemeManager.selectTheme(themeId) == themeId)
            }
            val host = startActivitySync(Intent(targetContext, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = host
            val imm = host.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            fun window() = checkNotNull(input()).di.direct.instance<KeyboardWindow>()
            fun click(label: String) = main {
                val index = window().currentKeyboard.keys.indexOfFirst { it.getLabel() == label }
                check(index >= 0)
                val view = descendants(checkNotNull(input())).filterIsInstance<KeyboardView>().single { it.isShown }
                val key = view.getChildAt(index) as KeyView
                val start = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, key.width / 2f, key.height / 2f, 0)
                    try { check(key.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
            }
            val failures = mutableListOf<String>()
            fun verify(label: String, initial: String, expected: String, cursor: Int, start: Int = 0, shifted: Boolean = false) {
                // Populate a new editor before attaching it. Updating an already served
                // TextView requests restartInput asynchronously, invalidating the macro
                // just as it is pressed and replacing the test keyboard.
                val field = main {
                    EditText(host).apply {
                        id = View.generateViewId()
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        setSingleLine(false)
                        setText(initial)
                        setSelection(start)
                        host.setContentView(this)
                        requestFocus()
                        imm.showSoftInput(this, 0)
                    }
                }
                await("$label editor connection") {
                    input()?.di?.direct?.instance<TrimeInputMethodService>()?.currentInputEditorInfo?.fieldId == field.id
                }
                main { window().switchKeyboard("audit_macro") }
                await("$label macro keyboard") {
                    val root = input()
                    if (root == null || root.di.direct.instance<TrimeInputMethodService>().currentInputEditorInfo?.fieldId != field.id) {
                        false
                    } else {
                        val keyboard = descendants(root).filterIsInstance<KeyboardView>().firstOrNull { it.isShown }
                        val index = window().currentKeyboard.keys.indexOfFirst { it.getLabel() == label }
                        index >= 0 && keyboard?.getChildAt(index)?.let { it.isShown && it.width > 0 && it.height > 0 } == true &&
                            RimeDaemon.readCached { statusCached.isAsciiMode } == true
                    }
                }
                val owner = main { checkNotNull(input()).di.direct.instance<TrimeInputMethodService>().editorToken }
                main { field.setSelection(start) }
                await("$label initial selection") {
                    val service = input()?.di?.direct?.instance<TrimeInputMethodService>()
                    service != null && service.editorToken == owner && service.currentInputEditorInfo?.fieldId == field.id &&
                        field.selectionStart == start && field.selectionEnd == start
                }
                // Selection callbacks can be queued by the editor; allow them to settle
                // before pressing the macro, with no barrier between its own actions.
                SystemClock.sleep(300)
                check(main { checkNotNull(input()).di.direct.instance<TrimeInputMethodService>().editorToken == owner })
                val initialSelection = main { field.selectionStart to field.selectionEnd }
                check(initialSelection == (start to start)) {
                    "$label expected initial selection $start, got $initialSelection"
                }
                sendStatus(0, Bundle().apply { putString("stream", "INITIAL: $label selection=${initialSelection.first}:${initialSelection.second}\n") })
                val shiftedKeyboard = if (shifted) main { window().currentKeyboard.also { it.setShifted(false, true) } } else null
                val observed = try {
                    click(label)
                    SystemClock.sleep(2000)
                    main { Triple(field.text.toString(), field.selectionStart, field.selectionEnd) }
                } finally {
                    shiftedKeyboard?.let { keyboard -> main { keyboard.setShifted(false, false) } }
                }
                val codes = observed.first.map { "U+%04X".format(it.code) }.joinToString(" ")
                val message = "$label expected=$expected observed=$codes cursor=${observed.second}:${observed.third}"
                sendStatus(0, Bundle().apply { putString("stream", "OBSERVE: $message\n") })
                if (observed.first != expected || observed.second != cursor || observed.third != cursor) failures += message
            }
            verify("A", "", "xY", 2)
            verify("B", "", "Yx", 2)
            verify("E", "", "X", 1, shifted = true)
            verify("E", "ab", "xab", 1)
            verify("F", "ab", "abY", 3)
            verify("C", "ab", "xabY", 4)
            verify("D", "", "```\n\n```", 3)
            verify("G", "```\n\n```", "```\n\n```", 3, 8)
            originalSchema = RimeDaemon.readCached { statusCached.schemaId }
            originalAscii = RimeDaemon.readCached { statusCached.isAsciiMode }
            val chineseField = main {
                EditText(host).apply {
                    id = View.generateViewId()
                    inputType = InputType.TYPE_CLASS_TEXT
                    host.setContentView(this)
                    requestFocus()
                    imm.showSoftInput(this, 0)
                }
            }
            await("Chinese composition editor") { input()?.di?.direct?.instance<TrimeInputMethodService>()?.currentInputEditorInfo?.fieldId == chineseField.id }
            runBlocking {
                withTimeout(30_000) {
                    checkNotNull(session).runOnReady {
                        check(selectSchema("luna_pinyin_simp"))
                        setRuntimeOption("ascii_mode", false)
                    }
                }
            }
            main { window().switchKeyboard("audit_macro_native") }
            await("Chinese macro keyboard") { window().currentKeyboard.keys.any { it.getLabel() == "H" } }
            await("Chinese input mode") { RimeDaemon.readCached { !statusCached.isAsciiMode && statusCached.schemaId == "luna_pinyin_simp" } == true }
            main { checkNotNull(input()).di.direct.instance<CommonKeyboardActionListener>().listener.onText("ni") }
            await("real pinyin composition") { RimeDaemon.readCached { statusCached.isComposing } == true }
            click("H")
            await("Chinese composition and literal alias") { chineseField.text.toString() == "你好" }
            SystemClock.sleep(1000)
            check(main { chineseField.text.toString() == "你好" }) { "Chinese macro output changed after the queue settled" }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: native pinyin composition commits before Chinese literal alias\n") })
            check(failures.isEmpty()) { failures.joinToString("; ") }
        } catch (error: Throwable) { failure = error }
        finally {
            try {
                activity?.let { main { it.finish() } }
                if (session != null && originalSchema != null && originalAscii != null) runBlocking {
                    checkNotNull(session).runOnReady {
                        check(selectSchema(checkNotNull(originalSchema)))
                        setRuntimeOption("ascii_mode", checkNotNull(originalAscii))
                    }
                }
                restoreTheme?.invoke()
                if (session != null) main { RimeDaemon.destroySession(javaClass.name) }
                source?.let { if (ownsSource && it.exists()) check(it.delete()) }
                artifact?.let { if (ownsArtifact && it.exists()) check(it.delete()) }
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        result.putBoolean("passed", failure == null)
        result.putString("stream", failure?.stackTraceToString() ?: "PASS: macro ordering and fence on real editor\n")
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
