/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Reader

/** A source was rejected before its complete contents were allocated. */
internal class TextReadLimitExceeded(val source: String, val limit: Int) :
    IOException("Text source exceeds its limit: $source")

private const val MAX_EMPTY_READS = 16
internal const val DEFAULT_YAML_CODE_POINT_LIMIT = 3 * 1024 * 1024

/** Counts Unicode code points across buffer boundaries, as the YAML parser does. */
internal fun readTextAtMostCodePoints(reader: Reader, limit: Int, source: String): String {
    require(limit >= 0)
    val result = StringBuilder()
    val buffer = CharArray(4096)
    var points = 0
    var previousHigh = false
    var emptyReads = 0
    while (true) {
        val count = reader.read(buffer, 0, buffer.size)
        if (count < 0) return result.toString()
        if (count == 0) {
            if (++emptyReads > MAX_EMPTY_READS) throw IOException("Text source made no read progress: $source")
            continue
        }
        emptyReads = 0
        for (i in 0 until count) {
            val char = buffer[i]
            if (!(previousHigh && Character.isLowSurrogate(char))) {
                if (points >= limit) throw TextReadLimitExceeded(source, limit)
                points++
            }
            previousHigh = Character.isHighSurrogate(char)
        }
        result.append(buffer, 0, count)
    }
}

internal fun File.readTextAtMostCodePoints(limit: Int): String =
    bufferedReader(Charsets.UTF_8).use { readTextAtMostCodePoints(it, limit, path) }

/** Limits actual bytes read even if a file grows after its metadata was inspected. */
internal fun readBytesAtMost(input: InputStream, limit: Int, source: String): ByteArray {
    require(limit >= 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    var emptyReads = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (count == 0) {
            if (++emptyReads > MAX_EMPTY_READS) throw IOException("Byte source made no read progress: $source")
            continue
        }
        emptyReads = 0
        if (count > limit - total) throw TextReadLimitExceeded(source, limit)
        output.write(buffer, 0, count)
        total += count
    }
}

internal fun File.readBytesAtMost(limit: Int): ByteArray =
    inputStream().use { readBytesAtMost(it, limit, path) }
