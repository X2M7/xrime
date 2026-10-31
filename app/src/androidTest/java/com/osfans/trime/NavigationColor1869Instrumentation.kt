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
import androidx.core.view.WindowCompat
import androidx.core.view.children
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemePrefs
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ui.main.EditorProbeActivity
import com.osfans.trime.util.ColorUtils
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.File

/** Real API 34 IME window color transition; a dedicated emulator is required. */
class NavigationColor1869Instrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        var session: RimeSession? = null
        var editor: Activity? = null
        var originalTheme: String? = null
        var failure: Throwable? = null
        val result = Bundle()
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
        try {
            waitForIdleSync()
            check(Build.VERSION.SDK_INT == 34 && Build.HARDWARE in setOf("ranchu", "goldfish")) { "Requires dedicated Android 14 emulator" }
            check(File(targetContext.getExternalFilesDir(null), "runtime-audit-dedicated").isFile) { "Requires dedicated runtime-audit marker" }
            val prefs = ThemeManager.prefs
            originalTheme = prefs.selectedTheme.getValue()
            session = main { RimeDaemon.createSession(javaClass.name) }
            runBlocking {
                withTimeout(300_000) { checkNotNull(session).runOnReady { } }
                ThemeManager.init(targetContext.resources.configuration)
                check(ThemeManager.selectTheme("trime") == "trime")
            }
            val activity = startActivitySync(Intent(targetContext, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            editor = activity
            val field = main {
                EditText(activity).apply { id = View.generateViewId(); activity.setContentView(this); requestFocus() }
            }
            val manager = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            main { manager.showSoftInput(field, 0) }
            val deadline = SystemClock.uptimeMillis() + 30_000
            var service: TrimeInputMethodService? = null
            while (service == null && SystemClock.uptimeMillis() < deadline) {
                service = main { input()?.di?.direct?.instance<TrimeInputMethodService>() }
                if (service == null) SystemClock.sleep(50)
            }
            val ime = checkNotNull(service) { "IME view not visible" }
            val ownerDeadline = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < ownerDeadline && !main { ime.currentInputEditorInfo?.fieldId == field.id && ime.isInputViewShown }) SystemClock.sleep(50)
            check(main { ime.currentInputEditorInfo?.fieldId == field.id && ime.isInputViewShown }) { "Probe editor did not acquire the visible IME" }
            val nav = prefs.navbarBackground
            val color = prefs.normalModeColor
            val follow = prefs.followSystemDayNight
            val navPresent = nav.sharedPreferences.contains(nav.key)
            val navRaw = nav.sharedPreferences.getString(nav.key, null)
            val colorPresent = color.sharedPreferences.contains(color.key)
            val colorRaw = color.sharedPreferences.getString(color.key, null)
            val followPresent = follow.sharedPreferences.contains(follow.key)
            val followRaw = follow.sharedPreferences.getBoolean(follow.key, false)
            val oldScheme = main { ColorManager.activeColorScheme }
            val window = main { checkNotNull(ime.window.window) }
            val token = main { ime.editorToken }
            @Suppress("DEPRECATION")
            fun select(id: String): Int {
                val generation = main { ColorManager.colorGeneration }
                main { ColorManager.setColorScheme(id) }
                val expected = main { checkNotNull(ColorManager.currentScope()).colors.backColor }
                val timeout = SystemClock.uptimeMillis() + 10_000
                while (SystemClock.uptimeMillis() < timeout) {
                    val ok = main {
                        ime.isInputViewShown && ime.editorToken == token && ime.currentInputEditorInfo?.fieldId == field.id &&
                            ime.window.window === window && window.navigationBarColor == expected &&
                            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars ==
                            !ColorUtils.isContrastedDark(expected)
                    }
                    if (ok) {
                        sendStatus(0, Bundle().apply { putString("stream", "PASS: $id actual IME window color $expected, generation $generation -> ${ColorManager.colorGeneration}\n") })
                        return expected
                    }
                    SystemClock.sleep(25)
                }
                val diagnostic = main { "scheme=$id expected=$expected actual=${window.navigationBarColor} shown=${ime.isInputViewShown} sameEditor=${ime.editorToken == token} sameWindow=${ime.window.window === window} generation=${ColorManager.colorGeneration}" }
                error("Navigation color did not update: $diagnostic")
            }
            try {
                main { follow.setValue(false); nav.setValue(ThemePrefs.NavbarBackground.COLOR_ONLY) }
                main { ColorManager.setColorScheme("tantsing") }
                val first = select("google")
                val second = select("tantsing")
                check(first != second) { "Expected distinct color schemes" }
                check(select("google") == first)
            } finally {
                main {
                    fun restoreString(key: String, present: Boolean, raw: String?, store: android.content.SharedPreferences) {
                        val edit = store.edit()
                        if (present) edit.putString(key, checkNotNull(raw)) else edit.remove(key)
                        check(edit.commit())
                    }
                    restoreString(nav.key, navPresent, navRaw, nav.sharedPreferences)
                    restoreString(color.key, colorPresent, colorRaw, color.sharedPreferences)
                    val edit = follow.sharedPreferences.edit()
                    if (followPresent) edit.putBoolean(follow.key, followRaw) else edit.remove(follow.key)
                    check(edit.commit())
                    val schemes = ThemeManager.activeTheme.presetColorSchemes
                    val alternate = schemes.getValue(if (oldScheme == schemes.getValue("tantsing")) "google" else "tantsing")
                    ColorManager.setColorSchemeForTesting(alternate)
                    ColorManager.setColorSchemeForTesting(oldScheme)
                }
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                main { editor?.finish() }
                originalTheme?.let { theme -> runBlocking { ThemeManager.selectTheme(theme) } }
                if (session != null) main { RimeDaemon.destroySession(javaClass.name) }
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        val error = failure
        result.putBoolean("passed", error == null)
        result.putString("stream", if (error == null) "PASS: live navigation color changes\n" else "FAIL: ${error.stackTraceToString()}\n")
        finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
