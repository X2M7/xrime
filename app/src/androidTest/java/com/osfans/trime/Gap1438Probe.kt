// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.Keyboard
import com.osfans.trime.ime.keyboard.KeyboardView
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.resolveKeyboardConfig
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ui.main.EditorProbeActivity
import org.kodein.di.direct
import org.kodein.di.instance
import splitties.dimensions.dp
import java.io.File

/** Attached production KeyViews and rendered bounds for issue 1438, emulator only. */
internal object Gap1438Probe {
    @SuppressLint("PrivateApi")
    private fun roots(): List<View> {
        val type = Class.forName("android.view.WindowManagerGlobal")
        val instance = type.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        return (type.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>).toList()
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    fun run(instrumentation: Instrumentation) {
        val result = Bundle()
        var activity: Activity? = null
        fun <T> main(block: () -> T): T {
            var value: Result<T>? = null
            instrumentation.runOnMainSync { value = runCatching(block) }
            return checkNotNull(value).getOrThrow()
        }
        fun input(): InputView? = roots().asSequence().flatMap(::descendants).filterIsInstance<InputView>().firstOrNull { it.isShown }
        fun until(label: String, test: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < end) {
                if (main(test)) return
                SystemClock.sleep(50)
            }
            error("Timed out: $label")
        }
        var checks = 0
        val outcome = runCatching {
            check(Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(File(instrumentation.targetContext.getExternalFilesDir(null), "runtime-audit-dedicated").isFile)
            val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = host
            val root = FrameLayout(host)
            val field = EditText(host)
            main { root.addView(field); host.setContentView(root); field.requestFocus() }
            val manager = host.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            until("editor focus") { field.hasWindowFocus() && field.hasFocus() }
            main { manager.showSoftInput(field, 0) }
            until("actual IME available") { input() != null }
            for ((globalGap, overrideGap, expectedDp) in listOf(Triple(8, 0, 8), Triple(8, 4, 4))) {
                val child = main {
                    val ime = checkNotNull(input())
                    val theme = ThemeManager.activeTheme.copy(style = ThemeManager.activeTheme.style.copy(verticalGap = globalGap))
                    val config = checkNotNull(resolveKeyboardConfig("qwerty", theme.presetKeyboards)).copy(verticalGap = overrideGap)
                    val keyboard = Keyboard(host, theme, host.dp(320), config)
                    val view = KeyboardView(host, theme, keyboard, ime.di.direct.instance<PopupDelegate>(),
                        ime.di.direct.instance<TrimeInputMethodService>(), ime.di.direct.instance<CommonKeyboardActionListener>().listener,
                        ime.di.direct.instance<EnterKeyDisplayDelegate>())
                    root.addView(view, FrameLayout.LayoutParams(host.dp(320), keyboard.keyboardHeight))
                    view to keyboard
                }
                val view = child.first
                val keyboard = child.second
                until("attached keyboard laid out") { view.isShown && view.width > 0 && view.height > 0 && view.getChildAt(0).height > 0 }
                main {
                    val keys = keyboard.keys
                    val pair = keys.indices.asSequence().flatMap { upper ->
                        keys.indices.asSequence().map { lower -> upper to lower }
                    }.first { (upper, lower) ->
                        val a = keys[upper]; val b = keys[lower]
                        a.row == 0 && b.row == 1 && maxOf(a.x, b.x) + host.dp(8) < minOf(a.x + a.width, b.x + b.width)
                    }
                    val upper = view.getChildAt(pair.first) as KeyView
                    val lower = view.getChildAt(pair.second) as KeyView
                    val expectedGap = host.dp(expectedDp)
                    val expectedTop = expectedGap / 2
                    val expectedBottom = expectedGap - expectedTop
                    check(upper.paddingBottom == expectedBottom && lower.paddingTop == expectedTop) {
                        "Unexpected row padding: bottom=${upper.paddingBottom}, top=${lower.paddingTop}, gap=$expectedGap"
                    }
                    val gapStart = upper.bottom - upper.paddingBottom
                    val gapEnd = lower.top + lower.paddingTop
                    check(gapEnd - gapStart == expectedGap) { "Actual visible row gap differs from configured padding" }
                    val x = (maxOf(upper.left + upper.paddingLeft, lower.left + lower.paddingLeft) +
                        minOf(upper.right - upper.paddingRight, lower.right - lower.paddingRight)) / 2
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(bitmap))
                        check(Color.alpha(bitmap.getPixel(x, (gapStart + gapEnd) / 2)) == 0) { "Drawn key backgrounds cover the row gap" }
                        check(Color.alpha(bitmap.getPixel(x, gapStart - host.dp(3))) > 0) { "Upper key background is not visible near its row edge" }
                        check(Color.alpha(bitmap.getPixel(x, gapEnd + host.dp(3))) > 0) { "Lower key background is not visible near its row edge" }
                    } finally { bitmap.recycle() }
                    root.removeView(view)
                }
                checks++
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: global=$globalGap keyboard=$overrideGap attached key padding and drawn row gap\n") })
            }
        }
        val cleanup = runCatching { main { activity?.finish() } }.exceptionOrNull()
        val failure = outcome.exceptionOrNull() ?: cleanup
        result.putBoolean("passed", failure == null)
        result.putInt("checks", checks)
        result.putString("stream", failure?.stackTraceToString() ?: "PASS: configured row gaps in attached production views")
        instrumentation.finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
