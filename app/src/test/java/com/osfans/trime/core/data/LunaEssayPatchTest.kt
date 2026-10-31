// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.core.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
private fun patch(source: ByteArray, target: ByteArray, ranges: List<Pair<Long, Long>>): ByteArray = ByteArrayOutputStream().also { bytes ->
    DataOutputStream(bytes).use { out ->
        out.write("XRIMEP01".toByteArray())
        out.writeLong(source.size.toLong())
        out.writeLong(target.size.toLong())
        out.write(sha256(source))
        out.write(sha256(target))
        out.writeInt(ranges.size)
        ranges.forEach { (offset, length) -> out.writeLong(offset); out.writeLong(length) }
    }
}.toByteArray()

class LunaEssayPatchTest : StringSpec({
    val source = "alpha\nskip\nomega\n".toByteArray()
    val target = "alpha\nomega\n".toByteArray()
    val valid = patch(source, target, listOf(6L to 5L))
    fun rebuild(base: ByteArray, delta: ByteArray, expected: String = sha256(target).hex()): ByteArray = ByteArrayOutputStream().also { out ->
        LunaEssayPatch.reconstruct(ByteArrayInputStream(base), ByteArrayInputStream(delta), out, expected)
    }.toByteArray()

    "reconstructs the byte-exact logical vocabulary" {
        rebuild(source, valid).toList() shouldBe target.toList()
    }
    "rejects truncation, wrong source, wrong checksum and trailing patch bytes" {
        shouldThrow<Exception> { rebuild(source, valid.copyOf(valid.size - 1)) }
        shouldThrow<Exception> { rebuild(source.copyOf(source.size - 1), valid) }
        shouldThrow<Exception> { rebuild(source.copyOf().also { it[0] = 1 }, valid) }
        shouldThrow<Exception> { rebuild(source, valid, "0".repeat(64)) }
        shouldThrow<Exception> { rebuild(source, valid + byteArrayOf(0)) }
    }
    "rejects out-of-range, overlapping and overflowed deletion ranges" {
        shouldThrow<Exception> { rebuild(source, patch(source, target, listOf(-1L to 5L))) }
        shouldThrow<Exception> { rebuild(source, patch(source, target, listOf(6L to Long.MAX_VALUE))) }
        shouldThrow<Exception> { rebuild(source, patch(source, target, listOf(6L to 3L, 8L to 2L))) }
    }
    "a failed reconstruction leaves the old destination and descriptor intact" {
        val root = createTempDirectory("luna-patch-atomic").toFile()
        try {
            val file = root.resolve("luna_pinyin_essay.txt").apply { writeText("old") }
            var published = false
            shouldThrow<Exception> {
                DataResourceInstaller.install(root, listOf(DataDiff.UpdateFile(LunaEssayPatch.TARGET)), { _, dest ->
                    LunaEssayPatch.reconstructToFile(
                        ByteArrayInputStream(source.copyOf(source.size - 1)), ByteArrayInputStream(valid), dest, sha256(target).hex(),
                    )
                }, { published = true })
            }
            file.readText() shouldBe "old"
            published shouldBe false
            root.listFiles()!!.map { it.name } shouldBe listOf("luna_pinyin_essay.txt")
        } finally { root.deleteRecursively() }
    }
    "the logical descriptor supports unchanged upgrades and missing-file repair" {
        val descriptor = DataChecksums("same", mapOf(LunaEssayPatch.TARGET to sha256(target).hex()))
        DataDiff.diff(descriptor, descriptor) { true } shouldBe emptyList()
        DataDiff.diff(descriptor, descriptor) { false } shouldBe listOf(DataDiff.CreateFile(LunaEssayPatch.TARGET))
        val old = DataChecksums("old", mapOf(LunaEssayPatch.TARGET to "0".repeat(64)))
        DataDiff.diff(old, descriptor) { true } shouldBe listOf(DataDiff.UpdateFile(LunaEssayPatch.TARGET))
    }
})
