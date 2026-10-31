/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.core

/** Maximum UTF-16 units read from an editor on either side of the cursor. */
internal const val MAX_SURROUNDING_TEXT = 16 * 1024

/** Bounds both the number of IPC requests and the returned text, even for a nonconforming editor. */
internal fun readSurroundingText(before: Boolean, read: (Int) -> CharSequence?): String {
    var requested = 1024
    while (true) {
        val text = read(requested) ?: return ""
        if (text.length < requested || text.length >= MAX_SURROUNDING_TEXT || requested == MAX_SURROUNDING_TEXT) {
            if (text.length <= MAX_SURROUNDING_TEXT) return text.toString()
            if (before) {
                var start = text.length - MAX_SURROUNDING_TEXT
                if (Character.isLowSurrogate(text[start]) && Character.isHighSurrogate(text[start - 1])) start++
                return text.subSequence(start, text.length).toString()
            }
            var end = MAX_SURROUNDING_TEXT
            if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            return text.subSequence(0, end).toString()
        }
        requested = minOf(requested * 2, MAX_SURROUNDING_TEXT)
    }
}
