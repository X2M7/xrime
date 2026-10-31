/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

/** Tracks search result ownership without storing query text or editor content. Main-thread only. */
internal class ClipboardSearchOwner {
    internal data class Ticket(val editor: Long, val session: Long, val revision: Long)

    private var nextSession = 0L
    private var editor = 0L
    private var session = 0L
    private var revision = 0L

    fun open(editor: Long): Ticket {
        require(editor > 0)
        check(nextSession < Long.MAX_VALUE)
        this.editor = editor
        session = ++nextSession
        revision = 0
        return ticket()
    }

    fun update(): Ticket {
        check(editor > 0 && revision < Long.MAX_VALUE)
        revision++
        return ticket()
    }

    fun accepts(ticket: Ticket, currentEditor: Long, privateMode: Boolean): Boolean =
        !privateMode && editor > 0 && currentEditor == editor && ticket == ticket()

    fun close() {
        editor = 0
        revision = 0
    }

    private fun ticket() = Ticket(editor, session, revision)
}
