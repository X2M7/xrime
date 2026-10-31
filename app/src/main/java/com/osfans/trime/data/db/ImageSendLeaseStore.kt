/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

internal class ImageLeaseInUseException : IOException("Image is currently shared")

internal data class ImageSendLeaseClock(val now: () -> Long, val boot: () -> Int)

internal data class ImageSendLease(val token: String, val imageId: String, val targetUid: Int, val boot: Int, val expiresAt: Long)

/** All methods require the repository's cross-process exclusive lock. */
internal class ImageSendLeaseStore(
    root: File,
    private val now: () -> Long,
    private val boot: () -> Int,
) {
    companion object {
        internal const val MAX_LEASES = 64
        internal const val LEASE_DURATION_MS = 15L * 60 * 1000
        private const val MAGIC = 0x58524c31
        private const val RECORD_BYTES = 36L
    }

    private val directory = File(root, "clipboard-image-leases")

    private fun ensureDirectory() {
        if (directory.canonicalFile != directory.absoluteFile) throw IOException("Unsafe lease directory")
        if (!directory.isDirectory && !directory.mkdir()) throw IOException("Cannot create lease directory")
        if (!directory.isDirectory || directory.canonicalFile != directory.absoluteFile) throw IOException("Unsafe lease directory")
    }

    private fun canonicalUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private fun records(): List<ImageSendLease> {
        ensureDirectory()
        val currentBoot = boot()
        val currentTime = now()
        if (currentBoot < 0 || currentTime < 0) throw IOException("Lease clock is unavailable")
        val files = directory.listFiles() ?: throw IOException("Cannot inspect image leases")
        if (files.size > MAX_LEASES + 1) throw IOException("Too many image lease entries")
        val expired = mutableListOf<File>()
        val temporary = mutableListOf<File>()
        val active = mutableListOf<ImageSendLease>()
        for (file in files) {
            if (file.canonicalFile != file.absoluteFile || !file.isFile) throw IOException("Unsafe lease entry")
            val isTemporary = file.name.endsWith(".tmp")
            val suffix = if (isTemporary) ".tmp" else ".lease"
            if (!file.name.endsWith(suffix)) throw IOException("Unknown lease entry")
            val token = file.name.removeSuffix(suffix)
            if (!canonicalUuid(token)) throw IOException("Invalid lease token")
            if (isTemporary) {
                temporary.add(file)
                continue
            }
            if (file.length() != RECORD_BYTES) throw IOException("Invalid lease record")
            val entry = DataInputStream(file.inputStream()).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Invalid lease record")
                val imageId = UUID(input.readLong(), input.readLong()).toString()
                val uid = input.readInt()
                val recordBoot = input.readInt()
                val expiry = input.readLong()
                if (input.read() != -1 || uid <= 0 || recordBoot < 0 || expiry < 0) throw IOException("Invalid lease record")
                ImageSendLease(token, imageId, uid, recordBoot, expiry)
            }
            if (entry.boot != currentBoot || currentTime >= entry.expiresAt) expired.add(file) else active.add(entry)
        }
        if (active.size > MAX_LEASES) throw IOException("Too many image leases")
        // Only after validating the complete directory may expired or incomplete records be removed.
        for (file in expired + temporary) if (!file.delete()) throw IOException("Cannot remove expired image lease")
        return active
    }

    fun create(imageId: String, targetUid: Int): ImageSendLease {
        if (!canonicalUuid(imageId) || targetUid <= 0) throw IOException("Invalid lease target")
        val active = records()
        if (active.size >= MAX_LEASES) throw IOException("Image send lease limit reached")
        val currentTime = now()
        val currentBoot = boot()
        if (currentBoot < 0 || currentTime < 0 || currentTime > Long.MAX_VALUE - LEASE_DURATION_MS) {
            throw IOException("Lease clock is unavailable")
        }
        val token = UUID.randomUUID().toString()
        val id = UUID.fromString(imageId)
        val temporary = File(directory, "$token.tmp")
        val target = File(directory, "$token.lease")
        if (temporary.canonicalFile != temporary.absoluteFile || target.canonicalFile != target.absoluteFile || temporary.exists() || target.exists()) {
            throw IOException("Unsafe lease path")
        }
        try {
            FileOutputStream(temporary).use { output ->
                val data = DataOutputStream(output)
                data.writeInt(MAGIC)
                data.writeLong(id.mostSignificantBits)
                data.writeLong(id.leastSignificantBits)
                data.writeInt(targetUid)
                data.writeInt(currentBoot)
                data.writeLong(currentTime + LEASE_DURATION_MS)
                data.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) throw IOException("Cannot publish image lease")
        } catch (failure: Throwable) {
            if (temporary.exists() && !temporary.delete()) failure.addSuppressed(IOException("Cannot remove temporary lease"))
            throw failure
        }
        return ImageSendLease(token, imageId, targetUid, currentBoot, currentTime + LEASE_DURATION_MS)
    }

    fun find(token: String, targetUid: Int): ImageSendLease? {
        if (!canonicalUuid(token) || targetUid <= 0) return null
        return records().firstOrNull { it.token == token && it.targetUid == targetUid }
    }

    fun revoke(token: String, targetUid: Int): Boolean {
        val entry = find(token, targetUid) ?: return false
        val file = File(directory, "${entry.token}.lease")
        if (file.canonicalFile != file.absoluteFile || !file.isFile || !file.delete()) throw IOException("Cannot revoke image lease")
        return true
    }

    fun hasActive(imageId: String): Boolean = records().any { it.imageId == imageId }
}
