/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.TimeZone

class Iso8601LocalDateTimeTest :
    StringSpec({
        "formats UTC with an explicit zero offset" {
            iso8601LocalDateTime(0, TimeZone.getTimeZone("UTC")) shouldBe "1970-01-01T00:00:00+00:00"
        }
        "formats a non-hour offset without losing minutes" {
            iso8601LocalDateTime(0, TimeZone.getTimeZone("GMT+05:45")) shouldBe "1970-01-01T05:45:00+05:45"
        }
        "uses the daylight-saving offset at the instant" {
            val zone = TimeZone.getTimeZone("America/New_York")
            iso8601LocalDateTime(1735689600000, zone) shouldBe "2024-12-31T19:00:00-05:00"
            iso8601LocalDateTime(1751328000000, zone) shouldBe "2025-06-30T20:00:00-04:00"
        }
    })
