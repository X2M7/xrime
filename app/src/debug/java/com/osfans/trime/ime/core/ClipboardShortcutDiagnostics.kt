/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.core

import android.view.KeyEvent

/** Test-only in-memory observation of shortcut keys; never records editor content. */
internal object ClipboardShortcutDiagnostics {
    var observer: ((Int, Int, Int) -> Unit)? = null
    fun record(event: KeyEvent) {
        if (event.keyCode in setOf(KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT)
        ) observer?.invoke(event.keyCode, event.action, event.metaState)
    }
}
