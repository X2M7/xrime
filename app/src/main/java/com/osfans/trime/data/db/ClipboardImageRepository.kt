/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import android.database.sqlite.SQLiteConstraintException
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.paging.PagingSource
import androidx.room.withTransaction
import com.osfans.trime.ime.core.PrivacyModeState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Private image history with bounded capture, recovery and scoped read leases. */
internal class ClipboardImageRepository(
    private val database: Database,
    private val store: PrivateImageByteStore,
    private val leaseClock: ImageSendLeaseClock? = null,
    private val beforePublicationAccepted: () -> Unit = {},
    private val afterPublicationAccepted: () -> Unit = {},
) {
    /** Caller owns [permit] and must release it, even when this operation fails. */
    suspend fun save(
        staged: PrivateImageByteStore.StagedImage,
        admitted: AdmittedClipboardImage,
        authorization: ImageCaptureAuthorization,
        permit: Long,
    ): ClipboardImagePayload {
        var primaryFailure: Throwable? = null
        try {
            authorization.requireMatchingPermit(permit)
            val image = staged.inspect(authorization::requireAllowed, admitted::validate)
            val pending = ClipboardImagePayload(
                staged.id, staged.sha256, image.mimeType, staged.byteCount,
                image.width, image.height, System.currentTimeMillis(), false, ready = false,
            )
            val duplicate = try {
                database.withTransaction {
                    clipboardPersistenceGate.guardTransaction(permit) {
                        authorization.requireMatchingPermit(permit)
                        val dao = database.clipboardImagePayloadDao()
                        if (dao.findByHash(pending.sha256) != null) {
                            true
                        } else {
                            if (dao.recordCount() >= MAX_IMAGE_RECORDS) throw IOException("Image history is full")
                            dao.insert(pending)
                            false
                        }
                    }
                }
            } catch (_: SQLiteConstraintException) {
                true
            }
            if (duplicate) {
                // Release this candidate before trying the exclusive lock. Never overwrite or
                // delete the existing object, including a pending or corrupt one.
                staged.close()
                return refreshDuplicate(pending, image, authorization, permit)
            }
            staged.publishAndCommit(
                validate = { file -> check(admitted.validate(file) == image) { "Image metadata changed" } },
                authorize = authorization::requireAllowed,
                acceptPublication = {
                    beforePublicationAccepted()
                    authorization.acceptPublication(permit)
                    afterPublicationAccepted()
                },
            ) {
                database.withTransaction {
                    clipboardPersistenceGate.guardTransaction(permit) {
                        check(database.clipboardImagePayloadDao().markReadyForCommit(staged.id) == 1) { "Pending image is unavailable" }
                    }
                }
            }
            return pending.copy(ready = true)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            closePreservingFailure(primaryFailure) { staged.close() }
        }
    }

    private suspend fun refreshDuplicate(
        candidate: ClipboardImagePayload,
        expected: ValidatedClipboardImage,
        authorization: ImageCaptureAuthorization,
        permit: Long,
    ): ClipboardImagePayload {
        repeat(30) {
            authorization.requireMatchingPermit(permit)
            val existing = try {
                store.withExclusiveRecovery { _, published ->
                    authorization.requireMatchingPermit(permit)
                    val current = database.clipboardImagePayloadDao().findByHash(candidate.sha256)
                        ?: return@withExclusiveRecovery null
                    if (current.byteCount != candidate.byteCount ||
                        current.mimeType != expected.mimeType || current.width != expected.width || current.height != expected.height
                    ) throw IOException("Duplicate image metadata disagrees")
                    val file = checkedObject(published, current.id)
                    verifyBytes(file, current, authorization::requireAllowed)
                    if (ClipboardImageValidator.validate(file, authorization::requireAllowed) != expected) {
                        throw IOException("Duplicate image validation failed")
                    }
                    val now = maxOf(System.currentTimeMillis(), current.createdAt)
                    beforePublicationAccepted()
                    authorization.acceptPublication(permit)
                    afterPublicationAccepted()
                    database.withTransaction {
                        clipboardPersistenceGate.guardTransaction(permit) {
                            check(database.clipboardImagePayloadDao().refreshReady(current.id, now) == 1) {
                                "Duplicate image changed before refresh"
                            }
                        }
                    }
                    current.copy(createdAt = now)
                }
            } catch (_: ImageStorageBusyException) {
                null
            }
            if (existing != null) return existing
            delay(100)
        }
        throw IOException("Duplicate image is not ready")
    }

    fun readyImages(): PagingSource<Int, ClipboardImagePayload> = database.clipboardImagePayloadDao().readyImages()

    private fun checkedObject(directory: File, id: String, suffix: String = ""): File {
        if (runCatching { UUID.fromString(id).toString() != id }.getOrDefault(true) ||
            !directory.isDirectory || directory.canonicalFile != directory.absoluteFile
        ) throw IOException("Unsafe image identifier or directory")
        return File(directory, id + suffix).also { file ->
            if (file.canonicalFile != file.absoluteFile || (file.exists() && !file.isFile)) {
                throw IOException("Unsafe image path")
            }
        }
    }

    private fun verifyBytes(file: File, metadata: ClipboardImagePayload, authorize: () -> Unit) {
        if (!file.isFile || metadata.byteCount !in 1..(10L * 1024 * 1024)) throw IOException("Image is unavailable")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                authorize()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0 || count.toLong() > metadata.byteCount - total) throw IOException("Image size changed")
                total += count
                digest.update(buffer, 0, count)
            }
        }
        authorize()
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (total != metadata.byteCount || hash != metadata.sha256) throw IOException("Image integrity check failed")
    }

    /** Caller must recycle the bitmap and recheck its page/editor token before showing it. */
    suspend fun loadThumbnail(id: String, authorize: () -> Unit): Bitmap? {
        val epoch = PrivacyModeState.observeOrdinaryPersistence() ?: throw PersistencePermitExpiredException()
        var result: Bitmap? = null
        try {
            return store.withExclusiveRecovery { _, published ->
                val coroutine = currentCoroutineContext()
                fun checkCurrent() {
                    coroutine.ensureActive()
                    authorize()
                    if (!PrivacyModeState.isOrdinaryEpoch(epoch)) throw PersistencePermitExpiredException()
                }
                checkCurrent()
                val metadata = database.clipboardImagePayloadDao().get(id) ?: return@withExclusiveRecovery null
                val file = checkedObject(published, id)
                verifyBytes(file, metadata, ::checkCurrent)
                val expected = ValidatedClipboardImage(metadata.mimeType, metadata.width, metadata.height)
                ClipboardImageValidator.thumbnail(file, expected, ::checkCurrent).also { result = it }
            }
        } catch (failure: Throwable) {
            result?.recycle()
            throw failure
        }
    }

    private fun activeLeaseExists(root: File, id: String): Boolean {
        val directory = File(root, "clipboard-image-leases")
        if (directory.canonicalFile != directory.absoluteFile) throw IOException("Unsafe lease directory")
        if (!directory.exists()) return false
        val clock = leaseClock ?: throw IOException("Image lease state is unavailable")
        return ImageSendLeaseStore(root, clock.now, clock.boot).hasActive(id)
    }

    /** Prepare a bounded lease; dispatch and Android URI grants are separate later steps. */
    suspend fun createSendLease(id: String, targetUid: Int, authorize: () -> Unit): ImageSendLease {
        val epoch = PrivacyModeState.observeOrdinaryPersistence() ?: throw PersistencePermitExpiredException()
        val clock = leaseClock ?: throw IOException("Image sending is unavailable")
        return store.withExclusiveRecovery { _, published ->
            fun checkCurrent() {
                authorize()
                if (!PrivacyModeState.isOrdinaryEpoch(epoch)) throw PersistencePermitExpiredException()
            }
            checkCurrent()
            val metadata = database.clipboardImagePayloadDao().get(id) ?: throw IOException("Image is unavailable")
            val file = checkedObject(published, id)
            verifyBytes(file, metadata, ::checkCurrent)
            val expected = ValidatedClipboardImage(metadata.mimeType, metadata.width, metadata.height)
            if (ClipboardImageValidator.validate(file, ::checkCurrent) != expected) throw IOException("Image metadata changed")
            checkCurrent()
            ImageSendLeaseStore(checkNotNull(published.parentFile), clock.now, clock.boot).create(id, targetUid)
        }
    }

    private suspend fun <T> withSendLease(
        token: String,
        targetUid: Int,
        block: (ClipboardImagePayload, File) -> T,
    ): T? {
        val clock = leaseClock ?: throw IOException("Image sending is unavailable")
        return store.withExclusiveRecovery { _, published ->
            val coroutine = currentCoroutineContext()
            val lease = ImageSendLeaseStore(checkNotNull(published.parentFile), clock.now, clock.boot).find(token, targetUid)
                ?: return@withExclusiveRecovery null
            val metadata = database.clipboardImagePayloadDao().get(lease.imageId) ?: return@withExclusiveRecovery null
            val file = checkedObject(published, metadata.id)
            verifyBytes(file, metadata) { coroutine.ensureActive() }
            val expected = ValidatedClipboardImage(metadata.mimeType, metadata.width, metadata.height)
            if (ClipboardImageValidator.validate(file) { coroutine.ensureActive() } != expected) throw IOException("Image metadata changed")
            coroutine.ensureActive()
            block(metadata, file)
        }
    }

    suspend fun sendLeaseMetadata(token: String, targetUid: Int): ClipboardImagePayload? =
        withSendLease(token, targetUid) { metadata, _ -> metadata }

    suspend fun openSendLease(token: String, targetUid: Int): ParcelFileDescriptor? {
        var descriptor: ParcelFileDescriptor? = null
        try {
            return withSendLease(token, targetUid) { _, file ->
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { descriptor = it }
            }
        } catch (failure: Throwable) {
            closePreservingFailure(failure) { descriptor?.close() }
            throw failure
        }
    }

    /** Revoke only after the caller has revoked the system URI grant or proved it was never sent. */
    suspend fun revokeSendLease(token: String, targetUid: Int): Boolean {
        val clock = leaseClock ?: throw IOException("Image sending is unavailable")
        return store.withExclusiveRecovery { _, published ->
            ImageSendLeaseStore(checkNotNull(published.parentFile), clock.now, clock.boot).revoke(token, targetUid)
        }
    }

    /** Active leases prevent deleting data that a receiving application may open later. */
    suspend fun deleteReady(id: String, authorize: () -> Unit): Boolean {
        val epoch = PrivacyModeState.observeOrdinaryPersistence() ?: throw PersistencePermitExpiredException()
        authorize()
        val permit = PrivacyModeState.acquirePersistencePermit(epoch) ?: throw PersistencePermitExpiredException()
        try {
            return store.withExclusiveRecovery { staging, published ->
                authorize()
                if (!PrivacyModeState.isOrdinaryEpoch(epoch)) throw PersistencePermitExpiredException()
                val metadata = database.clipboardImagePayloadDao().get(id) ?: return@withExclusiveRecovery false
                val file = checkedObject(published, metadata.id)
                val staged = checkedObject(staging, metadata.id, ".tmp")
                if (activeLeaseExists(checkNotNull(published.parentFile), metadata.id)) throw ImageLeaseInUseException()
                authorize()
                if (!PrivacyModeState.isOrdinaryEpoch(epoch)) throw PersistencePermitExpiredException()
                withContext(NonCancellable) {
                    database.withTransaction {
                        clipboardPersistenceGate.guardTransaction(permit) {
                            check(database.clipboardImagePayloadDao().markPendingForDelete(id) == 1) { "Image changed before deletion" }
                        }
                    }
                    if (file.exists() && !file.delete()) throw IOException("Cannot remove image")
                    if (staged.exists() && !staged.delete()) throw IOException("Cannot remove staged image")
                    database.withTransaction {
                        check(database.clipboardImagePayloadDao().deletePendingForRecovery(id) == 1) { "Image deletion state changed" }
                    }
                }
                true
            }
        } finally {
            PrivacyModeState.releasePersistencePermit(permit)
        }
    }

    suspend fun setPinned(id: String, pinned: Boolean, authorize: () -> Unit): Boolean {
        val epoch = PrivacyModeState.observeOrdinaryPersistence() ?: throw PersistencePermitExpiredException()
        authorize()
        val permit = PrivacyModeState.acquirePersistencePermit(epoch) ?: throw PersistencePermitExpiredException()
        try {
            return store.withExclusiveRecovery { _, _ ->
                authorize()
                if (!PrivacyModeState.isOrdinaryEpoch(epoch)) throw PersistencePermitExpiredException()
                database.withTransaction {
                    clipboardPersistenceGate.guardTransaction(permit) {
                        database.clipboardImagePayloadDao().updateReadyPinned(id, pinned) == 1
                    }
                }
            }
        } finally {
            PrivacyModeState.releasePersistencePermit(permit)
        }
    }

    data class RecoveryResult(
        val removedPending: Int,
        val missingReady: Int,
        val retainedUnreferenced: Int,
        val retainedReadyStaging: Int,
    )

    /** Requires exclusive storage ownership; unknown files or any DB failure cause no deletion. */
    suspend fun recover(): RecoveryResult = store.withExclusiveRecovery { staging, published ->
        fun checkedFiles(directory: File, suffix: String): Map<String, File> {
            if (!directory.isDirectory || directory.canonicalFile != directory.absoluteFile) throw IOException("Unsafe image directory")
            val files = directory.listFiles() ?: throw IOException("Cannot inspect image directory")
            if (files.size > MAX_IMAGE_RECORDS) throw IOException("Image directory exceeds its entry budget")
            return files.associateBy { file ->
                val name = file.name
                val id = if (suffix.isEmpty()) name else {
                    if (!name.endsWith(suffix)) throw IOException("Unknown image file")
                    name.removeSuffix(suffix)
                }
                if (runCatching { UUID.fromString(id).toString() != id }.getOrDefault(true) ||
                    file.canonicalFile != file.absoluteFile || !file.isFile
                ) throw IOException("Unsafe image file")
                id
            }
        }
        val staged = checkedFiles(staging, ".tmp")
        val files = checkedFiles(published, "")
        val rows = database.withTransaction {
            val rows = database.clipboardImagePayloadDao().allForRecovery()
            if (rows.size > MAX_IMAGE_RECORDS) throw IOException("Image history exceeds its record budget")
            for (row in rows) {
                if (runCatching { UUID.fromString(row.id).toString() != row.id }.getOrDefault(true)) {
                    throw IOException("Unsafe image metadata")
                }
            }
            for (row in rows.filterNot { it.ready }) {
                if (activeLeaseExists(checkNotNull(published.parentFile), row.id)) throw ImageLeaseInUseException()
                check(database.clipboardImagePayloadDao().deletePendingForRecovery(row.id) == 1) { "Pending image changed" }
            }
            rows
        }
        for (row in rows.filterNot { it.ready }) {
            files[row.id]?.let { if (!it.delete()) throw IOException("Cannot remove pending image") }
        }
        // A READY row may have lost its published object after an interrupted filesystem
        // operation. Preserve any same-ID staged copy even when a published file exists:
        // it may be the only intact copy, and this phase does not validate either copy.
        val readyIds = rows.filter { it.ready }.map { it.id }.toSet()
        for ((id, file) in staged) {
            if (id !in readyIds && !file.delete()) throw IOException("Cannot remove abandoned image staging")
        }
        val known = rows.map { it.id }.toSet()
        RecoveryResult(
            removedPending = rows.count { !it.ready },
            missingReady = rows.count { it.ready && it.id !in files },
            retainedUnreferenced = files.keys.count { it !in known },
            retainedReadyStaging = staged.keys.count { it in readyIds },
        )
    }
}
