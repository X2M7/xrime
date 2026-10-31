// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.core.data

/** Reject an incomplete listing before it can authorize replacement or deletion. */
internal class SafListingBudget {
    companion object {
        const val MAX_ENTRIES = 10_000
        const val MAX_ID_CHARS = 16_384
        const val MAX_NAME_CHARS = 4_096
        const val MAX_MIME_CHARS = 512
        const val MAX_PATH_CHARS = 32_768
        const val MAX_TOTAL_CHARS = 8L * 1024 * 1024
    }

    private var entries = 0
    private var total = 0L

    fun accept(id: String?, name: String?, mime: String?, parentPath: String = "") {
        check(++entries <= MAX_ENTRIES) { "External listing exceeds its entry budget" }
        val idLength = id?.length ?: 0
        val nameLength = name?.length ?: 0
        val mimeLength = mime?.length ?: 0
        val pathLength = parentPath.length.toLong() + (if (parentPath.isEmpty()) 0 else 1) + nameLength
        check(idLength <= MAX_ID_CHARS && nameLength <= MAX_NAME_CHARS && mimeLength <= MAX_MIME_CHARS) {
            "External listing contains oversized metadata"
        }
        check(pathLength <= MAX_PATH_CHARS) { "External listing contains an oversized path" }
        val cost = idLength.toLong() + nameLength + mimeLength + pathLength
        check(cost <= MAX_TOTAL_CHARS - total) { "External listing exceeds its metadata budget" }
        total += cost
    }
}
