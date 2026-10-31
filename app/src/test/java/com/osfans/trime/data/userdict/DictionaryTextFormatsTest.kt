// SPDX-FileCopyrightText: 2015 - 2026 Rime community
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.userdict

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

private fun withText(text: String, block: (File) -> Unit) {
    val directory = createTempDirectory().toFile()
    try {
        val file = File(directory, "input.txt").apply { writeText(text) }
        block(file)
    } finally {
        directory.deleteRecursively()
    }
}

private fun snapshot(name: String = "luna_pinyin", value: String = "c=3 d=0.3 t=5") = "# Rime user dictionary\n#@/db_name\t$name\n#@/db_type\tuserdb\n#@/tick\t5\nxu gou\t虚构词组\t$value\n"

class DictionaryTextFormatsTest :
    StringSpec({
        "Rime export metadata and phrase-code-weight rows count only actual entries" {
            withText("# Rime user dictionary export\n#@/db_name\tluna_pinyin\n虚构短语\txu gou duan yu\t3\n\n另一虚构短语\t5464\n") {
                DictionaryTextFormats.validateTable(it) shouldBe 2
            }
        }

        "invalid table rows are rejected before native can silently skip them" {
            listOf("bad\n", "短语\t   \t1\n", "短语\tcode\tnot-a-number\n", "短语\tcode\t2147483648\n", "短语\tcode\t1\textra\n").forEach { text ->
                withText(text) { shouldThrow<IOException> { DictionaryTextFormats.validateTable(it) } }
            }
        }

        "no comment sentinel follows the native TSV convention" {
            withText("# no comment\n#虚构短语\txu gou\t1\n") {
                DictionaryTextFormats.validateTable(it) shouldBe 1
            }
        }

        "snapshot identities accept only safe names and known Rime database suffixes" {
            listOf("luna_pinyin", "luna_pinyin.userdb", "luna_pinyin.userdb.txt", "luna_pinyin.userdb.kct", "luna_pinyin.userdb.ldb").forEach { name ->
                withText(snapshot(name)) { DictionaryTextFormats.snapshotDictionaryName(it) shouldBe "luna_pinyin" }
            }
        }

        "snapshot metadata cannot choose a path outside the user directory" {
            listOf("../outside", "/outside", "..", ".trime-restore", "a\\outside", "a/b.userdb", " name ").forEach { name ->
                withText(snapshot(name)) {
                    shouldThrow<IllegalArgumentException> { DictionaryTextFormats.snapshotDictionaryName(it) }
                }
            }
            withText(snapshot("luna.userdb.evil")) {
                shouldThrow<IOException> { DictionaryTextFormats.snapshotDictionaryName(it) }
            }
        }

        "duplicate and missing identities or wrong database type are rejected" {
            listOf(
                snapshot() + "#@/db_name\tother\n",
                snapshot() + "#@/db_type\tuserdb\n",
                snapshot().replace("#@/db_name\tluna_pinyin\n", ""),
                snapshot().replace("#@/db_type\tuserdb", "#@/db_type\ttabledb"),
            ).forEach { text ->
                withText(text) { shouldThrow<IOException> { DictionaryTextFormats.snapshotDictionaryName(it) } }
            }
        }

        "malformed learning values and tick metadata are rejected before merging" {
            listOf("c=oops d=0.1 t=2", "c=1 d=NaN t=2", "c=1 d=1 t=-1", "unknown=1").forEach { value ->
                withText(snapshot(value = value)) { shouldThrow<IOException> { DictionaryTextFormats.snapshotDictionaryName(it) } }
            }
            withText(snapshot().replace("#@/tick\t5", "#@/tick\tnot-a-tick")) {
                shouldThrow<IOException> { DictionaryTextFormats.snapshotDictionaryName(it) }
            }
        }

        "bounded staging rejects oversized streams before writing beyond the limit" {
            withText("keep") { file ->
                shouldThrow<IOException> { DictionaryTextFormats.copyBounded("123456789".byteInputStream(), file, 4) }
                file.length() shouldBe 0
            }
        }

        "staging tolerates temporary zero reads but rejects an unresponsive provider" {
            val intermittent = object : java.io.InputStream() {
                var reads = 0
                override fun read(): Int = error("Bulk reads expected")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = when (reads++) {
                    0, 1 -> 0
                    2 -> { buffer[offset] = 'x'.code.toByte(); 1 }
                    else -> -1
                }
            }
            DictionaryTextFormats.readBounded(intermittent, 1).toList() shouldBe listOf('x'.code.toByte())
            val stuck = object : java.io.InputStream() {
                override fun read() = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
            }
            shouldThrow<IOException> { DictionaryTextFormats.readBounded(stuck, 16) }
            val cancelled = java.util.concurrent.CancellationException("provider cancelled")
            val cancelledStream = object : java.io.InputStream() {
                override fun read(): Int = throw cancelled
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw cancelled
            }
            shouldThrow<java.util.concurrent.CancellationException> { DictionaryTextFormats.readBounded(cancelledStream, 16) } shouldBe cancelled
            withText("") { file ->
                var checks = 0
                shouldThrow<java.util.concurrent.CancellationException> {
                    DictionaryTextFormats.copyBounded("value".byteInputStream(), file, 16) {
                        if (++checks == 2) throw cancelled
                    }
                } shouldBe cancelled
                file.length() shouldBe 0
            }
        }

        "phrase snapshot validates exactly the bounded bytes it returns" {
            val bytes = "entry\tcode\t1\n".toByteArray()
            val snapshot = DictionaryTextFormats.readBounded(bytes.inputStream(), bytes.size.toLong())
            DictionaryTextFormats.validateTable(snapshot) shouldBe 1
            snapshot.toString(Charsets.UTF_8) shouldBe "entry\tcode\t1\n"
            shouldThrow<IOException> { DictionaryTextFormats.readBounded((bytes + 1).inputStream(), bytes.size.toLong()) }
            shouldThrow<IOException> { DictionaryTextFormats.validateTable(byteArrayOf(0xc3.toByte(), 0x28, 0x09, 0x61)) }
        }

        "local export limit fails before writing the excess byte" {
            val output = java.io.ByteArrayOutputStream()
            DictionaryDocumentStager.boundedOutput(output, 4).use { bounded ->
                bounded.write(byteArrayOf(1, 2, 3, 4))
                shouldThrow<IOException> { bounded.write(5) }
            }
            output.toByteArray().toList() shouldBe listOf<Byte>(1, 2, 3, 4)
        }

        "malformed UTF-8 is rejected instead of silently replacing a phrase" {
            withText("") { file ->
                file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28, 0x09, 0x61))
                shouldThrow<IOException> { DictionaryTextFormats.validateTable(file) }
            }
        }

        "NUL and oversized lines are rejected" {
            withText("虚构\u0000短语\tcode\t1\n") {
                shouldThrow<IOException> { DictionaryTextFormats.validateTable(it) }
            }
            withText("x".repeat(64 * 1024 + 1) + "\tcode\t1\n") {
                shouldThrow<IOException> { DictionaryTextFormats.validateTable(it) }
            }
        }
    })
