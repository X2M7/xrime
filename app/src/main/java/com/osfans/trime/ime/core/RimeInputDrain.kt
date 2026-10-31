/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.core

import java.util.concurrent.atomic.AtomicLong

/** Tracks a frozen input epoch until queued work and every registered message consumer drain. */
internal class RimeInputDrain {
    internal data class Marker(val epoch: Long, val sequence: Long, val submitted: Long)
    internal enum class Ack { WAIT, RETRY, COMPLETE, ABORTED }
    private enum class State { OPEN, DRAINING, SEALED, ABORTED }

    companion object {
        private val nextEpoch = AtomicLong()
        private val nextParticipant = AtomicLong()
        private fun unique(counter: AtomicLong): Long = counter.incrementAndGet().also { check(it > 0) }
    }

    val epoch = unique(nextEpoch)
    private val participants = mutableSetOf<Long>()
    private val awaiting = mutableSetOf<Long>()
    private var state = State.OPEN
    private var pending = 0
    private var submitted = 0L
    private var sequence = 0L
    private var current: Marker? = null

    @Synchronized
    fun register(): Long? {
        if (state == State.SEALED) return null
        if (state == State.DRAINING) abort()
        return unique(nextParticipant).also { participants.add(it) }
    }

    @Synchronized
    fun unregister(participant: Long) {
        if (participants.remove(participant) && state == State.DRAINING) abort()
    }

    @Synchronized
    fun root(): Long? {
        if (state != State.OPEN) return null
        addWork()
        return epoch
    }

    @Synchronized
    fun continuation(origin: Long): Boolean {
        if (origin != epoch || state == State.SEALED) return false
        addWork()
        return true
    }

    private fun addWork() {
        check(pending < Int.MAX_VALUE && submitted < Long.MAX_VALUE)
        pending++
        submitted++
    }

    @Synchronized
    fun finish(origin: Long) {
        check(origin == epoch && pending > 0)
        pending--
    }

    @Synchronized
    fun freeze(): Boolean {
        if (state != State.OPEN || participants.isEmpty()) return false
        state = State.DRAINING
        return true
    }

    @Synchronized
    fun nextMarker(): Marker? {
        if (state != State.DRAINING || pending != 0 || current != null) return null
        check(sequence < Long.MAX_VALUE)
        return Marker(epoch, ++sequence, submitted).also {
            current = it
            awaiting.clear()
            awaiting.addAll(participants)
        }
    }

    @Synchronized
    fun acknowledge(participant: Long, marker: Marker): Ack {
        if (state == State.ABORTED) return Ack.ABORTED
        if (state != State.DRAINING || marker != current || !awaiting.remove(participant)) return Ack.WAIT
        if (awaiting.isNotEmpty()) return Ack.WAIT
        current = null
        if (pending != 0 || submitted != marker.submitted) return Ack.RETRY
        state = State.SEALED
        return Ack.COMPLETE
    }

    @Synchronized
    fun allows(origin: Long?): Boolean = origin == epoch && state != State.SEALED

    @Synchronized
    fun isAborted(): Boolean = state == State.ABORTED

    /** Resume the same epoch after an unsuccessful drain; existing child work remains valid. */
    @Synchronized
    fun thaw(): Boolean {
        if (state == State.SEALED) return false
        state = State.OPEN
        current = null
        awaiting.clear()
        return true
    }

    @Synchronized
    fun abort() {
        if (state == State.SEALED) return
        state = State.ABORTED
        current = null
        awaiting.clear()
    }
}
