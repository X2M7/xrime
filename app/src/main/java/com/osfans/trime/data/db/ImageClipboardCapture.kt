/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import android.content.ClipData
import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounded private capture; the listener supplies the exact event and privacy authorization. */
internal class ImageClipboardCapture(
    private val context: Context,
    private val store: PrivateImageByteStore,
    private val repository: ClipboardImageRepository,
) {
    companion object {
        // Bound provider open/read calls across every instance in this process.
        private val openSlots = Semaphore(2)
    }

    private class Slot {
        private val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) openSlots.release()
        }
    }

    private fun acquireSlot(): Slot? = if (openSlots.tryAcquire()) Slot() else null

    /** The caller owns [authorization]; saturation rejects before any provider work is queued. */
    suspend fun capture(clip: ClipData, authorization: ImageCaptureAuthorization): ClipboardImagePayload? {
        authorization.requireAllowed()
        val slot = acquireSlot() ?: return null
        try {
            return captureReserved(clip, authorization)
        } finally {
            slot.release()
        }
    }

    /**
     * Listener entry: acquire the bounded slot before creating any coroutine. The caller
     * transfers ownership of [authorization], including when the request is rejected.
     */
    fun tryStart(
        scope: CoroutineScope,
        clip: ClipData,
        authorization: ImageCaptureAuthorization,
        onResult: (ClipboardImagePayload?) -> Unit,
        onFailure: () -> Unit,
    ): Job? {
        val slot = acquireSlot()
        if (slot == null) {
            authorization.close()
            return null
        }
        val job = try {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val result = captureReserved(clip, authorization)
                    authorization.requireAllowed()
                    onResult(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: PersistencePermitExpiredException) {
                    // The original clipboard or editor no longer owns this capture.
                } catch (_: Exception) {
                    onFailure()
                } finally {
                    authorization.close()
                    slot.release()
                }
            }
        } catch (failure: Throwable) {
            authorization.close()
            slot.release()
            throw failure
        }
        // Also cover a scope cancelled before its coroutine body is entered.
        job.invokeOnCompletion {
            authorization.close()
            slot.release()
        }
        return job
    }

    /** [authorization] must have been acquired before retrieving this exact system [clip]. */
    private suspend fun captureReserved(clip: ClipData, authorization: ImageCaptureAuthorization): ClipboardImagePayload? = coroutineScope {
        authorization.requireAllowed()
        val signal = CancellationSignal()
        val descriptor = AtomicReference<ParcelFileDescriptor?>()
        val worker = async(Dispatchers.IO) {
            authorization.requireAllowed()
            val sensitive = try {
                SensitiveClipboardCompat.isSensitive(clip.description)
            } catch (failure: RuntimeException) {
                if (failure is CancellationException || failure is PersistencePermitExpiredException) throw failure
                true
            }
            if (sensitive) return@async null
            val admitted = ClipboardImageUriAdmission.admit(context, clip, authorization) ?: return@async null
            val opened = context.contentResolver.openFileDescriptor(admitted.uri, "r", signal)
                ?: throw IOException("Image source is unavailable")
            descriptor.set(opened)
            var primaryFailure: Throwable? = null
            try {
                authorization.requireAllowed()
                ParcelFileDescriptor.AutoCloseInputStream(opened).use { input ->
                    val staged = store.stage(input, authorization::requireAllowed)
                    val permit = try {
                        authorization.acquirePersistencePermit()
                    } catch (failure: Throwable) {
                        closePreservingFailure(failure) { staged.close() }
                        throw failure
                    }
                    try {
                        repository.save(staged, admitted, authorization, permit)
                    } finally {
                        com.osfans.trime.ime.core.PrivacyModeState.releasePersistencePermit(permit)
                    }
                }
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                descriptor.compareAndSet(opened, null)
                closePreservingFailure(primaryFailure) { opened.close() }
            }
        }
        // coroutineScope waits for the worker to finish even if descriptor close cannot unblock it;
        // the outer slot remains reserved until then.
        val watcher = launch(Dispatchers.Default) {
            while (isActive && worker.isActive) {
                delay(100)
                try {
                    authorization.requireAllowed()
                } catch (_: PersistencePermitExpiredException) {
                    try {
                        signal.cancel()
                    } finally {
                        runCatching { descriptor.getAndSet(null)?.close() }
                        worker.cancel()
                    }
                    break
                }
            }
        }
        var primaryFailure: Throwable? = null
        try {
            worker.await()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            closePreservingFailure(primaryFailure) {
                try {
                    signal.cancel()
                } finally {
                    runCatching { descriptor.getAndSet(null)?.close() }
                    watcher.cancel()
                }
            }
        }
    }
}
