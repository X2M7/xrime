/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PreeditCaretMappingTest : StringSpec({
    "displayed syllable spaces and the caret marker do not advance the raw spelling" {
        val displayed = "zhong guo\u2038"
        preeditCaretToRawOffset(displayed, "zhongguo", 0) shouldBe 0
        preeditCaretToRawOffset(displayed, "zhongguo", 5) shouldBe 5
        preeditCaretToRawOffset(displayed, "zhongguo", 6) shouldBe 5
        preeditCaretToRawOffset(displayed, "zhongguo", 7) shouldBe 6
        preeditCaretToRawOffset(displayed, "zhongguo", 9) shouldBe 8
        preeditCaretToRawOffset(displayed, "zhongguo", 12) shouldBe 8
        preeditCaretToRawOffset(displayed, "zhongguo", 10) shouldBe null
        preeditCaretToRawOffset(displayed, "zhongguo", 13) shouldBe null
    }
    "marker in the middle is ignored at both valid UTF-8 boundaries" {
        preeditCaretToRawOffset("z\u2038hongguo", "zhongguo", 1) shouldBe 1
        preeditCaretToRawOffset("z\u2038hongguo", "zhongguo", 4) shouldBe 1
        preeditCaretToRawOffset("z\u2038hongguo", "zhongguo", 2) shouldBe null
    }
    "unknown transformations and non-ASCII input retain the existing engine path" {
        preeditCaretToRawOffset("nü\u2038", "nv", 1) shouldBe null
        preeditCaretToRawOffset("ab\u2038", "a'b", 1) shouldBe null
        preeditCaretToRawOffset("altered", "original", 1) shouldBe null
        preeditCaretToRawOffset("abc", "abc", -1) shouldBe null
    }
})
