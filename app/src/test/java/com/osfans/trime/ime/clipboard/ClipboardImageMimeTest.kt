/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException

class ClipboardImageMimeTest : StringSpec({
    "only a declared supported image type or standard wildcard matches" {
        acceptsClipboardImageMime("image/png") { arrayOf("image/png") } shouldBe true
        acceptsClipboardImageMime("image/jpeg") { arrayOf("image/*") } shouldBe true
        acceptsClipboardImageMime("image/jpeg") { arrayOf("*/*") } shouldBe true
        acceptsClipboardImageMime("image/png") { arrayOf("image/jpeg") } shouldBe false
        acceptsClipboardImageMime("image/gif") { arrayOf("*/*") } shouldBe false
        acceptsClipboardImageMime("image/png") { arrayOf("text/plain", "image/p*") } shouldBe false
    }
    "oversized malformed and throwing editor declarations fail closed" {
        acceptsClipboardImageMime("image/png") { emptyArray() } shouldBe false
        acceptsClipboardImageMime("image/png") { arrayOfNulls<String>(1) } shouldBe false
        acceptsClipboardImageMime("image/png") { Array(33) { "image/png" } } shouldBe false
        acceptsClipboardImageMime("image/png") { arrayOf("x".repeat(129)) } shouldBe false
        acceptsClipboardImageMime("image/png") { throw IllegalArgumentException() } shouldBe false
        shouldThrow<CancellationException> { acceptsClipboardImageMime("image/png") { throw CancellationException() } }
    }
})
