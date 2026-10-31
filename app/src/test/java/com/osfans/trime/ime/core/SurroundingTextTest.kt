/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class SurroundingTextTest :
    StringSpec({
        "short editor text is returned without additional requests" {
            val requests = mutableListOf<Int>()
            readSurroundingText(before = true) { requests.add(it); "hello" } shouldBe "hello"
            requests.shouldContainExactly(1024)
        }

        "a long editor stops at the size limit and keeps the nearest text" {
            val text = "a".repeat(MAX_SURROUNDING_TEXT) + "near"
            val requests = mutableListOf<Int>()
            readSurroundingText(before = true) { requested ->
                requests.add(requested)
                text.takeLast(requested)
            } shouldBe text.takeLast(MAX_SURROUNDING_TEXT)
            requests.shouldContainExactly(1024, 2048, 4096, 8192, 16384)
        }

        "a nonconforming editor cannot exceed the bound or split surrogate pairs" {
            val before = readSurroundingText(before = true) { "😀" + "a".repeat(MAX_SURROUNDING_TEXT - 1) }
            val after = readSurroundingText(before = false) { "a".repeat(MAX_SURROUNDING_TEXT - 1) + "😀" }
            before.length shouldBe MAX_SURROUNDING_TEXT - 1
            after.length shouldBe MAX_SURROUNDING_TEXT - 1
            Character.isLowSurrogate(before.first()) shouldBe false
            Character.isHighSurrogate(after.last()) shouldBe false
        }

        "an unavailable editor returns an empty string" {
            readSurroundingText(before = false) { null } shouldBe ""
        }
    })
