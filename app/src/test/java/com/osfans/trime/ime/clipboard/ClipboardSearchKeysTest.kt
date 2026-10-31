/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

import android.view.KeyEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ClipboardSearchKeysTest : StringSpec({
    "text and shift are allowed but shortcut modifiers and functional keys are denied" {
        clipboardSearchKeyAllowed(KeyEvent.KEYCODE_A, 0) shouldBe true
        clipboardSearchKeyAllowed(KeyEvent.KEYCODE_9, KeyEvent.META_SHIFT_ON) shouldBe true
        clipboardSearchKeyAllowed(KeyEvent.KEYCODE_DEL, KeyEvent.META_SHIFT_LEFT_ON) shouldBe true
        clipboardSearchKeyAllowed(KeyEvent.KEYCODE_FUNCTION, 0) shouldBe false
        clipboardSearchKeyAllowed(KeyEvent.KEYCODE_VOICE_ASSIST, 0) shouldBe false
        val blocked = listOf(
            KeyEvent.META_CTRL_ON, KeyEvent.META_CTRL_LEFT_ON, KeyEvent.META_CTRL_RIGHT_ON,
            KeyEvent.META_ALT_ON, KeyEvent.META_ALT_LEFT_ON, KeyEvent.META_ALT_RIGHT_ON,
            KeyEvent.META_META_ON, KeyEvent.META_META_LEFT_ON, KeyEvent.META_META_RIGHT_ON,
            KeyEvent.META_SYM_ON, KeyEvent.META_FUNCTION_ON,
        )
        blocked.forEach { modifier ->
            clipboardSearchKeyAllowed(KeyEvent.KEYCODE_A, modifier) shouldBe false
            clipboardSearchKeyAllowed(KeyEvent.KEYCODE_DEL, modifier or KeyEvent.META_SHIFT_ON) shouldBe false
        }
    }
})
