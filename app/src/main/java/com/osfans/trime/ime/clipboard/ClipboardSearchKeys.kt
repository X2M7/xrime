/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

import android.view.KeyEvent

/** Search permits text/navigation keys only and never forwards shortcut modifiers. */
internal fun clipboardSearchKeyAllowed(code: Int, metaState: Int): Boolean {
    val blocked = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_RIGHT_ON or
        KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_RIGHT_ON or
        KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON or KeyEvent.META_META_RIGHT_ON or
        KeyEvent.META_SYM_ON or KeyEvent.META_FUNCTION_ON
    if (metaState and blocked != 0) return false
    return code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z || code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
        code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_EQUALS || code in KeyEvent.KEYCODE_GRAVE..KeyEvent.KEYCODE_SLASH ||
        code in setOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
}
