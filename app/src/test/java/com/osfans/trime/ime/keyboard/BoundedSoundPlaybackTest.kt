/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec

class BoundedSoundPlaybackTest : StringSpec({
    "sample coordinator enforces bounded scheduling, revocation and failure recovery" {
        SoundDemandCoordinatorHarness.main(emptyArray())
    }
    "configuration loader coalesces requests and rejects stale completions" {
        LatestSoundConfigLoaderHarness.main(emptyArray())
    }
})
