/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.keyboard.Keyboard
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.pairNumberStyle
import com.osfans.trime.ime.keyboard.resolveKeyboardConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import splitties.dimensions.dp
import java.io.File

/** Model-level checks for older Android; live IME transitions are tested separately. */
class LegacyKeyboardGeometryInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var session: RimeSession? = null
        var originalTheme: String? = null
        var failure: Throwable? = null
        fun <T> main(block: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            var outcome: Result<T>? = null
            runOnMainSync { outcome = runCatching(block) }
            return checkNotNull(outcome).getOrThrow()
        }
        fun phase(message: String) = sendStatus(0, Bundle().apply { putString("stream", "PASS: $message\n") })
        try {
            waitForIdleSync()
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
            val context = targetContext
            check(File(context.getExternalFilesDir(null), "runtime-audit-dedicated").isFile) { "Requires a dedicated runtime-audit installation" }
            originalTheme = ThemeManager.prefs.selectedTheme.getValue()
            session = main { RimeDaemon.createSession(javaClass.name) }
            runBlocking {
                withTimeout(300_000) { checkNotNull(session).runOnReady { } }
                ThemeManager.init(context.resources.configuration)
                for (themeId in listOf("trime", "tongwenfeng.trime")) {
                    check(ThemeManager.selectTheme(themeId) == themeId) { "Theme unavailable: $themeId" }
                    main {
                        val theme = ThemeManager.activeTheme
                        fun config(id: String) = checkNotNull(resolveKeyboardConfig(id, theme.presetKeyboards)) { "Missing keyboard $themeId/$id" }
                        fun geometry(keyboard: Keyboard) = keyboard.keys.map { listOf(it.x, it.y, it.width, it.height, it.row, it.column) }
                        fun valid(keyboard: Keyboard, width: Int) {
                            check(keyboard.keys.isNotEmpty() && keyboard.keyboardHeight > 0)
                            keyboard.keys.forEach {
                                check(it.x >= 0 && it.y >= 0 && it.width > 0 && it.height > 0)
                                check(it.x + it.width <= width && it.y + it.height <= keyboard.keyboardHeight)
                            }
                        }
                        val t9 = config("luna_pinyin_t9")
                        val numeric = config("number")
                        val paired = pairNumberStyle(numeric, t9, theme.style.keyWidth)
                        check(paired.keys.map { it.click to it.width } == numeric.keys.map { it.click to it.width })
                        // Exercise the physical-orientation path even when the optional
                        // landscape layout is disabled, as in upstream issue #1515.
                        val prefs = AppPrefs.defaultInstance().keyboard
                        val oldHeight = prefs.keyboardHeight.getValue()
                        val oldLandscape = prefs.landscapeMode.getValue()
                        try {
                            prefs.keyboardHeight.setValue(0)
                            prefs.landscapeMode.setValue(AppPrefs.Keyboard.LandscapeMode.NEVER)
                            val customTheme = theme.copy(style = theme.style.copy(keyboardHeight = 250, keyboardHeightLand = 180))
                            val keyboardConfig = t9.copy(keyboardHeight = 0, keyboardHeightLand = 0, t9Layout = false)
                            fun oriented(orientation: Int): android.content.Context = context.createConfigurationContext(
                                Configuration(context.resources.configuration).apply { this.orientation = orientation },
                            )
                            val portrait = oriented(Configuration.ORIENTATION_PORTRAIT)
                            val landscape = oriented(Configuration.ORIENTATION_LANDSCAPE)
                            check(Keyboard(portrait, customTheme, portrait.dp(320), keyboardConfig).keyboardHeight == portrait.dp(250))
                            check(Keyboard(landscape, customTheme, landscape.dp(320), keyboardConfig).keyboardHeight == landscape.dp(180))
                            val spacedTheme = customTheme.copy(style = customTheme.style.copy(horizontalGap = 4, verticalGap = 8))
                            val inherited = Keyboard(portrait, spacedTheme, portrait.dp(320), keyboardConfig.copy(horizontalGap = 0, verticalGap = 0))
                            val explicit = Keyboard(portrait, spacedTheme, portrait.dp(320), keyboardConfig.copy(verticalGap = 4))
                            check(inherited.horizontalGap == portrait.dp(4) && inherited.verticalGap == portrait.dp(8))
                            check(explicit.verticalGap == portrait.dp(4))
                        } finally {
                            prefs.keyboardHeight.setValue(oldHeight)
                            prefs.landscapeMode.setValue(oldLandscape)
                        }
                        for (widthDp in listOf(320, 392, 600)) {
                            val width = context.dp(widthDp)
                            val native = Keyboard(context, theme, width, t9, heightBudgetPx = context.dp(240))
                            val number = Keyboard(context, theme, width, paired, pairedHeightPx = native.keyboardHeight)
                            valid(native, width)
                            valid(number, width)
                            check(native.isT9Layout && !number.isT9Layout)
                            check(native.keyboardHeight == number.keyboardHeight)
                            check(native.horizontalGap == number.horizontalGap && native.verticalGap == number.verticalGap)
                            check(native.roundCorner == number.roundCorner && native.keyBorder == number.keyBorder)
                            if (themeId == "trime") {
                                val chinese = Keyboard(context, theme, width, config("qwerty"))
                                val english = Keyboard(context, theme, width, config("qwerty_ascii"))
                                valid(chinese, width)
                                valid(english, width)
                                check(!chinese.asciiMode && english.asciiMode)
                                check(chinese.keyboardHeight == english.keyboardHeight && geometry(chinese) == geometry(english))
                                check(chinese.horizontalGap == english.horizontalGap && chinese.verticalGap == english.verticalGap)
                                check(chinese.roundCorner == english.roundCorner && chinese.keyBorder == english.keyBorder)
                            }
                        }
                    }
                    phase("$themeId production keyboard models at three widths")
                    checkNotNull(session).runOnReady {
                        val oldAscii = getRuntimeOption("ascii_mode")
                        try {
                            val keys = main {
                                val theme = ThemeManager.activeTheme
                                val config = TextKeyboard(keys = listOf(
                                    TextKeyboard.TextKey(click = KeyActionToken.Plain("ㅎ"), ascii = KeyActionToken.Plain("g")),
                                    TextKeyboard.TextKey(click = KeyActionToken.Plain("ㅎ"), ascii = KeyActionToken.Plain("g"), sendBindings = false),
                                ))
                                Keyboard(context, theme, context.dp(320), config).keys
                            }
                            setRuntimeOption("ascii_mode", false)
                            check(main { keys.all { it.getAction(KeyBehavior.CLICK) === it.click } })
                            setRuntimeOption("ascii_mode", true)
                            check(main { keys.all { it.getAction(KeyBehavior.CLICK)?.code == KeyEvent.KEYCODE_G } })
                        } finally {
                            setRuntimeOption("ascii_mode", oldAscii)
                        }
                    }
                    phase("$themeId ASCII binding follows the active mode")
                }
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                originalTheme?.let { theme -> runBlocking { ThemeManager.selectTheme(theme) } }
                if (session != null) main { RimeDaemon.destroySession(javaClass.name) }
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        val error = failure
        result.putBoolean("passed", error == null)
        result.putString("stream", if (error == null) "PASS: legacy keyboard model geometry\n" else "FAIL: ${error.stackTraceToString()}\n")
        finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
