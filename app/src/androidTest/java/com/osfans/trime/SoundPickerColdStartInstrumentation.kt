/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.soundeffect.SoundEffectManager
import com.osfans.trime.ui.main.MainActivity
import com.osfans.trime.ui.main.settings.SoundEffectPickerDialog
import com.osfans.trime.ui.setup.SetupActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Field
import java.util.UUID

/** Cold settings process, saved preference and real dialog; restricted to dedicated emulators. */
class SoundPickerColdStartInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        var failure: Throwable? = null
        var host: MainActivity? = null
        var dialog: AlertDialog? = null
        var fixture: File? = null
        var oldSelection: String? = null
        var oldSoundEnabled: Boolean? = null
        var setupShownField: Field? = null
        var oldSetupShown: Boolean? = null
        val result = Bundle()
        fun <T> main(block: () -> T): T {
            var value: Result<T>? = null
            runOnMainSync { value = runCatching(block) }
            return checkNotNull(value).getOrThrow()
        }
        fun cleanup(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
        }
        try {
            // Instrumentation.onStart can race Application.onCreate on the main thread.
            // Wait for production initialization; do not initialize preferences in the test.
            waitForIdleSync()
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Requires a dedicated emulator" }
            check(File(targetContext.getExternalFilesDir(null), "runtime-audit-dedicated").isFile) { "Dedicated test installation required" }
            val selectedIme = Settings.Secure.getString(targetContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
            check(!selectedIme.startsWith(targetContext.packageName + "/")) { "A different IME must be selected for the cold probe" }
            check(SoundEffectManager.activeSoundEffect == null) { "Sound effect state was already initialized" }
            val prefs = AppPrefs.defaultInstance().keyboard
            oldSelection = prefs.customSoundEffect.getValue()
            oldSoundEnabled = prefs.soundOnKeyPress.getValue()
            val id = "runtime-sound-picker-${UUID.randomUUID()}"
            val directory = File(DataManager.userDataDir, "soundeffect")
            check(directory.isDirectory || directory.mkdirs()) { "Sound fixture directory unavailable" }
            val target = File(directory, "$id.sound.yaml")
            fixture = target
            check(target.createNewFile()) { "Sound fixture already exists" }
            target.writeText("name: $id\nfolder: .\nsound: []\nkeyset: []\n")
            main { prefs.soundOnKeyPress.setValue(false); prefs.customSoundEffect.setValue(id) }
            // Selecting another IME makes real setup incomplete. Simulate the
            // already-shown state only inside this test process, with no persistent changes.
            val shownField = SetupActivity::class.java.getDeclaredField("shown").apply { isAccessible = true }
            setupShownField = shownField
            oldSetupShown = main { shownField.getBoolean(null) }
            main { shownField.setBoolean(null, true) }
            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            try {
                targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                host = checkNotNull(waitForMonitorWithTimeout(monitor, 30_000)) as MainActivity
            } finally { removeMonitor(monitor) }
            val activity = checkNotNull(host)
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < deadline && !main { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }) SystemClock.sleep(50)
            check(main { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !activity.isFinishing }) { "Settings host did not resume" }
            check(SoundEffectManager.activeSoundEffect == null) { "Activity unexpectedly initialized a sound selection" }
            dialog = runBlocking {
                withContext(Dispatchers.Main) {
                    SoundEffectPickerDialog.build(activity.lifecycleScope, activity).also { it.show() }
                }
            }
            main {
                val list = checkNotNull(dialog).listView
                val checked = list.checkedItemPosition
                check(checked >= 0 && list.adapter.getItem(checked).toString() == id) { "Saved sound selection was not checked on cold launch" }
            }
            check(SoundEffectManager.activeSoundEffect == null) { "Opening the picker unexpectedly initialized playback" }
            result.putString("stream", "PASS: cold settings host with simulated completed onboarding leaves playback uninitialized; real picker checks saved sound selection\n")
        } catch (error: Throwable) {
            failure = error
        } finally {
            cleanup { main { dialog?.dismiss(); host?.finish() } }
            cleanup { main { oldSetupShown?.let { setupShownField?.setBoolean(null, it) } } }
            cleanup { main {
                val prefs = AppPrefs.defaultInstance().keyboard
                oldSelection?.let { prefs.customSoundEffect.setValue(it) }
                oldSoundEnabled?.let { prefs.soundOnKeyPress.setValue(it) }
                check(prefs.customSoundEffect.sharedPreferences.edit().commit())
            } }
            cleanup { fixture?.let { check(!it.exists() || it.delete()) { "Could not remove dedicated fixture" } } }
            val error = failure
            result.putBoolean("passed", error == null)
            if (error != null) result.putString("error", error.stackTraceToString())
            finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }
}
