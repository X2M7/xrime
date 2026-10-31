/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import com.osfans.trime.data.theme.ColorKey
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.ui.ClipboardSuggestionUi

/** Measures the production clipboard suggestion with both narrow and wide toolbars. */
class ClipboardSuggestionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = runCatching {
            // Instrumentation can start before Application.onCreate finishes.
            waitForIdleSync()
            var uiResult: Result<Unit>? = null
            runOnMainSync {
                uiResult = runCatching {
                    val scope = ThemeScope(Theme("clipboard-suggestion-geometry")).apply {
                        updateScheme(ColorKey.entries.associate { it.key to "#ff334455" })
                    }
                    val ui = ClipboardSuggestionUi(targetContext, scope)
                    val density = targetContext.resources.displayMetrics.density
                    fun dp(value: Int) = (value * density + 0.5f).toInt()
                    var clicks = 0
                    ui.dismiss.setOnClickListener { clicks++ }
                    for (width in listOf(120, 300, 480)) {
                        var dismissLeft = -1
                        for (value in listOf("a", "中文🙂".repeat(200))) {
                            ui.text.text = value
                            ui.root.measure(
                                View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(dp(48), View.MeasureSpec.EXACTLY),
                            )
                            ui.root.layout(0, 0, ui.root.measuredWidth, ui.root.measuredHeight)
                            val dismiss = Rect(0, 0, ui.dismiss.width, ui.dismiss.height)
                            val text = Rect(0, 0, ui.text.width, ui.text.height)
                            // This isolated tree is not attached to a window, so screen coordinates
                            // are unavailable. Convert descendant bounds into the root's space.
                            ui.root.offsetDescendantRectToMyCoords(ui.dismiss, dismiss)
                            ui.root.offsetDescendantRectToMyCoords(ui.text, text)
                            check(ui.dismiss.width > 0 && ui.text.width > 0)
                            check(text.right <= dismiss.left) { "Clipboard text overlaps dismiss on ${width}dp toolbar" }
                            check(dismiss.left >= 0 && dismiss.right <= ui.root.width) { "Dismiss is outside ${width}dp toolbar" }
                            if (dismissLeft < 0) dismissLeft = dismiss.left
                            check(dismissLeft == dismiss.left) { "Dismiss moved when clipboard text changed on ${width}dp toolbar" }
                            check(ui.dismiss.performClick())
                        }
                    }
                    check(clicks == 6)
                }
            }
            checkNotNull(uiResult).getOrThrow()
        }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putBoolean("passed", result.isSuccess)
            putString("stream", result.exceptionOrNull()?.stackTraceToString() ?: "PASS: fixed clipboard dismiss geometry and click")
        })
    }
}
