/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import com.osfans.trime.util.matchesAny
import com.osfans.trime.util.removeRegexSet

/** Compiled once per preference value; invalid patterns reject capture until corrected. */
internal class ClipboardRulesCache {
    private var source: Pair<String, String>? = null
    private var compiled: ClipboardRules? = null

    fun get(compare: String, output: String): ClipboardRules? {
        val current = compare to output
        if (source == current) return compiled
        compiled = try {
            ClipboardRules(
                compare.split('\n').map { Regex(it.trim()) }.toSet(),
                output.split('\n').map { Regex(it) }.toSet(),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
        source = current
        return compiled
    }
}

internal class ClipboardRules(private val compare: Set<Regex>, private val output: Set<Regex>) {
    fun accepts(text: String): Boolean =
        text.isNotBlank() && !text.matchesAny(output) && text.removeRegexSet(compare).isNotEmpty()
}
