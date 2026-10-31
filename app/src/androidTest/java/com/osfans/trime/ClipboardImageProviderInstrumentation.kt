/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.osfans.trime.data.db.AdmittedClipboardImage
import com.osfans.trime.data.db.ClipboardImageHistory
import com.osfans.trime.data.db.ImageCaptureAuthorization
import com.osfans.trime.data.db.ImageLeaseInUseException
import com.osfans.trime.data.db.PrivateImageByteStore
import com.osfans.trime.ime.core.PrivacyModeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Uses one synthetic, uniquely identified image and a separate test-package UID. */
class ClipboardImageProviderInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val context = targetContext
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var failure: Throwable? = null
        try {
            waitForIdleSync()
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
            check(File(context.getExternalFilesDir(null), "runtime-audit-dedicated").isFile) { "Dedicated emulator required" }
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) { "Image sending requires Android 7.1" }
            val fixture = Uri.parse("content://com.x2m7.xrime.test.imagefixture/image")
            val fixtureInfo = checkNotNull(context.packageManager.resolveContentProvider(checkNotNull(fixture.authority), 0))
            val fixtureUid = checkNotNull(fixtureInfo.applicationInfo).uid
            check(fixtureUid != Process.myUid()) { "Fixture must run under a separate UID" }
            runBlocking {
                val repository = withTimeout(10_000) {
                    while (ClipboardImageHistory.repositoryOrNull() == null) delay(50)
                    checkNotNull(ClipboardImageHistory.repositoryOrNull())
                }
                val epoch = checkNotNull(PrivacyModeState.observeOrdinaryPersistence())
                delay(250)
                check(PrivacyModeState.observeOrdinaryPersistence() == epoch)
                val authorization = checkNotNull(ImageCaptureAuthorization.acquire { true })
                val permit = authorization.acquirePersistencePermit()
                var ownedImage: String? = null
                val leases = mutableListOf<Pair<String, Int>>()
                var primary: Throwable? = null
                try {
                    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                    val random = java.util.Random(UUID.randomUUID().mostSignificantBits)
                    for (y in 0 until 32) for (x in 0 until 32) bitmap.setPixel(x, y, random.nextInt() or -0x1000000)
                    val output = ByteArrayOutputStream()
                    try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) } finally { bitmap.recycle() }
                    val bytes = output.toByteArray()
                    val root = File(context.noBackupFilesDir.canonicalFile, "clipboard-image-history")
                    val staged = PrivateImageByteStore(root).stage(ByteArrayInputStream(bytes), authorization::requireAllowed)
                    val stagedId = staged.id
                    val admitted = AdmittedClipboardImage(fixture, setOf("image/png"), authorization)
                    val saved = repository.save(staged, admitted, authorization, permit)
                    check(saved.id == stagedId) { "Synthetic image unexpectedly matched an existing image" }
                    ownedImage = saved.id
                    val lease = repository.createSendLease(saved.id, fixtureUid) {}
                    leases.add(lease.token to fixtureUid)
                    val uri = Uri.parse("content://${context.packageName}.clipboard-image/send/${lease.token}")
                    fun probe(method: String, target: Uri = uri): Bundle =
                        checkNotNull(context.contentResolver.call(fixture, method, target.toString(), null))
                    val ungranted = probe("probeRead").getString("status")
                    check(ungranted != "OK") { "Unleased OS access was unexpectedly allowed" }
                    context.grantUriPermission("com.x2m7.xrime.test", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val read = probe("probeRead")
                    check(read.getString("status") == "OK" && read.getInt("bytes") == bytes.size) {
                        "Granted external read failed: status=${read.getString("status")}"
                    }
                    check(probe("probeType").getString("status") == "image/png") { "Granted MIME lookup failed" }
                    check(probe("probeWrite").getString("status") != "OK") { "External write was allowed" }
                    check(probe("holdRead").getString("status") == "OK") { "Receiver could not hold an authorized descriptor" }
                    check(runCatching { repository.deleteReady(saved.id) {} }.exceptionOrNull() is ImageLeaseInUseException)
                    val wrongUidLease = repository.createSendLease(saved.id, Process.myUid()) {}
                    leases.add(wrongUidLease.token to Process.myUid())
                    val wrongUidUri = Uri.parse("content://${context.packageName}.clipboard-image/send/${wrongUidLease.token}")
                    context.grantUriPermission("com.x2m7.xrime.test", wrongUidUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    check(probe("probeRead", wrongUidUri).getString("status") != "OK") { "Wrong lease UID was allowed" }
                    context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    check(probe("probeRead").getString("status") != "OK") { "Revoked OS grant remained usable" }
                    val held = probe("readHeld")
                    check(held.getString("status") == "OK" && held.getInt("bytes") == bytes.size) {
                        "Already opened descriptor did not survive grant revocation"
                    }
                    sendStatus(0, Bundle().apply {
                        putString("stream", "External provider fixture: targetUid=${Process.myUid()} receiverUid=$fixtureUid " +
                            "ungranted=$ungranted grantedRead=OK writeDenied=true wrongUidDenied=true revokedDenied=true\n")
                    })
                } catch (t: Throwable) {
                    primary = t
                    throw t
                } finally {
                    var cleanupFailure: Throwable? = null
                    suspend fun cleanup(action: suspend () -> Unit) {
                        try { action() } catch (cleanup: Throwable) {
                            val first = cleanupFailure
                            if (first == null) cleanupFailure = cleanup else if (first !== cleanup) first.addSuppressed(cleanup)
                        }
                    }
                    cleanup { context.contentResolver.call(fixture, "closeHeld", null, null) }
                    for ((token, uid) in leases) {
                        cleanup {
                            val uri = Uri.parse("content://${context.packageName}.clipboard-image/send/$token")
                            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            check(repository.revokeSendLease(token, uid))
                        }
                    }
                    cleanup { ownedImage?.let { check(repository.deleteReady(it) {}) } }
                    PrivacyModeState.releasePersistencePermit(permit)
                    authorization.close()
                    cleanupFailure?.let { if (primary != null) primary.addSuppressed(it) else throw it }
                }
            }
            code = Activity.RESULT_OK
            result.putString("stream", "External image provider authorization and cleanup passed\n")
        } catch (t: Throwable) {
            failure = t
        }
        failure?.let { result.putString("stream", android.util.Log.getStackTraceString(it)) }
        finish(code, result)
    }
}
