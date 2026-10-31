/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PrivateCopyHistoryTicketTest : StringSpec({
    "closing history invalidates both unclaimed operations permanently" {
        val old = PrivateCopyHistoryTicket()
        old.cancel()
        old.claimRead() shouldBe false
        old.claimWrite() shouldBe false
        val reopened = PrivateCopyHistoryTicket()
        reopened.claimRead() shouldBe true
        reopened.claimWrite() shouldBe true
        old.claimWrite() shouldBe false
    }
    "a selection read already started cannot write after history is closed" {
        val ticket = PrivateCopyHistoryTicket()
        ticket.claimRead() shouldBe true
        ticket.cancel()
        ticket.claimWrite() shouldBe false
    }
    "a write claimed before closing may complete once" {
        val ticket = PrivateCopyHistoryTicket()
        ticket.claimWrite() shouldBe true
        ticket.cancel()
        ticket.claimWrite() shouldBe false
    }
})
