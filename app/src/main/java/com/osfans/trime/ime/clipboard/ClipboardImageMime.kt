/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

import kotlinx.coroutines.CancellationException

/** Editor-provided MIME declarations are untrusted and must remain bounded. */
internal fun acceptsClipboardImageMime(actual: String, read: () -> Array<out String?>?): Boolean {
    if (actual != "image/png" && actual != "image/jpeg") return false
    return try {
        val filters = read() ?: return false
        if (filters.isEmpty() || filters.size > 32 || filters.any { it == null || it.length !in 1..128 }) return false
        filters.any { it == actual || it == "image/*" || it == "*/*" }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
