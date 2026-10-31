/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.system.Os
import android.system.ErrnoException
import android.system.OsConstants
import com.osfans.trime.data.soundeffect.SoundFileAccess
import java.io.File
import java.util.UUID

/** Dedicated unique app-private fixture; never clears an existing directory. */
class SoundFileAccessInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var failure: Throwable? = null
        var created = false
        val base = File(targetContext.cacheDir, "sound-open-${UUID.randomUUID()}")
        try {
            check(base.mkdir()) { "Cannot create unique sound fixture" }
            created = true
            val root = File(base, "soundeffect")
            val nested = File(root, "音效/层")
            check(nested.mkdirs())
            val audio = File(nested, "敲😀.wav")
            audio.writeBytes(byteArrayOf(1, 2, 3))
            File(root, "root.wav").writeBytes(byteArrayOf(7))
            for (folder in listOf("", ".")) {
                val handle = checkNotNull(SoundFileAccess.open(root, folder, "root.wav"))
                val descriptor = handle.fileDescriptor
                handle.use { check(descriptor.valid()) }
                check(!descriptor.valid())
            }
            val handle = checkNotNull(SoundFileAccess.open(root, "音效/层", audio.name))
            val descriptor = handle.fileDescriptor
            handle.use {
                val bytes = ByteArray(3)
                check(Os.read(descriptor, bytes, 0, bytes.size) == 3)
                check(bytes.contentEquals(byteArrayOf(1, 2, 3)))
            }
            check(!descriptor.valid())
            val outside = File(base, "outside.wav")
            outside.writeBytes(byteArrayOf(9))
            Os.symlink(outside.absolutePath, File(nested, "link.wav").absolutePath)
            Os.symlink(base.absolutePath, File(root, "linkdir").absolutePath)
            Os.mkfifo(File(nested, "pipe.wav").absolutePath, 0x180) // 0600
            check(SoundFileAccess.open(root, "音效/层", "link.wav") == null)
            check(SoundFileAccess.open(root, "linkdir", "outside.wav") == null)
            check(SoundFileAccess.open(root, "音效/层", "pipe.wav") == null)
            check(SoundFileAccess.open(root, "音效/层", "../../outside.wav") == null)
            check(SoundFileAccess.open(root, "音效/层", "/etc/passwd") == null)
            check(SoundFileAccess.open(root, "音效/层", "bad\u0000.wav") == null)
            check(SoundFileAccess.open(root, "音效/层", "missing.wav") == null)
        } catch (t: Throwable) {
            failure = t
        } finally {
            try {
                if (created) {
                    // Unlink fixture symlinks before recursive cleanup; never follow them.
                    for (link in listOf(File(base, "soundeffect/音效/层/link.wav"), File(base, "soundeffect/linkdir"))) {
                        try {
                            // POSIX remove unlinks a symlink itself, without traversing it.
                            Os.remove(link.absolutePath)
                        } catch (missing: ErrnoException) {
                            if (missing.errno != OsConstants.ENOENT) throw missing
                        }
                    }
                    check(base.deleteRecursively()) { "Unable to remove unique sound fixture" }
                }
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
        result.putBoolean("passed", failure == null)
        result.putString("stream", if (failure == null) "PASS: native sound file boundary and fd ownership\n" else "FAIL: ${failure.stackTraceToString()}\n")
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
