// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.dialog

import android.app.AlertDialog
import android.content.Context
import android.view.KeyEvent
import androidx.lifecycle.LifecycleCoroutineScope
import com.osfans.trime.R
import com.osfans.trime.core.KeyValue
import com.osfans.trime.core.RimeApi
import com.osfans.trime.data.prefs.InputLayout
import com.osfans.trime.data.prefs.InputSetupController
import com.osfans.trime.data.prefs.InputSetupException
import com.osfans.trime.data.prefs.InputSetupFailure
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch
import splitties.systemservices.inputMethodManager

object EnabledSchemaPickerDialog {
    suspend fun build(
        rime: RimeApi,
        scope: LifecycleCoroutineScope,
        context: Context,
        extensions: (AlertDialog.Builder.() -> AlertDialog.Builder)? = null,
    ): AlertDialog {
        val selectedSchemaId = rime.selectedSchemaId()
        val all = rime.selectedSchemata().toList()
        val hasBothFullPinyin = all.any { it.id == "luna_pinyin" } && all.any { it.id == "luna_pinyin_simp" }
        val selecteds = if (hasBothFullPinyin) {
            val keep = if (selectedSchemaId == "luna_pinyin_simp") "luna_pinyin_simp" else "luna_pinyin"
            all.filter { it.id !in setOf("luna_pinyin", "luna_pinyin_simp") || it.id == keep }
        } else {
            all
        }
        val selectedNames = selecteds.map {
            if (it.id == "luna_pinyin" || it.id == "luna_pinyin_simp") context.getString(R.string.quick_settings_full_pinyin) else it.name
        }
        val selectedIds = selecteds.map { it.id }
        val selectedIndex = selecteds.indexOfFirst { it.id == selectedSchemaId }
        return AlertDialog
            .Builder(context)
            .apply {
                setTitle(R.string.select_current_schema)
                if (selecteds.isEmpty()) {
                    setMessage(R.string.no_schema_to_select)
                } else {
                    setSingleChoiceItems(
                        selectedNames.toTypedArray(),
                        selectedIndex,
                    ) { dialog, which ->
                        scope.launch {
                            // F4 keeps a temporary engine active until the switcher is dismissed.
                            if (rime.isEmpty()) {
                                rime.processKey(KeyValue.fromKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)).value)
                            }
                            val id = selectedIds[which]
                            val layout = when (id) {
                                "luna_pinyin", "luna_pinyin_simp" -> InputLayout.FULL_PINYIN
                                "luna_pinyin_t9" -> InputLayout.T9
                                else -> null
                            }
                            val selection = if (layout != null) InputSetupController.inspect(rime).selection else null
                            if (layout != null && selection != null) {
                                try {
                                    InputSetupController.apply(rime, selection.copy(layout = layout))
                                } catch (failure: InputSetupException) {
                                    context.toast(if (failure.reason == InputSetupFailure.PENDING_INPUT) R.string.quick_settings_pending_input else R.string.quick_settings_apply_failed)
                                }
                            } else {
                                rime.selectSchema(id)
                            }
                            dialog.dismiss()
                        }
                    }
                }
                setNeutralButton(R.string.other_ime) { _, _ ->
                    inputMethodManager.showInputMethodPicker()
                }
                extensions?.invoke(this)
            }.create()
    }
}
