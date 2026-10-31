/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.soundeffect

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.File

/** Small lazy boundary; no engine startup or UI/file IO is performed by initialization. */
internal class OwnedSoundFdAccess<T : Any>(
    private val load: () -> Unit,
    private val openFd: (String, String, String) -> Int,
    private val closeFd: (Int) -> Unit,
    private val adopt: (Int) -> T,
) {
    private val available by lazy {
        try {
            load()
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun open(root: String, folder: String, name: String): T? {
        if (!available) return null
        val fd = try {
            openFd(root, folder, name)
        } catch (_: UnsatisfiedLinkError) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        if (fd < 0) return null
        return try {
            adopt(fd)
        } catch (failure: Throwable) {
            try {
                closeFd(fd)
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }
}

/** Files are opened from a trusted root using directory handles, never a checked pathname. */
@Keep
internal object SoundFileAccess {
    @JvmStatic
    private external fun openNative(root: String, folder: String, name: String): Int

    @JvmStatic
    private external fun closeNative(fd: Int)

    private val access = OwnedSoundFdAccess(
        load = {
            System.loadLibrary("xrime_sound_file")
            // Resolve the cleanup symbol before any fd can be handed to Java.
            closeNative(-1)
        },
        openFd = ::openNative,
        closeFd = ::closeNative,
        adopt = ParcelFileDescriptor::adoptFd,
    )

    /** The caller owns the result and must close it. Null means unavailable or rejected. */
    fun open(root: File, folder: String, name: String): ParcelFileDescriptor? =
        access.open(root.absolutePath, folder, name)
}
