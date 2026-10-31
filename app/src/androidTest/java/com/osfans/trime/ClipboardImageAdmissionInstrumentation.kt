/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Process
import com.osfans.trime.data.db.ClipboardImageUriAdmission
import com.osfans.trime.data.db.ClipboardImageValidator
import com.osfans.trime.data.db.ImageCaptureAuthorization
import java.io.File
import java.io.IOException
import java.util.UUID

/** Tests only generated fixtures and a dedicated provider in the separately installed test APK. */
class ClipboardImageAdmissionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        val context = targetContext
        val root = File(context.cacheDir, "image-admission-test-${UUID.randomUUID()}")
        val source = Uri.parse("content://com.x2m7.xrime.test.imagefixture/image")
        try {
            check(root.mkdir())
            // Opening this test-only control endpoint establishes fixture package visibility;
            // it does not grant the image URI or open its content.
            context.contentResolver.call(source, "revoke", null, null)
            val fixtureProvider = checkNotNull(context.packageManager.resolveContentProvider(source.authority!!, 0))
            val owner = fixtureProvider.applicationInfo
            check(owner.uid != Process.myUid()) { "Fixture provider must have a different UID" }
            val auth = checkNotNull(ImageCaptureAuthorization.acquire { true })
            try {
                fun clip(uri: Uri) = ClipData(ClipDescription("image fixture", arrayOf("image/png")), ClipData.Item(uri))
                fun admit(value: ClipData) = ClipboardImageUriAdmission.admit(context, value, auth)
                context.contentResolver.call(source, "revoke", null, null)
                check(context.checkUriPermission(source, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_DENIED)
                check(admit(clip(source)) == null)
                context.contentResolver.call(source, "grant", null, null)
                val granted = context.checkUriPermission(source, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val description = clip(source).description
                sendStatus(0, Bundle().apply {
                    putString("stream", "Fixture diagnostics: targetUid=${Process.myUid()} providerUid=${owner.uid} " +
                        "explicitGrant=$granted providerReadPermission=${fixtureProvider.readPermission} " +
                        "generalReadPermission=${context.checkPermission(fixtureProvider.readPermission, Process.myPid(), Process.myUid())} " +
                        "mimeCount=${description.mimeTypeCount} " +
                        "mimeMatches=${ClipDescription.compareMimeTypes("image/png", description.getMimeType(0))} " +
                        "scheme=${source.scheme} authorityUnencoded=${source.authority == source.encodedAuthority}\n")
                })
                check(granted == PackageManager.PERMISSION_GRANTED) { "Provider read grant was not visible to target UID" }
                val admitted = checkNotNull(admit(clip(source))) { "Granted external URI was not admitted" }
                check(admitted.uri == source)
                val wildcard = ClipData(ClipDescription("wildcard", arrayOf("image/*")), ClipData.Item(source))
                check(admit(wildcard)?.uri == source)
                val plain = ClipData(ClipDescription("text", arrayOf("text/plain")), ClipData.Item(source))
                check(admit(plain) == null)
                check(admit(ClipboardImageFixtureProvider.malformedClip(source)) == null)
                val tooMany = ClipData(ClipDescription("many", Array(33) { "image/png" }), ClipData.Item(source))
                check(admit(tooMany) == null)
                val tooLong = ClipData(ClipDescription("long", arrayOf("x".repeat(129))), ClipData.Item(source))
                check(admit(tooLong) == null)
                fun throwingClip(failure: RuntimeException) = object : ClipData(
                    ClipDescription("bad", arrayOf("image/png")), ClipData.Item(source),
                ) {
                    override fun getDescription(): ClipDescription = throw failure
                }
                check(admit(throwingClip(android.os.BadParcelableException("invalid fixture"))) == null)
                val cancelled = java.util.concurrent.CancellationException("cancelled")
                check(runCatching { admit(throwingClip(cancelled)) }.exceptionOrNull() === cancelled)
                val expired = com.osfans.trime.data.db.PersistencePermitExpiredException()
                check(runCatching { admit(throwingClip(expired)) }.exceptionOrNull() === expired)
                val multiple = clip(source).apply { addItem(ClipData.Item(source)) }
                check(admit(multiple) == null)
                check(admit(clip(Uri.parse("content://${context.packageName}.fileprovider/root-path/etc/passwd"))) == null)
                check(admit(clip(Uri.parse("file:///data/local/tmp/image"))) == null)
                check(admit(clip(Uri.parse("content://0@com.x2m7.xrime.test.imagefixture/image"))) == null)
                val intent = ClipData(ClipDescription("image fixture", arrayOf("image/png")), ClipData.Item(null, null, Intent("example"), source))
                check(admit(intent) == null)
                context.contentResolver.call(source, "revoke", null, null)
                check(admit(clip(source)) == null)
                val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
                try {
                    for ((format, mime) in listOf(Bitmap.CompressFormat.PNG to "image/png", Bitmap.CompressFormat.JPEG to "image/jpeg")) {
                        val file = File(root, "sample")
                        file.outputStream().use { check(bitmap.compress(format, 90, it)) }
                        val decoded = ClipboardImageValidator.validate(file, auth::requireAllowed)
                        check(decoded.mimeType == mime && decoded.width == 8 && decoded.height == 6)
                        if (mime == "image/png") {
                            check(admitted.validate(file) == decoded)
                            val bytes = file.readBytes()
                            val corrupt = bytes.copyOf().apply { this[18] = (this[18].toInt() xor 1).toByte() }
                            val corruptFile = File(root, "corrupt-png").apply { writeBytes(corrupt) }
                            check(runCatching { ClipboardImageValidator.validate(corruptFile, auth::requireAllowed) }.exceptionOrNull() is IOException)
                            val oversized = bytes.copyOf()
                            java.nio.ByteBuffer.wrap(oversized).putInt(16, 16_385)
                            val crc = java.util.zip.CRC32().apply { update(oversized, 12, 17) }
                            java.nio.ByteBuffer.wrap(oversized).putInt(29, crc.value.toInt())
                            val oversizedFile = File(root, "oversized-png").apply { writeBytes(oversized) }
                            check(runCatching { ClipboardImageValidator.validate(oversizedFile, auth::requireAllowed) }.exceptionOrNull() is IOException)
                        } else {
                            check(runCatching { admitted.validate(file) }.exceptionOrNull() is IOException)
                            val truncated = file.readBytes().dropLast(2).toByteArray()
                            val truncatedFile = File(root, "truncated-jpeg").apply { writeBytes(truncated) }
                            check(runCatching { ClipboardImageValidator.validate(truncatedFile, auth::requireAllowed) }.exceptionOrNull() is IOException)
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
                val invalid = File(root, "invalid").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)) }
                check(runCatching { ClipboardImageValidator.validate(invalid, auth::requireAllowed) }.exceptionOrNull() is IOException)
                auth.close()
                check(runCatching { admit(clip(source)) }.isFailure)
            } finally {
                auth.close()
                context.contentResolver.call(source, "revoke", null, null)
            }
            code = Activity.RESULT_OK
            result.putString("stream", "PASS: foreign UID grant admission, rejection cases and bounded PNG/JPEG decode\n")
        } catch (failure: Throwable) {
            result.putString("stream", android.util.Log.getStackTraceString(failure))
        } finally {
            if (root.exists() && !root.deleteRecursively()) {
                code = Activity.RESULT_CANCELED
                result.putString("stream", "Unable to remove isolated fixture directory")
            }
        }
        finish(code, result)
    }
}
