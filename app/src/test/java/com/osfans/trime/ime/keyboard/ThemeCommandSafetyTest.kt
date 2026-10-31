/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.net.URISyntaxException

class ThemeCommandSafetyTest :
    StringSpec({
        "valid positional formatting retains the existing values" {
            expandThemeCommand("%2\$s/%1\$s") { "text$it" } shouldBe "text2/text1"
        }
        "malformed formatting cannot crash the input method" {
            expandThemeCommand("%1\$s/%?") { "text$it" }.shouldBeNull()
        }
        "non-placeholder strings are not expanded" {
            expandThemeCommand("https://example.test/%20") { error("unexpected text read") } shouldBe "https://example.test/%20"
        }
        "missing or invalid external targets fail without launching" {
            var launched = false
            launchThemeCommand<String>({ null }) { launched = true } shouldBe false
            launchThemeCommand<String>({ throw URISyntaxException("bad", "bad") }) { launched = true } shouldBe false
            launched shouldBe false
        }
        "a denied launch fails and a valid launch proceeds" {
            launchThemeCommand({ "target" }) { throw SecurityException() } shouldBe false
            var launched = ""
            launchThemeCommand({ "target" }) { launched = it } shouldBe true
            launched shouldBe "target"
        }
    })
