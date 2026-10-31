// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Bounded private byte staging; no clipboard capture, sharing or image decoding is wired here.
 * A caller must verify image format/dimensions and privacy authorization before publication.
 * The caller owns and closes [InputStream]. A single blocking read cannot be interrupted by
 * coroutine checks alone; the eventual provider integration must also close its descriptor.
 */
internal class PrivateImageByteStore(
    filesDir: File,
    private val perImageLimit: Long = 10L * 1024 * 1024,
    private val totalLimit: Long = 50L * 1024 * 1024,
    private val stagingLimit: Long = 20L * 1024 * 1024,
    private val maxStagedImages: Int = 2,
) {
    companion object {
        private const val MAX_EMPTY_READS = 16
        private const val BUFFER_SIZE = 32 * 1024
        // Serialize quota checks across instances in this process, not only per instance.
        private val publicationMutex = Mutex()
    }

    private val root = filesDir.canonicalFile
    private val staging = File(root, "clipboard-image-staging")
    private val published = File(root, "clipboard-image-published")

    init {
        require(perImageLimit > 0 && totalLimit >= perImageLimit && stagingLimit >= perImageLimit && maxStagedImages > 0)
        require(root.isDirectory) { "Private files directory is unavailable" }
        ensurePrivateDirectory(staging)
        ensurePrivateDirectory(published)
    }

    private fun ensurePrivateDirectory(directory: File) {
        check(directory.parentFile == root && directory.canonicalFile == directory.absoluteFile) {
            "Image storage path is indirect"
        }
        check(directory.isDirectory || directory.mkdir()) { "Cannot create private image storage" }
        check(directory.isDirectory && directory.canonicalFile == directory.absoluteFile) { "Image storage is not a directory" }
    }

    /** Caller must first verify a complete database snapshot before deleting any object. */
    suspend fun <T> withExclusiveRecovery(block: suspend (File, File) -> T): T = withContext(Dispatchers.IO) {
        val lock = ImageStagingBudget.acquireExclusive(root)
        var primaryFailure: Throwable? = null
        try {
            block(staging, published)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            closePreservingFailure(primaryFailure) { lock.close() }
        }
    }

    suspend fun stage(input: InputStream, authorize: () -> Unit): StagedImage {
        var staged: StagedImage? = null
        try {
            return withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val id = UUID.randomUUID().toString()
                val temporary = File(staging, "$id.tmp")
                context.ensureActive()
                authorize()
                val reservation = ImageStagingBudget.reserve(root, staging, id, perImageLimit, stagingLimit, maxStagedImages)
                try {
                    check(temporary.canonicalFile == temporary.absoluteFile && !temporary.exists()) { "Unsafe image staging path" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var emptyReads = 0
                        while (true) {
                            context.ensureActive()
                            authorize()
                            val count = input.read(buffer)
                            // The provider may have blocked across an editor/privacy boundary.
                            context.ensureActive()
                            authorize()
                            if (count < 0) break
                            if (count == 0) {
                                if (++emptyReads > MAX_EMPTY_READS) throw IOException("Image source made no read progress")
                                continue
                            }
                            emptyReads = 0
                            if (count.toLong() > perImageLimit - total) throw IOException("Image exceeds its byte limit")
                            total += count
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                        context.ensureActive()
                        authorize()
                        if (total == 0L) throw IOException("Image is empty")
                        output.fd.sync()
                    }
                    StagedImage(id, temporary, total, digest.digest().joinToString("") { "%02x".format(it) }, reservation).also { staged = it }
                } catch (failure: Throwable) {
                    if (temporary.exists() && !temporary.delete()) failure.addSuppressed(IOException("Cannot remove staged image"))
                    try { reservation.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            try {
                staged?.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    inner class StagedImage internal constructor(
        val id: String,
        private val temporary: File,
        val byteCount: Long,
        val sha256: String,
        private val reservation: Closeable,
    ) : Closeable {
        private var finished = false

        /** Inspection is repeated at publication; mutation is detected by the stored digest. */
        suspend fun <T> inspect(authorize: () -> Unit, inspect: (File) -> T): T = withContext(Dispatchers.IO) {
            synchronized(this@StagedImage) {
                check(!finished && temporary.isFile) { "Staged image is unavailable" }
                authorize()
                inspect(temporary).also { authorize() }
            }
        }

        /** Validation must reject unsupported, malformed or unsafe image content. */
        suspend fun publish(validate: (File) -> Unit, authorize: () -> Unit): File =
            publishAndCommit(validate, authorize) { }

        /** Keep the cross-process reservation until the durable metadata callback has completed. */
        suspend fun publishAndCommit(
            validate: (File) -> Unit,
            authorize: () -> Unit,
            acceptPublication: () -> Unit = authorize,
            commit: suspend (File) -> Unit,
        ): File = withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    publicationMutex.withLock {
                        val target = synchronized(this@StagedImage) {
                            check(!finished && temporary.isFile) { "Staged image is unavailable" }
                            context.ensureActive()
                            authorize()
                            validate(temporary)
                            check(temporary.canonicalFile == temporary.absoluteFile && temporary.isFile) { "Unsafe image staging path" }
                            val actual = MessageDigest.getInstance("SHA-256")
                            var observed = 0L
                            temporary.inputStream().use { input ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    context.ensureActive()
                                    authorize()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) throw IOException("Staged image made no read progress")
                                    if (count.toLong() > byteCount - observed) throw IOException("Validated image changed")
                                    observed += count
                                    actual.update(buffer, 0, count)
                                }
                            }
                            if (observed != byteCount || actual.digest().joinToString("") { "%02x".format(it) } != sha256) {
                                throw IOException("Validated image changed")
                            }
                            authorize()
                            var used = 0L
                            val entries = published.listFiles() ?: throw IOException("Cannot inspect private image storage")
                            for (entry in entries) {
                                if (entry.canonicalFile != entry.absoluteFile || !entry.isFile) throw IOException("Unsafe image storage entry")
                                val size = entry.length()
                                if (size > totalLimit - used) throw IOException("Image storage exceeds its byte budget")
                                used += size
                            }
                            if (byteCount > totalLimit - used) throw IOException("Image storage is full")
                            val target = File(published, id)
                            check(target.canonicalFile == target.absoluteFile && !target.exists()) { "Unsafe image publication path" }
                            context.ensureActive()
                            authorize()
                            // This short acceptance step is the publication linearization point.
                            // It must never perform IO while holding its event/privacy locks.
                            acceptPublication()
                            if (!temporary.renameTo(target)) throw IOException("Cannot publish image")
                            finished = true
                            target
                        }
                        var primaryFailure: Throwable? = null
                        try {
                            context.ensureActive()
                            // Once metadata work starts, finish its transaction before releasing the lock.
                            withContext(NonCancellable) { commit(target) }
                            target
                        } catch (failure: Throwable) {
                            primaryFailure = failure
                            throw failure
                        } finally {
                            // A failure after rename is ambiguous until Room is inspected. Preserve
                            // the object for conservative recovery rather than deleting a live file.
                            closePreservingFailure(primaryFailure) { reservation.close() }
                        }
                    }
        }

        override fun close() = synchronized(this) {
            if (!finished) {
                if (temporary.exists() && !temporary.delete()) throw IOException("Cannot remove staged image")
                finished = true
                reservation.close()
            }
        }
    }
}
