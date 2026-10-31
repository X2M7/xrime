/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.keyboard.Key
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.Keyboard
import com.osfans.trime.ime.keyboard.KeyboardWindow
import org.json.JSONArray
import org.json.JSONObject
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.math.abs

/** Draft: caller must supply a focused, visible IME using the built-in trime theme. */
internal object BuiltinKeyboardColorProbe {
    private data class CapturedKey(val view: KeyView, val key: Key, val keyboard: Keyboard, val id: String, val width: Int, val height: Int)

    private fun sameCapture(a: CapturedKey, b: CapturedKey): Boolean =
        a.view === b.view && a.key === b.key && a.keyboard === b.keyboard &&
            a.id == b.id && a.width == b.width && a.height == b.height

    private fun <T> main(instrumentation: Instrumentation, block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) view.children.forEach { yieldAll(descendants(it)) }
    }

    private fun similar(actual: Int, expected: Int): Boolean =
        abs(Color.red(actual) - Color.red(expected)) <= 32 &&
            abs(Color.green(actual) - Color.green(expected)) <= 32 &&
            abs(Color.blue(actual) - Color.blue(expected)) <= 32 && Color.alpha(actual) >= 240

    private fun backgroundPixel(drawable: Drawable, width: Int, height: Int): Int {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val previousBounds = Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            return bitmap.getPixel(width / 2, height / 2)
        } finally {
            drawable.setBounds(previousBounds)
            bitmap.recycle()
        }
    }

    private fun visibleTextPixels(view: KeyView, foreground: Int): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            var matching = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (similar(bitmap.getPixel(x, y), foreground)) matching++
            }
            return matching
        } finally {
            bitmap.recycle()
        }
    }

    fun verify(instrumentation: Instrumentation, input: InputView): JSONArray {
        val window = main(instrumentation) { input.di.direct.instance<KeyboardWindow>() }
        val theme = main(instrumentation) { ThemeManager.activeTheme }
        val activeConfig = main(instrumentation) { field(ThemeManager, "activeConfigId") as? String }
        check(activeConfig == "trime") { "Probe requires built-in trime theme; got $activeConfig" }
        check(listOf("default", "qwerty").all { it in theme.presetKeyboards }) { "Required keyboard preset missing" }
        check(listOf("google_plus", "google", "tantsing").all { it in theme.presetColorSchemes }) { "Required color scheme missing" }
        val originalScheme = main(instrumentation) { ColorManager.activeColorScheme }
        val originalKeyboard = main(instrumentation) { field(window, "currentKeyboardId") as String }
        val expected = mapOf(
            "google_plus" to listOf(Color.rgb(0xdd, 0x4b, 0x39), Color.WHITE, Color.WHITE, Color.rgb(0xdd, 0x4b, 0x39)),
            "google" to listOf(Color.BLACK, Color.WHITE, Color.WHITE, Color.rgb(0x39, 0x75, 0xce)),
            "tantsing" to listOf(Color.BLACK, Color.rgb(0xe3, 0xe3, 0xe3), Color.rgb(0xe3, 0xe3, 0xe3), Color.rgb(0x73, 0x0b, 0x6f)),
        )
        val results = JSONArray()
        try {
            for (layout in listOf("default", "qwerty")) {
                main(instrumentation) { window.switchKeyboard(layout) }
                val deadline = SystemClock.uptimeMillis() + 15000
                var selected: CapturedKey? = null
                var previous: CapturedKey? = null
                var stableSince = 0L
                while (selected == null && SystemClock.uptimeMillis() < deadline) {
                    val sample = main(instrumentation) {
                        val currentId = field(window, "currentKeyboardId") as String
                        if (currentId != layout) return@main null
                        val model = window.currentKeyboard
                        descendants(input).filterIsInstance<KeyView>()
                            .filter { it.isAttachedToWindow && it.isShown && it.width > 0 && it.height > 0 }
                            .mapNotNull { view -> (field(view, "key") as? Key)?.let { view to it } }
                            .firstOrNull { (_, key) ->
                                key.code == KeyEvent.KEYCODE_Q && model.keys.any { it === key }
                            }
                            ?.let { (view, key) -> CapturedKey(view, key, model, currentId, view.width, view.height) }
                    }
                    val now = SystemClock.uptimeMillis()
                    if (sample == null) {
                        previous = null
                        stableSince = 0L
                    } else if (previous == null || !sameCapture(checkNotNull(previous), sample)) {
                        previous = sample
                        stableSince = now
                    } else if (now - stableSince >= 350) {
                        selected = sample
                    }
                    if (selected == null) SystemClock.sleep(40)
                }
                val capture = checkNotNull(selected) { "No stable attached Q key for $layout" }
                val view = capture.view
                val key = capture.key
                val selectedKeyboard = capture.keyboard
                check(main(instrumentation) { key.getLabel().equals("q", ignoreCase = true) }) { "Unexpected Q label" }
                check(main(instrumentation) { key.click?.isSticky == false }) { "The Q key must not be sticky" }
                val previousPressed = main(instrumentation) { key.isPressed }
                try {
                    for (schemeId in listOf("google_plus", "google", "tantsing", "google_plus")) {
                        val colors = expected.getValue(schemeId)
                        main(instrumentation) {
                            ColorManager.setColorSchemeForTesting(theme.presetColorSchemes.getValue(schemeId))
                        }
                        main(instrumentation) { } // listener queue barrier
                        for (pressed in listOf(false, true)) {
                            val result = main(instrumentation) {
                                val currentKeyboard = window.currentKeyboard
                                val currentId = field(window, "currentKeyboardId")
                                val attached = view.isAttachedToWindow
                                val belongs = currentKeyboard.keys.any { it === key }
                                check(currentId == layout && attached && belongs && currentKeyboard === selectedKeyboard) {
                                    "$layout/$schemeId/$pressed changed live keyboard: currentId=$currentId " +
                                        "attached=$attached belongs=$belongs selectedModel=${System.identityHashCode(selectedKeyboard)} " +
                                        "currentModel=${System.identityHashCode(currentKeyboard)} view=${System.identityHashCode(view)} " +
                                        "key=${System.identityHashCode(key)} oldSize=${capture.width}x${capture.height} " +
                                        "currentSize=${view.width}x${view.height} generation=${ColorManager.colorGeneration}"
                                }
                                if (key.isPressed != pressed) {
                                    if (pressed) key.onPressed() else key.onReleased()
                                }
                                view.refreshAppearance()
                                val text = key.getTextColor()
                                val background = backgroundPixel(checkNotNull(key.getBackgroundDrawable()), view.width, view.height)
                                val expectedText = colors[if (pressed) 2 else 0]
                                val expectedBackground = colors[if (pressed) 3 else 1]
                                check(text == expectedText) { "$layout/$schemeId/$pressed text ${Integer.toHexString(text)} != ${Integer.toHexString(expectedText)}" }
                                check(similar(background, expectedBackground)) { "$layout/$schemeId/$pressed background ${Integer.toHexString(background)} != ${Integer.toHexString(expectedBackground)}" }
                                check(!similar(text, background)) { "$layout/$schemeId/$pressed foreground blends into background" }
                                val pixels = visibleTextPixels(view, expectedText)
                                check(pixels >= 3) { "$layout/$schemeId/$pressed has no visible foreground pixels" }
                                JSONObject().put("layout", layout).put("scheme", schemeId).put("pressed", pressed)
                                    .put("text", Integer.toHexString(text)).put("background", Integer.toHexString(background))
                                    .put("foreground_pixels", pixels).put("generation", ColorManager.colorGeneration)
                            }
                            results.put(result)
                        }
                    }
                } finally {
                    main(instrumentation) {
                        if (key.isPressed != previousPressed) {
                            if (previousPressed) key.onPressed() else key.onReleased()
                        }
                        view.refreshAppearance()
                    }
                }
            }
            return results
        } finally {
            main(instrumentation) {
                ColorManager.setColorSchemeForTesting(originalScheme)
                window.switchKeyboard(originalKeyboard)
            }
            main(instrumentation) { }
        }
    }
}
