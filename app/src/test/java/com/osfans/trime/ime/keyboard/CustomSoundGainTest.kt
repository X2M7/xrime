/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CustomSoundGainTest : StringSpec({
    "system-default volume permits system-controlled playback and explicit values are bounded" {
        customSoundGain(0) shouldBe 1f
        customSoundGain(-1) shouldBe 0f
        customSoundGain(Int.MIN_VALUE) shouldBe 0f
        customSoundGain(10) shouldBe 0.1f
        customSoundGain(100) shouldBe 1f
        customSoundGain(101) shouldBe 1f
    }

})
