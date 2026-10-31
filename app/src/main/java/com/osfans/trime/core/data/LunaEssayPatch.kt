// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.core.data

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** Rebuild the derived vocabulary while validating every untrusted length and digest. */
internal object LunaEssayPatch {
    const val TARGET = "shared/luna_pinyin_essay.txt"
    const val SOURCE = "shared/essay.txt"
    const val PATCH = "xrime-internal/luna-essay.patch"
    private val magic = byteArrayOf(88, 82, 73, 77, 69, 80, 48, 49)
    private const val MAX_SIZE = 64L * 1024 * 1024
    private const val MAX_RANGES = 65_536

    /** Publish only after validation, using a single same-directory filesystem rename. */
    fun reconstructToFile(source: InputStream, patch: InputStream, destination: File, expectedSha256: String) {
        val parent = checkNotNull(destination.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "Cannot create vocabulary directory" }
        check(!destination.exists() || destination.isFile) { "Vocabulary destination is not a file" }
        val temporary = File.createTempFile(".xrime-luna-", ".tmp", parent)
        try {
            temporary.outputStream().buffered().use { output -> reconstruct(source, patch, output, expectedSha256) }
            check(temporary.renameTo(destination)) { "Cannot publish reconstructed vocabulary" }
        } finally {
            temporary.delete()
        }
    }

    fun reconstruct(source: InputStream, patch: InputStream, output: OutputStream, expectedSha256: String) {
        require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid expected vocabulary digest" }
        val data = DataInputStream(patch.buffered())
        val actualMagic = ByteArray(magic.size).also(data::readFully)
        require(actualMagic.contentEquals(magic)) { "Unknown vocabulary patch format" }
        val sourceSize = data.readLong()
        val targetSize = data.readLong()
        require(sourceSize in 0..MAX_SIZE && targetSize in 0..sourceSize) { "Invalid vocabulary sizes" }
        val sourceDigest = ByteArray(32).also(data::readFully)
        val targetDigest = ByteArray(32).also(data::readFully)
        require(targetDigest.toHex() == expectedSha256) { "Vocabulary patch does not match the logical checksum" }
        val count = data.readInt()
        require(count in 0..MAX_RANGES) { "Invalid vocabulary range count" }
        val ranges = ArrayList<Pair<Long, Long>>(count)
        var end = 0L
        var removed = 0L
        repeat(count) {
            val offset = data.readLong()
            val length = data.readLong()
            require(offset >= end && offset <= sourceSize && length > 0 && length <= sourceSize - offset) {
                "Invalid vocabulary patch range"
            }
            end = offset + length
            removed += length
            require(removed <= sourceSize) { "Vocabulary patch exceeds source size" }
            ranges.add(offset to length)
        }
        require(data.read() == -1 && sourceSize - removed == targetSize) { "Invalid vocabulary patch length" }

        val digestSource = MessageDigest.getInstance("SHA-256")
        val digestTarget = MessageDigest.getInstance("SHA-256")
        val input = DigestInputStream(source.buffered(), digestSource)
        val buffer = ByteArray(8192)
        var position = 0L
        fun transfer(countBytes: Long, keep: Boolean) {
            var left = countBytes
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                check(read > 0) { "Truncated vocabulary source" }
                if (keep) {
                    output.write(buffer, 0, read)
                    digestTarget.update(buffer, 0, read)
                }
                left -= read
                position += read
            }
        }
        ranges.forEach { (offset, length) ->
            transfer(offset - position, true)
            transfer(length, false)
        }
        transfer(sourceSize - position, true)
        check(input.read() == -1) { "Vocabulary source exceeds declared size" }
        check(MessageDigest.isEqual(digestSource.digest(), sourceDigest)) { "Vocabulary source checksum mismatch" }
        check(MessageDigest.isEqual(digestTarget.digest(), targetDigest)) { "Reconstructed vocabulary checksum mismatch" }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
