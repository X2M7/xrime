/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32

internal data class ValidatedClipboardImage(val mimeType: String, val width: Int, val height: Int)

/** Validates private staged PNG/JPEG bytes with bounded dimensions and a small sampled decode. */
internal object ClipboardImageValidator {
    private const val MAX_SIDE = 16_384
    private const val MAX_PIXELS = 16L * 1024 * 1024
    private const val MAX_PREVIEW_PIXELS = 256L * 256
    private const val MAX_PREVIEW_SIDE = 512
    private const val MAX_BYTES = 10L * 1024 * 1024

    private fun validatePng(file: File, authorize: () -> Unit) {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val signature = ByteArray(8)
            input.readFully(signature)
            if (!signature.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) {
                throw IOException("Invalid PNG signature")
            }
            var consumed = 8L
            var chunks = 0
            var first = true
            var sawData = false
            var afterData = false
            val buffer = ByteArray(8192)
            while (true) {
                authorize()
                if (++chunks > 4096) throw IOException("Too many PNG chunks")
                if (consumed > MAX_BYTES - 12) throw IOException("PNG exceeds byte budget")
                val length = input.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4)
                input.readFully(type)
                if (length > MAX_BYTES - consumed - 12) throw IOException("Invalid PNG chunk length")
                if (type.any { (it.toInt() !in 65..90) && (it.toInt() !in 97..122) } || type[2].toInt() !in 65..90) {
                    throw IOException("Invalid PNG chunk type")
                }
                val name = String(type, Charsets.US_ASCII)
                if (type[0].toInt() in 65..90 && name !in setOf("IHDR", "PLTE", "IDAT", "IEND")) {
                    throw IOException("Unsupported critical PNG chunk")
                }
                if (first && (name != "IHDR" || length != 13L)) throw IOException("Invalid PNG header")
                if (!first && name == "IHDR") throw IOException("Duplicate PNG header")
                if (name == "acTL") throw IOException("Animated PNG is unsupported")
                if (name == "IDAT") {
                    if (afterData) throw IOException("Nonconsecutive PNG data")
                    sawData = true
                } else if (sawData) {
                    afterData = true
                }
                val crc = CRC32().apply { update(type) }
                var remaining = length
                while (remaining > 0) {
                    authorize()
                    val count = minOf(buffer.size.toLong(), remaining).toInt()
                    input.readFully(buffer, 0, count)
                    crc.update(buffer, 0, count)
                    remaining -= count
                }
                val expectedCrc = input.readInt().toLong() and 0xffffffffL
                if (crc.value != expectedCrc) throw IOException("Invalid PNG checksum")
                consumed += length + 12
                first = false
                if (name == "IEND") {
                    if (length != 0L || !sawData || input.read() != -1) throw IOException("Invalid PNG end")
                    return
                }
            }
        }
    }

    private fun validateJpeg(file: File, authorize: () -> Unit) {
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            var previous = -1
            var last = -1
            while (true) {
                authorize()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) throw IOException("Image made no read progress")
                if (count.toLong() > MAX_BYTES - total) throw IOException("JPEG exceeds byte budget")
                total += count
                if (count == 1) {
                    previous = last
                } else {
                    previous = buffer[count - 2].toInt() and 0xff
                }
                last = buffer[count - 1].toInt() and 0xff
            }
            if (previous != 0xff || last != 0xd9) throw IOException("Invalid JPEG end")
        }
    }

    private fun sampleFor(width: Int, height: Int): Int {
        var sample = 1
        fun scaled(value: Int) = (value.toLong() + sample - 1) / sample
        while (scaled(width) > MAX_PREVIEW_SIDE || scaled(height) > MAX_PREVIEW_SIDE ||
            scaled(width) * scaled(height) > MAX_PREVIEW_PIXELS
        ) sample *= 2
        return sample
    }

    private fun decodePreview(file: File, width: Int, height: Int, authorize: () -> Unit): Bitmap {
        authorize()
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleFor(width, height)
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: throw IOException("Image cannot be decoded")
        try {
            authorize()
            if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.width > MAX_PREVIEW_SIDE ||
                bitmap.height > MAX_PREVIEW_SIDE || bitmap.width.toLong() * bitmap.height > MAX_PREVIEW_PIXELS ||
                bitmap.allocationByteCount.toLong() > MAX_PREVIEW_PIXELS * 4
            ) throw IOException("Decoded image exceeds preview budget")
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    /** Caller owns and must recycle the returned bounded bitmap. */
    fun thumbnail(file: File, expected: ValidatedClipboardImage, authorize: () -> Unit): Bitmap {
        if (validate(file, authorize) != expected) throw IOException("Image metadata changed")
        return decodePreview(file, expected.width, expected.height, authorize)
    }

    fun validate(file: File, authorize: () -> Unit): ValidatedClipboardImage {
        authorize()
        if (!file.isFile || file.length() <= 0 || file.length() > MAX_BYTES) throw IOException("Invalid image byte count")
        val header = ByteArray(8)
        val count = file.inputStream().use { it.read(header) }
        val mime = when {
            count == 8 && header.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
            count >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() -> "image/jpeg"
            else -> throw IOException("Unsupported image format")
        }
        if (mime == "image/png") validatePng(file, authorize) else validateJpeg(file, authorize)
        authorize()
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        BitmapFactory.decodeFile(file.path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (bounds.outMimeType != mime || width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE ||
            width.toLong() * height.toLong() > MAX_PIXELS
        ) throw IOException("Invalid or oversized image")
        val bitmap = decodePreview(file, width, height, authorize)
        bitmap.recycle()
        return ValidatedClipboardImage(mime, width, height)
    }
}
