/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

/** In-memory result ownership. Never persist or log this object. */
internal data class ClipboardSearchResults(
    val query: String,
    val ticket: ClipboardSearchOwner.Ticket,
    val physicalOwner: Long,
    val logicalEditor: Long,
)
