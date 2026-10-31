/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.core

import com.osfans.trime.core.RimeMessage
import java.util.concurrent.atomic.AtomicLong

/** Keeps work and subscriber ownership while a drain epoch is replaced. */
internal class RimeInputAdmissionRegistry {
    internal data class Work(val drain: RimeInputDrain, val epoch: Long)

    companion object { private val nextConsumer = AtomicLong() }
    private var drain = RimeInputDrain()
    private val consumers = mutableMapOf<Long, Long>()
    private var currentMarker: RimeInputDrain.Marker? = null
    private var lastAck = RimeInputDrain.Ack.WAIT

    @Synchronized
    fun register(): Long {
        val consumer = nextConsumer.incrementAndGet().also { check(it > 0) }
        val participant = drain.register()
        // A subscriber created during draining invalidates that drain.
        consumers[consumer] = participant ?: 0L
        return consumer
    }

    @Synchronized
    fun unregister(consumer: Long) {
        consumers.remove(consumer)?.takeIf { it != 0L }?.let(drain::unregister)
    }

    @Synchronized
    fun accept(parent: Long?): Work? {
        val epoch = if (parent == null) drain.root() else parent.takeIf(drain::continuation)
        return epoch?.let { Work(drain, it) }
    }

    fun finish(work: Work) = work.drain.finish(work.epoch)

    @Synchronized
    fun allows(origin: Long?): Boolean = drain.allows(origin)

    @Synchronized
    fun freeze(): Boolean = drain.freeze()

    @Synchronized
    fun nextMarker(): RimeInputDrain.Marker? = drain.nextMarker()?.also {
        currentMarker = it
        lastAck = RimeInputDrain.Ack.WAIT
    }

    @Synchronized
    fun acknowledge(consumer: Long, data: RimeMessage.InputBarrierMessage.Data): RimeInputDrain.Ack {
        val participant = consumers[consumer] ?: return RimeInputDrain.Ack.WAIT
        val marker = currentMarker ?: return RimeInputDrain.Ack.WAIT
        if (marker.epoch != data.epoch || marker.sequence != data.sequence) return RimeInputDrain.Ack.WAIT
        return drain.acknowledge(participant, marker).also {
            if (it != RimeInputDrain.Ack.WAIT) lastAck = it
        }
    }

    @Synchronized
    fun outcome(): RimeInputDrain.Ack = if (drain.isAborted()) RimeInputDrain.Ack.ABORTED else lastAck

    @Synchronized
    fun thaw(): Boolean {
        currentMarker = null
        lastAck = RimeInputDrain.Ack.WAIT
        return drain.thaw()
    }

    @Synchronized
    fun reset() {
        drain.abort()
        val replacement = RimeInputDrain()
        consumers.keys.toList().forEach { consumers[it] = checkNotNull(replacement.register()) }
        drain = replacement
        currentMarker = null
        lastAck = RimeInputDrain.Ack.WAIT
    }
}
