// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.core.data

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

class SyncFingerprintTest :
    StringSpec({
        "fingerprints use standard SHA-256 byte encoding" {
            SyncFingerprint.hex(SyncFingerprint.digest("abc".byteInputStream())) shouldBe
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            SyncFingerprint.hex(SyncFingerprint.digest("".byteInputStream())) shouldBe
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        }
        "read-back enforces the actual byte limit including empty content" {
            SyncFingerprint.digest("abc".byteInputStream(), 3).toList() shouldBe
                SyncFingerprint.digest("abc".byteInputStream()).toList()
            SyncFingerprint.digest("".byteInputStream(), 0).toList() shouldBe
                SyncFingerprint.digest("".byteInputStream()).toList()
            shouldThrow<IOException> { SyncFingerprint.digest("abcd".byteInputStream(), 3) }
            shouldThrow<IOException> { SyncFingerprint.digest("a".byteInputStream(), 0) }
            shouldThrow<IllegalArgumentException> { SyncFingerprint.digest("".byteInputStream(), -1) }
        }
        "transient zero reads recover and persistent zero reads fail" {
            fun stream(empty: Int) = object : InputStream() {
                var remaining = empty
                val delegate = "abc".byteInputStream()
                override fun read(): Int = delegate.read()
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    if (remaining-- > 0) 0 else delegate.read(bytes, offset, length)
            }
            SyncFingerprint.digest(stream(16)).toList() shouldBe
                SyncFingerprint.digest("abc".byteInputStream()).toList()
            shouldThrow<IOException> { SyncFingerprint.digest(stream(17)) }
        }
        "zero-read retries propagate cancellation" {
            val input = object : InputStream() {
                override fun read(): Int = error("single-byte read must not be used")
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
            }
            var checks = 0
            shouldThrow<CancellationException> {
                SyncFingerprint.digest(input) {
                    if (++checks == 3) throw CancellationException("cancelled")
                }
            }
            checks shouldBe 3
        }
        "legacy indexes have no content-backed deletion authority" {
            Json.decodeFromString<SyncEntry>("""{"size":3,"lastModified":123}""") shouldBe SyncEntry(3, 123)
        }
        "content fingerprints survive index serialization" {
            val entry = SyncEntry(3, 123, SyncFingerprint.hex(SyncFingerprint.digest("abc".byteInputStream())))
            Json.decodeFromString<SyncEntry>(Json.encodeToString(entry)) shouldBe entry
        }
    })
