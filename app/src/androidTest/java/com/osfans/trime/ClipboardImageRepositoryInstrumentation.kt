/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import com.osfans.trime.data.db.AdmittedClipboardImage
import com.osfans.trime.data.db.ClipboardImagePayload
import com.osfans.trime.data.db.ClipboardImageRepository
import com.osfans.trime.data.db.Database
import com.osfans.trime.data.db.ImageCaptureAuthorization
import com.osfans.trime.data.db.ImageLeaseInUseException
import com.osfans.trime.data.db.ImageSendLeaseClock
import com.osfans.trime.data.db.ImageSendLeaseStore
import com.osfans.trime.data.db.PrivateImageByteStore
import com.osfans.trime.ime.core.PrivacyModeState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/** Uses only a uniquely named test DB and a uniquely named private fixture directory. */
class ClipboardImageRepositoryInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        val context = targetContext
        val root = File(context.cacheDir, "image-repository-test-${UUID.randomUUID()}")
        val name = "image-repository-test-${UUID.randomUUID()}.db"
        var db: Database? = null
        var primaryFailure: Throwable? = null
        try {
            // Instrumentation.onStart can run while Application.onCreate is still initializing
            // the process-wide privacy epoch. Wait for that real initialization to finish.
            waitForIdleSync()
            val initialEpoch = checkNotNull(PrivacyModeState.observeOrdinaryPersistence()) {
                "Fixture requires an ordinary, initialized privacy state"
            }
            Thread.sleep(250)
            check(PrivacyModeState.observeOrdinaryPersistence() == initialEpoch) {
                "Privacy epoch changed while the fixture was settling"
            }
            sendStatus(0, Bundle().apply { putString("stream", "Stable ordinary epoch: $initialEpoch\n") })
            check(root.mkdir())
            db = Room.databaseBuilder(context, Database::class.java, name).build()
            val database = checkNotNull(db)
            val store = PrivateImageByteStore(root)
            var leaseTime = 1000L
            val leaseBoot = 1
            val repository = ClipboardImageRepository(database, store, ImageSendLeaseClock({ leaseTime }, { leaseBoot }))
            runBlocking {
                val auth = checkNotNull(ImageCaptureAuthorization.acquire { true })
                val permit = auth.acquirePersistencePermit()
                try {
                    val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
                    val encoded = ByteArrayOutputStream()
                    try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded)) } finally { bitmap.recycle() }
                    val bytes = encoded.toByteArray()
                    val staged = store.stage(ByteArrayInputStream(bytes), auth::requireAllowed)
                    val stagedAgain = store.stage(ByteArrayInputStream(bytes), auth::requireAllowed)
                    val admitted = AdmittedClipboardImage(Uri.parse("content://fixture/image"), setOf("image/png"), auth)
                    val secondPermit = auth.acquirePersistencePermit()
                    val concurrent = try {
                        coroutineScope {
                            listOf(
                                async { repository.save(staged, admitted, auth, permit) },
                                async { repository.save(stagedAgain, admitted, auth, secondPermit) },
                            ).awaitAll()
                        }
                    } finally {
                        PrivacyModeState.releasePersistencePermit(secondPermit)
                    }
                    check(concurrent[0].id == concurrent[1].id)
                    check(database.clipboardImagePayloadDao().allForRecovery().size == 1)
                    val id = concurrent[0].id
                    check(repository.setPinned(id, true) {})
                    val repeated = store.stage(ByteArrayInputStream(bytes), auth::requireAllowed)
                    val saved = repository.save(repeated, admitted, auth, permit)
                    check(saved.id == id && saved.ready && saved.pinned && database.clipboardImagePayloadDao().get(id) == saved)
                    // Exercise both orders at the real Room publication boundary.
                    val changedBitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
                    val changedOutput = ByteArrayOutputStream()
                    try {
                        changedBitmap.setPixel(0, 0, -0x1000000)
                        check(changedBitmap.compress(Bitmap.CompressFormat.PNG, 100, changedOutput))
                    } finally { changedBitmap.recycle() }
                    val changedBytes = changedOutput.toByteArray()
                    val events = com.osfans.trime.data.db.ClipboardImageEventGeneration()
                    val beforeTicket = events.advance()
                    val beforeAuth = checkNotNull(events.acquireAuthorization(beforeTicket) { true })
                    val beforePermit = beforeAuth.acquirePersistencePermit()
                    try {
                        val beforeStage = store.stage(ByteArrayInputStream(changedBytes), beforeAuth::requireAllowed)
                        val beforeId = beforeStage.id
                        val beforeAdmission = AdmittedClipboardImage(Uri.parse("content://fixture/image"), setOf("image/png"), beforeAuth)
                        val beforeRepository = ClipboardImageRepository(database, store, beforePublicationAccepted = { events.advance() })
                        check(runCatching { beforeRepository.save(beforeStage, beforeAdmission, beforeAuth, beforePermit) }
                            .exceptionOrNull() is com.osfans.trime.data.db.PersistencePermitExpiredException)
                        check(database.clipboardImagePayloadDao().get(beforeId) == null)
                        check(database.clipboardImagePayloadDao().pendingForRecovery().any { it.id == beforeId })
                        beforeRepository.recover()
                        check(database.clipboardImagePayloadDao().pendingForRecovery().isEmpty())
                        check(!File(root, "clipboard-image-published/$beforeId").exists())
                        check(database.clipboardImagePayloadDao().get(saved.id) == saved)
                    } finally {
                        PrivacyModeState.releasePersistencePermit(beforePermit)
                        beforeAuth.close()
                    }
                    val afterTicket = events.advance()
                    val afterAuth = checkNotNull(events.acquireAuthorization(afterTicket) { true })
                    val afterPermit = afterAuth.acquirePersistencePermit()
                    try {
                        val afterStage = store.stage(ByteArrayInputStream(changedBytes), afterAuth::requireAllowed)
                        val afterId = afterStage.id
                        val afterAdmission = AdmittedClipboardImage(Uri.parse("content://fixture/image"), setOf("image/png"), afterAuth)
                        val afterRepository = ClipboardImageRepository(database, store, afterPublicationAccepted = { events.advance() })
                        val accepted = afterRepository.save(afterStage, afterAdmission, afterAuth, afterPermit)
                        check(accepted.id == afterId && accepted.ready && database.clipboardImagePayloadDao().get(afterId) == accepted)
                        check(File(root, "clipboard-image-published/$afterId").isFile)
                        check(afterRepository.deleteReady(afterId) {})
                        check(database.clipboardImagePayloadDao().get(saved.id) == saved)
                    } finally {
                        PrivacyModeState.releasePersistencePermit(afterPermit)
                        afterAuth.close()
                    }
                    val savedFile = File(root, "clipboard-image-published/${saved.id}")
                    check(savedFile.isFile)
                    val original = savedFile.readBytes()
                    savedFile.writeBytes(original.copyOf().apply { this[20] = (this[20].toInt() xor 1).toByte() })
                    val duplicateOfCorrupt = store.stage(ByteArrayInputStream(bytes), auth::requireAllowed)
                    check(runCatching { repository.save(duplicateOfCorrupt, admitted, auth, permit) }.exceptionOrNull() is IOException)
                    check(database.clipboardImagePayloadDao().get(saved.id) == saved && savedFile.readBytes()[20] != original[20])
                    savedFile.writeBytes(original)
                    val pendingId = UUID.randomUUID().toString()
                    val missingId = UUID.randomUUID().toString()
                    val orphanId = UUID.randomUUID().toString()
                    val pending = saved.copy(id = pendingId, sha256 = "pending-hash", ready = false)
                    val missing = saved.copy(id = missingId, sha256 = "missing-hash", ready = true)
                    database.clipboardImagePayloadDao().insert(pending)
                    database.clipboardImagePayloadDao().insert(missing)
                    check(database.clipboardImagePayloadDao().get(pendingId) == null)
                    check(database.clipboardImagePayloadDao().findByHash("pending-hash") == null)
                    check(database.clipboardImagePayloadDao().pendingForRecovery().single() == pending)
                    val page = repository.readyImages().load(androidx.paging.PagingSource.LoadParams.Refresh(null, 16, false))
                        as androidx.paging.PagingSource.LoadResult.Page<Int, ClipboardImagePayload>
                    check(page.data.map { it.id }.toSet() == setOf(saved.id, missing.id))
                    val directory = File(root, "clipboard-image-published")
                    val pendingFile = File(directory, pendingId).apply { writeBytes(byteArrayOf(1)) }
                    val orphanFile = File(directory, orphanId).apply { writeBytes(byteArrayOf(2)) }
                    val abandoned = File(root, "clipboard-image-staging/${UUID.randomUUID()}.tmp").apply { writeBytes(byteArrayOf(3)) }
                    val missingCopy = File(root, "clipboard-image-staging/$missingId.tmp").apply { writeBytes(byteArrayOf(5)) }
                    val existingCopy = File(root, "clipboard-image-staging/${saved.id}.tmp").apply { writeBytes(byteArrayOf(6)) }
                    val unknown = File(directory, "unknown").apply { writeBytes(byteArrayOf(4)) }
                    check(runCatching { repository.recover() }.exceptionOrNull() is IOException)
                    check(database.clipboardImagePayloadDao().pendingForRecovery().single() == pending && pendingFile.isFile && abandoned.isFile)
                    check(unknown.delete())
                    val recovered = repository.recover()
                    check(recovered.removedPending == 1 && recovered.missingReady == 1 && recovered.retainedUnreferenced == 1)
                    check(recovered.retainedReadyStaging == 2 && missingCopy.isFile && existingCopy.isFile)
                    check(database.clipboardImagePayloadDao().pendingForRecovery().isEmpty() && !pendingFile.exists() && !abandoned.exists())
                    check(database.clipboardImagePayloadDao().get(saved.id) == saved && orphanFile.isFile)
                    check(database.clipboardImagePayloadDao().get(missingId) == missing)
                    val thumbnail = checkNotNull(repository.loadThumbnail(saved.id) {})
                    check(thumbnail.width == 8 && thumbnail.height == 6)
                    thumbnail.recycle()
                    savedFile.writeBytes(original.copyOf().apply { this[20] = (this[20].toInt() xor 1).toByte() })
                    check(runCatching { repository.loadThumbnail(saved.id) {} }.exceptionOrNull() is IOException)
                    savedFile.writeBytes(original)
                    val cancelled = java.util.concurrent.CancellationException("fixture cancellation")
                    check(runCatching { repository.loadThumbnail(saved.id) { throw cancelled } }.exceptionOrNull() === cancelled)
                    check(runCatching { repository.deleteReady(saved.id) { throw cancelled } }.exceptionOrNull() === cancelled)
                    check(database.clipboardImagePayloadDao().get(saved.id) == saved && savedFile.isFile)
                    val lease = repository.createSendLease(saved.id, 12345) {}
                    check(repository.sendLeaseMetadata(lease.token, 12345) == saved)
                    check(repository.sendLeaseMetadata(lease.token, 12346) == null)
                    val descriptor = checkNotNull(repository.openSendLease(lease.token, 12345))
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { check(it.readBytes().contentEquals(original)) }
                    val heldDescriptor = checkNotNull(repository.openSendLease(lease.token, 12345))
                    var heldFailure: Throwable? = null
                    try {
                        check(runCatching { repository.deleteReady(saved.id) {} }.exceptionOrNull() is ImageLeaseInUseException)
                        check(savedFile.isFile && database.clipboardImagePayloadDao().get(saved.id) == saved)
                        leaseTime += ImageSendLeaseStore.LEASE_DURATION_MS
                        check(repository.sendLeaseMetadata(lease.token, 12345) == null)
                        check(repository.openSendLease(lease.token, 12345) == null)
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(heldDescriptor).use { check(it.readBytes().contentEquals(original)) }
                    } catch (failure: Throwable) {
                        heldFailure = failure
                        throw failure
                    } finally {
                        com.osfans.trime.data.db.closePreservingFailure(heldFailure) { heldDescriptor.close() }
                    }
                    check(repository.deleteReady(saved.id) {})
                    check(database.clipboardImagePayloadDao().get(saved.id) == null && !savedFile.exists() && !existingCopy.exists())
                    check(repository.deleteReady(missingId) {})
                    check(database.clipboardImagePayloadDao().get(missingId) == null && !missingCopy.exists())
                    check(orphanFile.isFile)
                    database.withTransaction {
                        repeat(com.osfans.trime.data.db.MAX_IMAGE_RECORDS) { index ->
                            database.clipboardImagePayloadDao().insert(
                                saved.copy(id = UUID.randomUUID().toString(), sha256 = "fixture-$index", pinned = true),
                            )
                        }
                    }
                    val atCapacity = store.stage(ByteArrayInputStream(bytes), auth::requireAllowed)
                    check(runCatching { repository.save(atCapacity, admitted, auth, permit) }.exceptionOrNull() is IOException)
                    check(database.clipboardImagePayloadDao().recordCount() == com.osfans.trime.data.db.MAX_IMAGE_RECORDS.toLong())
                    database.clipboardImagePayloadDao().insert(saved.copy(id = UUID.randomUUID().toString(), sha256 = "over-limit"))
                    check(runCatching { repository.recover() }.exceptionOrNull() is IOException)
                    check(database.clipboardImagePayloadDao().recordCount() == com.osfans.trime.data.db.MAX_IMAGE_RECORDS + 1L)
                    check(orphanFile.isFile)
                    database.withTransaction {
                        database.openHelper.writableDatabase.execSQL("DELETE FROM clipboard_image_payload WHERE sha256 = 'over-limit'")
                    }
                    repeat(com.osfans.trime.data.db.MAX_IMAGE_RECORDS) {
                        File(directory, UUID.randomUUID().toString()).writeBytes(byteArrayOf(7))
                    }
                    check(runCatching { repository.recover() }.exceptionOrNull() is IOException)
                    check(directory.listFiles()!!.size == com.osfans.trime.data.db.MAX_IMAGE_RECORDS + 1)
                    check(orphanFile.isFile && database.clipboardImagePayloadDao().recordCount() == com.osfans.trime.data.db.MAX_IMAGE_RECORDS.toLong())
                } finally {
                    PrivacyModeState.releasePersistencePermit(permit)
                    auth.close()
                }
            }
            code = Activity.RESULT_OK
            result.putString("stream", "PASS: Room pending/ready publication and conservative recovery\n")
        } catch (failure: Throwable) {
            primaryFailure = failure
            result.putString("stream", android.util.Log.getStackTraceString(failure))
        } finally {
            val cleanup = runCatching {
                db?.close()
                val dbRemoved = !context.getDatabasePath(name).exists() || context.deleteDatabase(name)
                val filesRemoved = !root.exists() || root.deleteRecursively()
                check(dbRemoved && filesRemoved) { "Unable to remove isolated test fixtures" }
            }.exceptionOrNull()
            if (cleanup != null) {
                code = Activity.RESULT_CANCELED
                val message = if (primaryFailure == null) android.util.Log.getStackTraceString(cleanup)
                else result.getString("stream") + "\nCleanup failure:\n" + android.util.Log.getStackTraceString(cleanup)
                result.putString("stream", message)
            }
        }
        finish(code, result)
    }
}
