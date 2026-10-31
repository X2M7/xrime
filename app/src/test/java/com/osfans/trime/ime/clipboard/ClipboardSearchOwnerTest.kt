/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ClipboardSearchOwnerTest : StringSpec({
    "only the current query and editor can consume search results" {
        val owner = ClipboardSearchOwner()
        val initial = owner.open(7)
        val previous = owner.update()
        val current = owner.update()
        owner.accepts(initial, 7, false) shouldBe false
        owner.accepts(previous, 7, false) shouldBe false
        owner.accepts(current, 7, false) shouldBe true
        owner.accepts(current, 8, false) shouldBe false
        owner.accepts(current, 7, true) shouldBe false
        owner.close()
        owner.accepts(current, 7, false) shouldBe false
        val reopened = owner.open(7)
        owner.accepts(current, 7, false) shouldBe false
        owner.accepts(reopened, 7, false) shouldBe true
    }
})
