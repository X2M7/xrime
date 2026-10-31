/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

/**
 * Numeric ownership only; no query or clipboard content. Confined to the service main thread.
 * Logical tokens must come from the same process-wide allocator as normal Rime editor tokens.
 */
internal class ClipboardSearchSession {
    enum class Phase { INACTIVE, ENTERING, EDITING, RESTORING, RESULTS }
    enum class Route { REJECT, LOCAL, EXTERNAL }

    private val owner = ClipboardSearchOwner()
    @Volatile var phase = Phase.INACTIVE
        private set
    private var editor = 0L
    private var logical = 0L
    private var ticket: ClipboardSearchOwner.Ticket? = null
    private var showResults = false

    fun begin(physicalEditor: Long, searchLogical: Long, privateMode: Boolean, composing: Boolean): Boolean {
        if (phase != Phase.INACTIVE || physicalEditor <= 0 || searchLogical <= 0 || privateMode || composing) return false
        editor = physicalEditor
        logical = searchLogical
        ticket = owner.open(editor)
        phase = Phase.ENTERING
        return true
    }

    fun ready(physicalEditor: Long, searchLogical: Long, privateMode: Boolean) {
        if (phase == Phase.ENTERING && !privateMode && editor == physicalEditor && logical == searchLogical) phase = Phase.EDITING
    }

    fun changed(): ClipboardSearchOwner.Ticket? {
        if (phase != Phase.EDITING) return null
        return owner.update().also { ticket = it }
    }

    fun restore(physicalEditor: Long, newLogical: Long, privateMode: Boolean, results: Boolean): Boolean {
        if ((phase != Phase.EDITING && phase != Phase.ENTERING) || editor != physicalEditor || newLogical <= 0 || logical == newLogical || privateMode) return false
        logical = newLogical
        showResults = results && phase == Phase.EDITING
        phase = Phase.RESTORING
        return true
    }

    fun restored(physicalEditor: Long, restoredLogical: Long, privateMode: Boolean) {
        if (phase != Phase.RESTORING || privateMode || editor != physicalEditor || logical != restoredLogical) return
        if (showResults) phase = Phase.RESULTS else close()
    }

    fun acceptsResult(candidate: ClipboardSearchOwner.Ticket, physicalEditor: Long, privateMode: Boolean): Boolean =
        phase == Phase.RESULTS && owner.accepts(candidate, physicalEditor, privateMode) && candidate == ticket

    fun consume(candidate: ClipboardSearchOwner.Ticket, physicalEditor: Long, privateMode: Boolean, rowExists: Boolean): Boolean {
        if (!rowExists || !acceptsResult(candidate, physicalEditor, privateMode)) return false
        close()
        return true
    }

    fun route(physicalEditor: Long, messageLogical: Long, privateMode: Boolean): Route {
        if (phase == Phase.INACTIVE) return Route.EXTERNAL // caller still checks the normal editor generation
        if (privateMode || editor != physicalEditor || logical != messageLogical) return Route.REJECT
        return when (phase) {
            Phase.EDITING -> Route.LOCAL
            Phase.RESULTS -> Route.EXTERNAL
            else -> Route.REJECT
        }
    }

    fun close() {
        owner.close()
        phase = Phase.INACTIVE
        editor = 0
        logical = 0
        ticket = null
        showResults = false
    }
}
