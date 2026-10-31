/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.util

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import java.util.concurrent.CancellationException

class BoundedTextTest :
    StringSpec({
        "accepts the exact code point limit including a surrogate pair across reads" {
            val text = "a".repeat(4095) + "😀" + "b"
            readTextAtMostCodePoints(StringReader(text), 4097, "test") shouldBe text
            shouldThrow<TextReadLimitExceeded> { readTextAtMostCodePoints(StringReader(text), 4096, "test") }
        }
        "ordinary UTF-8 decoding retains replacement behavior for malformed input" {
            val reader = ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)).reader(Charsets.UTF_8)
            readTextAtMostCodePoints(reader, 2, "test") shouldBe "\uFFFD("
        }
        "zero reads may recover but a stuck reader fails instead of spinning" {
            var remaining = 2
            val reader = object : Reader() {
                override fun read(buffer: CharArray, offset: Int, length: Int): Int = if (remaining-- > 0) 0 else -1
                override fun close() = Unit
            }
            readTextAtMostCodePoints(reader, 10, "test") shouldBe ""
            val stuck = object : Reader() {
                override fun read(buffer: CharArray, offset: Int, length: Int) = 0
                override fun close() = Unit
            }
            shouldThrow<IOException> { readTextAtMostCodePoints(stuck, 10, "test") }
        }
        "byte limits check the stream rather than trusting metadata" {
            readBytesAtMost(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3, "test").toList() shouldBe listOf<Byte>(1, 2, 3)
            shouldThrow<TextReadLimitExceeded> { readBytesAtMost(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 3, "test") }
            val stuck = object : InputStream() {
                override fun read() = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
            }
            shouldThrow<IOException> { readBytesAtMost(stuck, 3, "test") }
        }
        "cancellation from a reader propagates unchanged" {
            val cancellation = CancellationException("cancelled")
            val reader = object : Reader() {
                override fun read(buffer: CharArray, offset: Int, length: Int): Int = throw cancellation
                override fun close() = Unit
            }
            shouldThrow<CancellationException> { readTextAtMostCodePoints(reader, 10, "test") } shouldBe cancellation
        }
    })
