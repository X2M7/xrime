/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ClipboardImageTicketTest : StringSpec({
    "a new editor invalidates background work before the privacy epoch advances" {
        isClipboardImageTicketCurrent(4, 4, 9, 9, 9, 21, 21) shouldBe true
        isClipboardImageTicketCurrent(4, 4, 9, 9, 9, 21, 22) shouldBe false
    }
    "closed pages and privacy changes reject old image actions" {
        isClipboardImageTicketCurrent(4, 5, 9, 9, 9, 21, 21) shouldBe false
        isClipboardImageTicketCurrent(4, 4, 9, null, 9, 21, 21) shouldBe false
        isClipboardImageTicketCurrent(4, 4, 9, 9, null, 21, 21) shouldBe false
        isClipboardImageTicketCurrent(4, 4, 9, 9, 10, 21, 21) shouldBe false
    }
})
