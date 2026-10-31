/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KeyboardWidthTest :
    StringSpec({
        "omitted keyboard width inherits the style percentage" {
            resolveKeyboardKeyWidth(0f, 10f) shouldBe 10f
        }
        "explicit keyboard width takes precedence over style" {
            resolveKeyboardKeyWidth(12.5f, 10f) shouldBe 12.5f
        }
        "zero style width retains the existing unset behavior" {
            resolveKeyboardKeyWidth(0f, 0f) shouldBe 0f
        }
    })
