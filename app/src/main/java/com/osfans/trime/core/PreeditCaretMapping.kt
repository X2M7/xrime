/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core

/**
 * Map an unambiguous decorated ASCII spelling to the underlying Rime input.
 * Unknown transliterations and non-ASCII input keep the engine's existing behavior.
 */
internal fun preeditCaretToRawOffset(preedit: String, raw: String, displayedByteOffset: Int): Int? {
    if (raw.isEmpty() || raw.length > 4096 || preedit.length > 8192 || displayedByteOffset < 0 ||
        raw.any { it !in 'a'..'z' && it !in 'A'..'Z' }
    ) return null
    if (preedit.any { it != ' ' && it != '\u2038' && it !in 'a'..'z' && it !in 'A'..'Z' }) return null
    if (preedit.filter { it != ' ' && it != '\u2038' } != raw) return null

    var displayBytes = 0
    var rawBytes = 0
    var mapped: Int? = if (displayedByteOffset == 0) 0 else null
    for (character in preedit) {
        displayBytes += if (character == '\u2038') 3 else 1
        if (character != ' ' && character != '\u2038') rawBytes++
        if (displayBytes == displayedByteOffset) mapped = rawBytes
    }
    return mapped
}
