/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KeyboardFontSnapshotTest :
    StringSpec({
        "many key draws share fonts until the interval expires" {
            var time = 10L
            var loads = 0
            val cache = ExpiringKeyboardFontSnapshot(1_000, { time }) { ++loads }
            repeat(100) { cache.get() shouldBe 1 }
            time = 1_009
            cache.get() shouldBe 1
            time = 1_010
            cache.get() shouldBe 2
            repeat(100) { cache.get() shouldBe 2 }
            loads shouldBe 2
        }
        "a backwards clock causes refresh rather than retaining a stale value" {
            var time = 2_000L
            var loads = 0
            val cache = ExpiringKeyboardFontSnapshot(1_000, { time }) { ++loads }
            cache.get() shouldBe 1
            time = 1_999
            cache.get() shouldBe 2
        }
        "failed refresh keeps the old timestamp so a subsequent draw retries" {
            var time = 0L
            var attempts = 0
            val cache = ExpiringKeyboardFontSnapshot(100, { time }) {
                attempts++
                if (attempts == 2) error("unavailable")
                attempts
            }
            cache.get() shouldBe 1
            time = 100
            io.kotest.assertions.throwables.shouldThrow<IllegalStateException> { cache.get() }
            cache.get() shouldBe 3
        }
    })
