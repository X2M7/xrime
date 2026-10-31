/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.util.concurrent.CancellationException

class CleanupFailureTest : StringSpec({
    "cleanup failures are suppressed without replacing cancellation or the primary error" {
        for (primary in listOf(CancellationException("cancelled"), IOException("primary"))) {
            val cleanup = IOException("cleanup")
            closePreservingFailure(primary) { throw cleanup }
            primary.suppressed.toList() shouldBe listOf(cleanup)
        }
    }
    "cleanup failures propagate when there is no primary error" {
        val cleanup = IOException("cleanup")
        shouldThrow<IOException> { closePreservingFailure(null) { throw cleanup } } shouldBe cleanup
    }
})
