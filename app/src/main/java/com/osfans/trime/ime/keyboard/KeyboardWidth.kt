/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

/** Keyboard width is a percentage, like the theme's default key width. */
internal fun resolveKeyboardKeyWidth(keyboardWidth: Float, styleWidth: Float): Float =
    if (keyboardWidth == 0f) styleWidth else keyboardWidth
