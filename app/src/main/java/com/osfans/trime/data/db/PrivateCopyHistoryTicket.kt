/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import com.osfans.trime.ime.clipboard.ClipboardImageDispatchGate

/** One listening interval and one explicit copy; closing listening cancels pending claims. */
internal class PrivateCopyHistoryTicket {
    private val read = ClipboardImageDispatchGate()
    private val write = ClipboardImageDispatchGate()
    fun claimRead() = read.tryClaim()
    fun claimWrite() = write.tryClaim()
    fun cancel() {
        read.cancelBeforeClaim()
        write.cancelBeforeClaim()
    }
}
