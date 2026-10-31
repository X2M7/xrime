/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.core

import android.view.KeyEvent

/** Release builds do not observe or retain hardware events. */
internal object ClipboardShortcutDiagnostics {
    fun record(@Suppress("UNUSED_PARAMETER") event: KeyEvent) = Unit
}
