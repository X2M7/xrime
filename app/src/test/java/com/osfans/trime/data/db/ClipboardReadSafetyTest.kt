/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CancellationException

class ClipboardReadSafetyTest : StringSpec({
    "malformed clipboard data is rejected without swallowing cancellation or fatal errors" {
        ClipboardReadSafety.readOrNull<Int> { throw IllegalArgumentException("malformed fixture") } shouldBe null
        val cancelled = CancellationException("fixture cancellation")
        shouldThrow<CancellationException> { ClipboardReadSafety.readOrNull<Int> { throw cancelled } } shouldBe cancelled
        val expired = PersistencePermitExpiredException()
        shouldThrow<PersistencePermitExpiredException> { ClipboardReadSafety.readOrNull<Int> { throw expired } } shouldBe expired
        val fatal = AssertionError("fixture fatal")
        shouldThrow<AssertionError> { ClipboardReadSafety.readOrNull<Int> { throw fatal } } shouldBe fatal
    }
})
