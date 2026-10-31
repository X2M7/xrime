/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

internal class AdmittedClipboardImage(
    val uri: Uri,
    private val allowedMimeTypes: Set<String>,
    private val authorization: ImageCaptureAuthorization,
) {
    /** The caller must use this during staged-file publication. */
    fun validate(file: File): ValidatedClipboardImage {
        val image = ClipboardImageValidator.validate(file, authorization::requireAllowed)
        if (image.mimeType !in allowedMimeTypes) throw IOException("Image format disagrees with clipboard description")
        return image
    }
}

/** Admission only: never opens the provider, requests a permission, or persists the URI. */
internal object ClipboardImageUriAdmission {
    private val authorityPattern = Regex("[A-Za-z0-9._-]{1,255}")
    private const val MAX_URI_LENGTH = 8192

    private data class ParsedClip(val uri: Uri, val allowedMimeTypes: Set<String>)

    /** Only untrusted ClipData extraction is caught; cancellation and privacy loss still escape. */
    private fun parse(clip: ClipData): ParsedClip? {
        return try {
            if (clip.itemCount != 1) return null
            val description = clip.description ?: return null
            if (description.mimeTypeCount !in 1..32) return null
            val declared = (0 until description.mimeTypeCount).map {
                val mime: String? = description.getMimeType(it)
                mime
            }
            if (declared.any { it == null || it.length > 128 }) return null
            val allowed = listOf("image/png", "image/jpeg").filter { actual ->
                declared.any { filter -> filter != null && ClipDescription.compareMimeTypes(actual, filter) }
            }.toSet()
            if (allowed.isEmpty()) return null
            val item = clip.getItemAt(0) ?: return null
            if (item.intent != null) return null
            val uri = item.uri ?: return null
            if (uri.scheme != "content" || uri.toString().length > MAX_URI_LENGTH || uri.fragment != null) return null
            val authority = uri.authority ?: return null
            if (!authorityPattern.matches(authority) || uri.encodedAuthority != authority) return null
            ParsedClip(uri, allowed)
        } catch (failure: RuntimeException) {
            if (failure is CancellationException || failure is PersistencePermitExpiredException) throw failure
            null
        }
    }

    /** [authorization] must have been acquired before obtaining [clip] from the system. */
    fun admit(context: Context, clip: ClipData, authorization: ImageCaptureAuthorization): AdmittedClipboardImage? {
        authorization.requireAllowed()
        val parsed = parse(clip) ?: return null
        val uri = parsed.uri
        val authority = checkNotNull(uri.authority)
        if (authority == "${context.packageName}.provider" || authority == "${context.packageName}.fileprovider") return null
        val provider = try {
            @Suppress("DEPRECATION")
            context.packageManager.resolveContentProvider(authority, 0)
        } catch (_: SecurityException) {
            null
        } ?: return null
        val owner = provider.applicationInfo ?: return null
        if (owner.uid == Process.myUid()) return null
        authorization.requireAllowed()
        val granted = context.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
        authorization.requireAllowed()
        return if (granted) AdmittedClipboardImage(uri, parsed.allowedMimeTypes, authorization) else null
    }
}
