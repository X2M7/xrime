/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.os.Build
import android.os.Bundle
import android.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ui.main.settings.ColorPickerDialog
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.ui.main.settings.presentColorPicker
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.startActivity
import com.osfans.trime.util.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

class ThemeSettingsFragment : PreferenceDelegateFragment(ThemeManager.prefs) {
    private var colorPickerJob: Job? = null
    private var colorPicker: AlertDialog? = null
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        findPreference<Preference>("selected_theme")?.setOnPreferenceClickListener {
            lifecycleScope.launch { ThemePickerDialog.build(lifecycleScope, requireContext()).show() }
            true
        }
        findPreference<Preference>("normal_mode_color")?.setOnPreferenceClickListener {
            showColorPicker()
            true
        }
    }

    private fun showColorPicker() {
        if (colorPickerJob?.isActive == true || colorPicker?.isShowing == true) return
        val owner = viewLifecycleOwner
        val host = activity ?: return
        val active = {
            isAdded && viewLifecycleOwnerLiveData.value === owner &&
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                activity === host && !host.isFinishing && !host.isDestroyed
        }
        val failed: (Exception) -> Unit = { failure ->
            Timber.e(failure, "Unable to load or apply a color scheme")
            host.toast(R.string.color_scheme_unavailable)
        }
        colorPickerJob = owner.lifecycleScope.launch {
            presentColorPicker(
                create = { ColorPickerDialog.build(owner.lifecycleScope, host, ownerActive = active, reportFailure = failed) },
                ownerActive = active,
                present = { dialog ->
                    dialog.setOnDismissListener { if (colorPicker === dialog) colorPicker = null }
                    try {
                        dialog.show()
                        colorPicker = dialog
                    } catch (failure: Exception) {
                        dialog.dismiss()
                        throw failure
                    }
                },
                reportFailure = failed,
            )
        }
    }

    override fun onDestroyView() {
        colorPickerJob?.cancel()
        colorPickerJob = null
        colorPicker?.dismiss()
        colorPicker = null
        super.onDestroyView()
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(
            R.string.theme_diagnostics,
            R.string.theme_diagnostics_summary,
        ) {
            startActivity<ThemeDiagnosticsActivity>()
        }
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        super.onStop()
    }
}
