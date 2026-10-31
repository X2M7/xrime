/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.navigation.NavController
import com.osfans.trime.ui.main.MainActivity
import com.osfans.trime.ui.main.NavigationRoute
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real exported activity receives malformed extras from an independent test APK UID. */
class ExternalSettingsRouteInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val context = targetContext
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var failure: Throwable? = null
        val resumed = AtomicReference<MainActivity?>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) { if (activity is MainActivity) resumed.set(activity) }
            override fun onActivityPaused(activity: Activity) { if (activity is MainActivity) resumed.compareAndSet(activity, null) }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) { if (activity is MainActivity) resumed.compareAndSet(activity, null) }
        }
        val application = context.applicationContext as Application
        val setupShown = com.osfans.trime.ui.setup.SetupActivity::class.java.getDeclaredField("shown").apply { isAccessible = true }
        var previousSetupShown: Boolean? = null
        try {
            waitForIdleSync()
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
            check(File(context.getExternalFilesDir(null), "runtime-audit-dedicated").isFile) { "Dedicated emulator required" }
            @Suppress("DEPRECATION")
            val sourceUid = context.packageManager.getApplicationInfo("com.x2m7.xrime.test", 0).uid
            check(sourceUid != Process.myUid()) { "Fixture must run as a separate UID" }
            // The dedicated emulator may have an unfinished first-run wizard. Suppress only
            // this process-local prompt during the probe; no persisted setup setting changes.
            previousSetupShown = setupShown.getBoolean(null)
            setupShown.setBoolean(null, true)
            application.registerActivityLifecycleCallbacks(callbacks)
            fun <T> main(block: () -> T): T {
                var value: Result<T>? = null
                runOnMainSync { value = runCatching(block) }
                return checkNotNull(value).getOrThrow()
            }
            fun awaitMain(previousIntent: Intent?): MainActivity {
                val deadline = android.os.SystemClock.uptimeMillis() + 10_000
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    resumed.get()?.let {
                        if (main { (previousIntent == null || it.intent !== previousIntent) && it.intent.action == null }) return it
                    }
                    Thread.sleep(50)
                }
                error("Exported settings activity did not resume")
            }
            for (mode in listOf("missing", "wrong", "other", "broken")) {
                val previousIntent = main { resumed.get()?.intent }
                main {
                    context.startActivity(
                        Intent().setClassName("com.x2m7.xrime.test", "com.osfans.trime.ExternalSettingsRouteFixtureActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("mode", mode),
                    )
                }
                val activity = awaitMain(previousIntent)
                check(main { activity.intent.action == null }) { "Invalid external route was not consumed: $mode" }
                sendStatus(0, Bundle().apply { putString("stream", "Rejected external route fixture: $mode\n") })
            }
            val beforeExternal = main { resumed.get()?.intent }
            main {
                context.startActivity(
                    Intent().setClassName("com.x2m7.xrime.test", "com.osfans.trime.ExternalSettingsRouteFixtureActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("mode", "valid"),
                )
            }
            val externalActivity = awaitMain(beforeExternal)
            val field = MainActivity::class.java.getDeclaredField("navController").apply { isAccessible = true }
            val externalController = field.get(externalActivity) as NavController
            check(main { externalController.currentDestination?.route == NavigationRoute.General.path && externalActivity.intent.action == null }) {
                "Valid external route did not navigate"
            }
            val beforeInternal = main { resumed.get()?.intent }
            main {
                context.startActivity(
                    Intent(context, MainActivity::class.java).setAction(Intent.ACTION_RUN)
                        .putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, NavigationRoute.Clipboard)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            val mainActivity = awaitMain(beforeInternal)
            val controller = field.get(mainActivity) as NavController
            check(main { controller.currentDestination?.route == NavigationRoute.Clipboard.path && mainActivity.intent.action == null }) {
                "Valid internal route did not navigate"
            }
            sendStatus(0, Bundle().apply { putString("stream", "Valid internal route navigated; sourceUid=$sourceUid targetUid=${Process.myUid()}\n") })
            code = Activity.RESULT_OK
            result.putString("stream", "External settings route handling passed\n")
        } catch (t: Throwable) {
            failure = t
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            previousSetupShown?.let { setupShown.setBoolean(null, it) }
        }
        failure?.let { result.putString("stream", android.util.Log.getStackTraceString(it)) }
        finish(code, result)
    }
}
