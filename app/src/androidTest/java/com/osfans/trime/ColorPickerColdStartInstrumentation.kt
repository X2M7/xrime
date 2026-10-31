/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ui.main.MainActivity
import com.osfans.trime.ui.main.NavigationRoute
import java.io.File

/** Real settings preference, in a fresh process with another IME selected. */
class ColorPickerColdStartInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        var code = Activity.RESULT_CANCELED
        fun <T> main(block: () -> T): T {
            var value: Result<T>? = null
            runOnMainSync { value = runCatching(block) }
            return checkNotNull(value).getOrThrow()
        }
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
        fun nodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            yield(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { yieldAll(nodes(it)) }
        }
        fun waitUntil(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(50)
            }
            error("Timed out waiting for $label")
        }
        try {
            check(Build.VERSION.SDK_INT >= 29 && Build.HARDWARE in setOf("ranchu", "goldfish")) { "Requires a dedicated API 29+ emulator" }
            check(File(targetContext.getExternalFilesDir(null), "runtime-audit-dedicated").isFile) { "Dedicated test installation required" }
            val selectedIme = Settings.Secure.getString(targetContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
            check(!selectedIme.startsWith(targetContext.packageName + "/")) { "Select another IME before starting the cold probe" }
            val themeField = ThemeManager::class.java.getDeclaredField("_activeTheme").apply { isAccessible = true }
            check(main { themeField.get(null) == null }) { "Theme was initialized before the cold probe" }
            fun routeIntent() = Intent(targetContext, MainActivity::class.java).setAction(Intent.ACTION_RUN)
                .putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, NavigationRoute.Theme)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            try {
                targetContext.startActivity(routeIntent())
                activity = checkNotNull(waitForMonitorWithTimeout(monitor, 30_000)) { "MainActivity did not start" }
            } finally {
                removeMonitor(monitor)
            }
            val title = targetContext.getString(R.string.normal_mode_color)
            fun findText(text: String): AccessibilityNodeInfo? = uiAutomation.rootInActiveWindow?.let { root ->
                nodes(root).firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true && it.isEnabled }
            }
            fun clickText(text: String) {
                var node = checkNotNull(findText(text))
                while (!node.isClickable && node.parent != null) node = checkNotNull(node.parent)
                check(node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Could not click $text" }
            }
            waitUntil("settings page or setup wizard") {
                findText(title) != null || findText(targetContext.getString(R.string.setup__skip)) != null
            }
            if (findText(targetContext.getString(R.string.setup__skip)) != null) {
                clickText(targetContext.getString(R.string.setup__skip))
                val confirm = targetContext.getString(R.string.setup__skip_hint_yes)
                waitUntil("setup skip confirmation") { findText(confirm) != null }
                clickText(confirm)
                val quickSettings = targetContext.getString(R.string.quick_settings)
                val notificationTitle = targetContext.getString(R.string.notification_permission_title)
                waitUntil("main screen or notification prompt after skipping setup") {
                    findText(quickSettings) != null || findText(notificationTitle) != null
                }
                if (findText(notificationTitle) != null) {
                    clickText(targetContext.getString(android.R.string.cancel))
                    waitUntil("main screen after notification prompt") { findText(quickSettings) != null }
                }
                targetContext.startActivity(routeIntent())
            }
            fun findPreference() = findText(title)
            waitUntil("theme settings preference") { findPreference() != null }
            check(main { themeField.get(null) == null }) { "Theme initialized before the settings preference was clicked" }
            fun clickPreference() {
                clickText(title)
            }
            fun dialogList(): ListView? = WindowInspector.getGlobalWindowViews().asSequence()
                .flatMap(::descendants)
                .filterIsInstance<ListView>()
                .firstOrNull { it.isShown && (it.adapter?.count ?: 0) > 0 }
            clickPreference()
            waitUntil("cold color dialog") { main { dialogList() != null } }
            check(main { themeField.get(null) != null && ColorManager.currentScope() != null }) { "Color dialog appeared without initialized theme and colors" }
            main {
                val root = checkNotNull(dialogList()).rootView
                check(descendants(root).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == title }) { "Color dialog title missing" }
                checkNotNull(root.findViewById<View>(android.R.id.button2)).performClick()
            }
            waitUntil("color dialog dismissal") { main { dialogList() == null } }
            clickPreference()
            waitUntil("color dialog retry") { main { dialogList() != null } }
            main { checkNotNull(checkNotNull(dialogList()).rootView.findViewById<View>(android.R.id.button2)).performClick() }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: cold settings preference initializes theme and displays color choices; reopening works\n") })
            code = Activity.RESULT_OK
            result.putBoolean("passed", true)
        } catch (failure: Throwable) {
            result.putString("error", failure.stackTraceToString())
            result.putBoolean("passed", false)
        } finally {
            activity?.let { host -> main { host.finish() } }
            finish(code, result)
        }
    }
}
