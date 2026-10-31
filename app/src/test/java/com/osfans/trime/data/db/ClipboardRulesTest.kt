/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ClipboardRulesTest :
    StringSpec({
        "compare expressions remove matches before deciding if text is empty" {
            val rules = checkNotNull(ClipboardRulesCache().get("[0-9]", ""))
            rules.accepts("123") shouldBe false
            rules.accepts("abc123") shouldBe true
        }
        "output expressions match the whole clipboard text" {
            val rules = checkNotNull(ClipboardRulesCache().get("", "secret.*"))
            rules.accepts("secret text") shouldBe false
            rules.accepts("not secret text") shouldBe true
        }
        "changed rules take effect without restarting and invalid rules reject capture" {
            val cache = ClipboardRulesCache()
            checkNotNull(cache.get("", "")).accepts("secret") shouldBe true
            checkNotNull(cache.get("", "secret")).accepts("secret") shouldBe false
            cache.get("", "[") shouldBe null
            checkNotNull(cache.get("", "")).accepts("secret") shouldBe true
        }
    })
