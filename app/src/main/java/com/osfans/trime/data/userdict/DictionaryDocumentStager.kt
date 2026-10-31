/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.userdict

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.osfans.trime.data.db.closePreservingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Isolate untrusted provider IO from Rime's serial maintenance thread. */
internal object DictionaryDocumentStager {
    const val MAX_EXPORT_BYTES = 256L * 1024 * 1024
    private val slots = ArrayBlockingQueue<Int>(2).apply { add(0); add(1) }
    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Slots are reserved before work is scheduled and remain held until blocked IO exits. */
    private suspend fun <T> withSlot(context: Context, operation: suspend (File, Job) -> T): T {
        val slot = slots.poll() ?: throw IOException("Dictionary transfer is busy")
        try {
            return withContext(Dispatchers.IO) {
                val job = checkNotNull(currentCoroutineContext()[Job])
                val directory = File(context.cacheDir.canonicalFile, "dictionary-import-staging")
                if (directory.canonicalFile != directory.absoluteFile ||
                    (!directory.isDirectory && !directory.mkdir() && !directory.isDirectory) || !directory.isDirectory
                ) throw IOException("Dictionary staging is unavailable")
                val lockFile = File(directory, "slot-$slot.lock")
                val staged = File(directory, "slot-$slot.tmp")
                if (lockFile.canonicalFile != lockFile.absoluteFile || staged.canonicalFile != staged.absoluteFile) {
                    throw IOException("Unsafe dictionary staging path")
                }
                RandomAccessFile(lockFile, "rw").use { lockHandle ->
                    val lock = try { lockHandle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                        ?: throw IOException("Dictionary staging is busy")
                    var primary: Throwable? = null
                    try {
                        // Only the holder of this slot's OS lock may remove its stale file.
                        if (staged.exists() && (!staged.isFile || !staged.delete())) throw IOException("Cannot clear dictionary staging")
                        operation(staged, job)
                    } catch (failure: Throwable) {
                        primary = failure
                        throw failure
                    } finally {
                        closePreservingFailure(primary) {
                            var deletionFailure: Throwable? = null
                            try {
                                if (staged.exists() && !staged.delete()) throw IOException("Cannot remove dictionary staging")
                            } catch (failure: Throwable) {
                                deletionFailure = failure
                                throw failure
                            } finally {
                                closePreservingFailure(deletionFailure) { lock.release() }
                            }
                        }
                    }
                }
            }
        } finally {
            check(slots.offer(slot)) { "Dictionary staging slot was already released" }
        }
    }

    /** A blocked provider may retain its slot, but never Rime's thread or an unbounded queue. */
    private suspend fun <T> withDescriptor(
        context: Context,
        uri: Uri,
        mode: String,
        job: Job,
        operation: suspend (ParcelFileDescriptor) -> T,
    ): T {
        val signal = CancellationSignal()
        val descriptor = AtomicReference<ParcelFileDescriptor?>()
        val finished = AtomicBoolean(false)
        val watcher = watcherScope.launch {
            while (!finished.get() && job.isActive) delay(50)
            if (!finished.get() && !job.isActive) {
                try { signal.cancel() } catch (_: RuntimeException) { }
                try { descriptor.getAndSet(null)?.close() } catch (_: IOException) { }
            }
        }
        var primary: Throwable? = null
        try {
            job.ensureActive()
            val opened = context.contentResolver.openFileDescriptor(uri, mode, signal)
                ?: throw IOException("Cannot open dictionary document")
            descriptor.set(opened)
            job.ensureActive()
            return operation(opened)
        } catch (failure: Throwable) {
            if (!job.isActive && (failure is IOException || failure is android.os.OperationCanceledException)) {
                try { job.ensureActive() } catch (cancelled: CancellationException) {
                    cancelled.addSuppressed(failure)
                    primary = cancelled
                    throw cancelled
                }
            }
            primary = failure
            throw failure
        } finally {
            finished.set(true)
            watcher.cancel()
            closePreservingFailure(primary) { descriptor.getAndSet(null)?.close() }
        }
    }

    suspend fun <T> read(context: Context, uri: Uri, limit: Long, operation: suspend (InputStream) -> T): T =
        withSlot(context) { staged, job ->
            withDescriptor(context, uri, "r", job) { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    DictionaryTextFormats.copyBounded(input, staged, limit, job::ensureActive)
                }
            }
            job.ensureActive()
            staged.inputStream().use { operation(it) }
        }

    /** [generate] finishes using Rime before the external document is opened or truncated. */
    suspend fun <T> write(context: Context, uri: Uri, limit: Long, generate: suspend (File) -> T): T =
        withSlot(context) { staged, job ->
            job.ensureActive()
            val result = generate(staged)
            job.ensureActive()
            if (staged.canonicalFile != staged.absoluteFile || !staged.isFile) throw IOException("Dictionary export is unavailable")
            if (staged.length() > limit) throw DictionarySizeLimitException("Dictionary export exceeds the $limit byte limit")
            withDescriptor(context, uri, "wt", job) { descriptor ->
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    staged.inputStream().use { input -> DictionaryTextFormats.copyBounded(input, output, limit, job::ensureActive) }
                    output.flush()
                }
            }
            result
        }

    /** Bounds locally generated phrase output before touching the external document. */
    fun boundedOutput(output: OutputStream, limit: Long): OutputStream = object : OutputStream() {
        private var written = 0L
        override fun write(value: Int) {
            if (written >= limit) throw DictionarySizeLimitException("Dictionary export exceeds the $limit byte limit")
            output.write(value)
            written++
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length < 0 || offset < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
            if (length.toLong() > limit - written) throw DictionarySizeLimitException("Dictionary export exceeds the $limit byte limit")
            output.write(bytes, offset, length)
            written += length
        }
        override fun flush() = output.flush()
        override fun close() = output.close()
    }
}
