/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

/** A stale page cannot authorize background work even before privacy catches up. */
internal fun isClipboardImageTicketCurrent(
    ticket: Long,
    currentGeneration: Long,
    epoch: Long,
    currentPageEpoch: Long?,
    currentPrivacyEpoch: Long?,
    owner: Long,
    currentEditor: Long,
): Boolean = ticket == currentGeneration && currentPageEpoch == epoch && currentPrivacyEpoch == epoch && owner == currentEditor
