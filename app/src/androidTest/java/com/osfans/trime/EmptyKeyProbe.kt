/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeYaml
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyAction
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.Keyboard
import com.osfans.trime.ime.keyboard.KeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardView
import com.osfans.trime.ime.popup.PopupDelegate
import kotlinx.serialization.decodeFromString
import org.kodein.di.direct
import org.kodein.di.instance

/** Decode real YAML, construct the production model and draw/dispatch the actual key views. */
internal object EmptyKeyProbe {
    fun verify(instrumentation: Instrumentation, service: TrimeInputMethodService, input: InputView, themeId: String) {
        fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            return checkNotNull(result).getOrThrow()
        }
        val config = ThemeYaml.parser.decodeFromString<TextKeyboard>(
            """
            width: 20
            height: 48
            columns: 4
            keys:
              - {click: a}
              - {click: ''}
              - {width: 10}
              - {click: b}
              - {click: '', width: 30}
              - {click: '', swipe_up: a, long_click: b}
            """.trimIndent(),
        )
        val popupPref = AppPrefs.defaultInstance().keyboard.popupOnKeyPress
        val previousPopup = popupPref.getValue()
        try {
            main { popupPref.setValue(true) }
            for (t9 in listOf(false, true)) {
                main {
                    val width = input.width.coerceAtLeast(500)
                    val model = Keyboard(service, input.theme, width, config.copy(t9Layout = t9))
                    check(model.keys.size == 5) { "$themeId/$t9: missing empty keys or a spacer became a key" }
                    val (first, blank, second, wideBlank, gestureBlank) = model.keys
                    check(blank.click == null && wideBlank.click == null && gestureBlank.click == null)
                    check(blank.width == first.width && blank.width > 0) { "$themeId/$t9: default empty width differs" }
                    check(kotlin.math.abs(wideBlank.width - width * .3f) <= 2f) { "$themeId/$t9: explicit empty width differs" }
                    check(second.x - blank.x - blank.width == (width * .1f).toInt()) { "$themeId/$t9: omitted-click spacer changed" }
                    check(listOf(first, blank, second, wideBlank).all { it.row == 0 } && gestureBlank.row == 1) {
                        "$themeId/$t9: empty keys did not count toward row columns"
                    }
                    var presses = 0
                    var actions = 0
                    val listener = object : KeyboardActionListener {
                        override fun onPress(keyEventCode: Int) { presses++ }
                        override fun onAction(action: KeyAction) { actions++ }
                        override fun onKey(keyEventCode: Int, metaState: Int) { actions++ }
                        override fun onText(input: String) { actions++ }
                    }
                    val view = KeyboardView(
                        service, input.theme, model, input.di.direct.instance<PopupDelegate>(), service,
                        listener, input.di.direct.instance<EnterKeyDisplayDelegate>(),
                    )
                    input.addView(view, ViewGroup.LayoutParams(width, model.height))
                    try {
                        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(model.height, View.MeasureSpec.EXACTLY))
                        view.layout(0, 0, width, model.height)
                        val blankView = view.getChildAt(1) as KeyView
                        check(blankView.width > 0 && blankView.height > 0 && !blankView.isClickable)
                        check(blank.getBackgroundDrawable() != null) { "$themeId/$t9: blank key has no background" }
                        val bitmap = Bitmap.createBitmap(blankView.width, blankView.height, Bitmap.Config.ARGB_8888)
                        try {
                            blankView.draw(Canvas(bitmap))
                            check(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) ushr 24 != 0) { "$themeId/$t9: blank background did not draw" }
                        } finally { bitmap.recycle() }
                        val now = SystemClock.uptimeMillis()
                        fun dispatch(target: View, action: Int, y: Float): Boolean {
                            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, target.width / 2f, y, 0)
                            event.source = InputDevice.SOURCE_TOUCHSCREEN
                            return try { target.dispatchTouchEvent(event) } finally { event.recycle() }
                        }
                        check(!dispatch(blankView, MotionEvent.ACTION_DOWN, blankView.height / 2f)) { "$themeId/$t9: inert key accepted touch" }
                        check(presses == 0 && actions == 0) { "$themeId/$t9: inert key emitted input feedback" }
                        val gestureView = view.getChildAt(4) as KeyView
                        check(dispatch(gestureView, MotionEvent.ACTION_DOWN, gestureView.height / 2f))
                        check(dispatch(gestureView, MotionEvent.ACTION_UP, -1000f))
                        check(presses == 0 && actions == 1) { "$themeId/$t9: blank key lost its configured swipe action" }
                        val info = gestureView.createAccessibilityNodeInfo()
                        check(!info.isClickable && info.isLongClickable && gestureView.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES) {
                            "$themeId/$t9: gesture-only key has incorrect accessibility actions"
                        }
                        check(gestureView.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null)) {
                            "$themeId/$t9: gesture-only key did not accept accessibility long press"
                        }
                        check(presses == 0 && actions == 2) { "$themeId/$t9: gesture-only key lost its long-press action" }
                    } finally {
                        view.onDetach()
                        input.removeView(view)
                    }
                }
            }
        } finally {
            main { popupPref.setValue(previousPopup) }
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: $themeId blank key model, drawing, no-op touch and swipe with preview\n") })
    }
}
