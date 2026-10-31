// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.core.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

internal object SyncFingerprint {
    const val BUFFER_SIZE = 32 * 1024

    /** Read until progress or EOF, while allowing a caller to propagate cancellation. */
    fun readWithProgress(input: InputStream, buffer: ByteArray, checkActive: () -> Unit = {}): Int {
        require(buffer.isNotEmpty())
        var emptyReads = 0
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count != 0) return count
            if (++emptyReads > 16) throw IOException("Input stream made no read progress")
        }
    }

    fun digest(input: InputStream, maxBytes: Long = Long.MAX_VALUE, checkActive: () -> Unit = {}): ByteArray {
        require(maxBytes >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = readWithProgress(input, buffer, checkActive)
            if (count < 0) break
            if (count.toLong() > maxBytes - total) throw IOException("Input stream exceeds expected length")
            total += count
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun of(file: File): String = file.inputStream().use { hex(digest(it)) }
}
