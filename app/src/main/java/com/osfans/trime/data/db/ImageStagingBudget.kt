/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

internal class ImageStorageBusyException : IOException("Image storage is busy")

/** Reservations are shared by instances and the OS lock excludes other processes. */
internal object ImageStagingBudget {
    private class State(
        val owner: RandomAccessFile,
        val lock: FileLock,
        val byteLimit: Long,
        val activeLimit: Int,
        val reservations: MutableMap<String, Long> = mutableMapOf(),
    )

    private val states = mutableMapOf<File, State>()

    private fun lock(root: File, byteLimit: Long, activeLimit: Int): State {
        val lockFile = File(root, ".clipboard-image.lock")
        if (lockFile.canonicalFile != lockFile.absoluteFile) throw IOException("Indirect image lock path")
        val owner = RandomAccessFile(lockFile, "rw")
        try {
            val lock = try {
                owner.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } ?: throw ImageStorageBusyException()
            return State(owner, lock, byteLimit, activeLimit)
        } catch (failure: Throwable) {
            try { owner.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun unlock(state: State) {
        try {
            state.lock.release()
        } finally {
            state.owner.close()
        }
    }

    @Synchronized
    fun reserve(root: File, staging: File, id: String, bytes: Long, byteLimit: Long, activeLimit: Int): Closeable {
        val existing = states[root]
        val acquired = existing == null
        val state = existing ?: lock(root, byteLimit, activeLimit)
        try {
            if (staging.canonicalFile != staging.absoluteFile || !staging.isDirectory) throw IOException("Unsafe image staging directory")
            if (state.byteLimit != byteLimit || state.activeLimit != activeLimit) throw IOException("Conflicting image staging budgets")
            val entries = staging.listFiles() ?: throw IOException("Cannot inspect image staging")
            if (entries.any { it.name !in state.reservations || it.canonicalFile != it.absoluteFile || !it.isFile }) {
                throw IOException("Image staging requires recovery")
            }
            if (state.reservations.size >= activeLimit) throw IOException("Too many image staging operations")
            var used = 0L
            for (reserved in state.reservations.values) {
                if (reserved > byteLimit - used) throw IOException("Image staging budget exceeded")
                used += reserved
            }
            if (bytes > byteLimit - used) throw IOException("Image staging budget exceeded")
            check("$id.tmp" !in state.reservations)
            state.reservations["$id.tmp"] = bytes
            states[root] = state
            return Closeable { release(root, "$id.tmp") }
        } catch (failure: Throwable) {
            if (acquired) {
                try { unlock(state) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }

    @Synchronized
    private fun release(root: File, name: String) {
        val state = checkNotNull(states[root]) { "Image staging reservation is unavailable" }
        check(state.reservations.remove(name) != null) { "Image staging reservation was already released" }
        if (state.reservations.isEmpty()) {
            states.remove(root)
            unlock(state)
        }
    }

    /** Excludes both staging and reconciliation in every cooperating process. */
    @Synchronized
    fun acquireExclusive(root: File): Closeable {
        if (states.containsKey(root)) throw ImageStorageBusyException()
        val state = lock(root, 0, 0)
        states[root] = state
        return Closeable { releaseExclusive(root, state) }
    }

    @Synchronized
    private fun releaseExclusive(root: File, state: State) {
        check(states[root] === state && state.reservations.isEmpty())
        states.remove(root)
        unlock(state)
    }

}
