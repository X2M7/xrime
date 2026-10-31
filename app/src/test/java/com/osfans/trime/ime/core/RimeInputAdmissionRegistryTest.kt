/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.core

import com.osfans.trime.core.RimeMessage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RimeInputAdmissionRegistryTest : StringSpec({
    "drain keeps input lineage and each subscriber must acknowledge" {
        val registry = RimeInputAdmissionRegistry()
        val service = registry.register()
        val view = registry.register()
        val parent = checkNotNull(registry.accept(null))
        registry.freeze() shouldBe true
        registry.accept(null) shouldBe null
        val child = checkNotNull(registry.accept(parent.epoch))
        registry.finish(parent)
        registry.nextMarker() shouldBe null
        registry.finish(child)
        val marker = checkNotNull(registry.nextMarker())
        val message = RimeMessage.InputBarrierMessage.Data(marker.epoch, marker.sequence)
        registry.acknowledge(service, message) shouldBe RimeInputDrain.Ack.WAIT
        registry.acknowledge(view, message) shouldBe RimeInputDrain.Ack.COMPLETE
        registry.accept(parent.epoch) shouldBe null
    }

    "reset invalidates prior work while preserving consumer registration" {
        val registry = RimeInputAdmissionRegistry()
        val consumer = registry.register()
        val old = checkNotNull(registry.accept(null))
        registry.reset()
        registry.finish(old)
        registry.accept(old.epoch) shouldBe null
        val next = checkNotNull(registry.accept(null))
        registry.finish(next)
        registry.freeze() shouldBe true
        val marker = checkNotNull(registry.nextMarker())
        registry.acknowledge(consumer, RimeMessage.InputBarrierMessage.Data(marker.epoch, marker.sequence)) shouldBe RimeInputDrain.Ack.COMPLETE
    }

    "thaw preserves already accepted child lineage after an aborted drain" {
        val registry = RimeInputAdmissionRegistry()
        val service = registry.register()
        val root = checkNotNull(registry.accept(null))
        registry.freeze() shouldBe true
        val view = registry.register()
        registry.outcome() shouldBe RimeInputDrain.Ack.ABORTED
        val child = checkNotNull(registry.accept(root.epoch))
        registry.thaw() shouldBe true
        registry.finish(root)
        registry.finish(child)
        registry.freeze() shouldBe true
        val marker = checkNotNull(registry.nextMarker())
        val data = RimeMessage.InputBarrierMessage.Data(marker.epoch, marker.sequence)
        registry.acknowledge(service, data) shouldBe RimeInputDrain.Ack.WAIT
        registry.acknowledge(view, data) shouldBe RimeInputDrain.Ack.COMPLETE
    }

    "view replacement aborts a drain and rejects its marker" {
        val registry = RimeInputAdmissionRegistry()
        val service = registry.register()
        val view = registry.register()
        registry.freeze() shouldBe true
        val marker = checkNotNull(registry.nextMarker())
        registry.unregister(view)
        registry.acknowledge(service, RimeMessage.InputBarrierMessage.Data(marker.epoch, marker.sequence)) shouldBe RimeInputDrain.Ack.ABORTED
    }
})
