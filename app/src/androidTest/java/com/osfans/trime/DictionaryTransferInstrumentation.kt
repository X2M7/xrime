/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.osfans.trime.data.userdict.DictionaryDocumentStager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Cross-UID provider transfer without relying on the system clipboard's grant behavior. */
class DictionaryTransferInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val context = targetContext
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var primary: Throwable? = null
        val fixture = Uri.parse("content://com.x2m7.xrime.test.imagefixture/image")
        try {
            waitForIdleSync()
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
            check(File(context.getExternalFilesDir(null), "runtime-audit-dedicated").isFile) { "Dedicated emulator required" }
            val info = checkNotNull(context.packageManager.resolveContentProvider(checkNotNull(fixture.authority), 0))
            check(checkNotNull(info.applicationInfo).uid != android.os.Process.myUid()) { "Provider must use another UID" }
            context.contentResolver.call(fixture, "grant", null, null)
            runBlocking {
                val callbacks = AtomicInteger()
                val reads = checkNotNull(context.contentResolver.call(fixture, "openCount", null, null)).getInt("count")
                context.contentResolver.call(fixture, "slow", null, null)
                fun read() = async(start = CoroutineStart.UNDISPATCHED) {
                    DictionaryDocumentStager.read(context, fixture, 1024 * 1024) { callbacks.incrementAndGet() }
                }
                val firstRead = read()
                val secondRead = read()
                try {
                    check(runCatching { DictionaryDocumentStager.read(context, fixture, 1024) { callbacks.incrementAndGet() } }
                        .exceptionOrNull() is IOException)
                    withTimeout(5_000) {
                        while (checkNotNull(context.contentResolver.call(fixture, "openCount", null, null)).getInt("count") < reads + 2) delay(20)
                    }
                } finally { firstRead.cancel(); secondRead.cancel() }
                withTimeout(8_000) { firstRead.join(); secondRead.join() }
                check(callbacks.get() == 0)
                context.contentResolver.call(fixture, "fast", null, null)
                check(DictionaryDocumentStager.read(context, fixture, 1024 * 1024) { it.readBytes().isNotEmpty() })

                val writes = checkNotNull(context.contentResolver.call(fixture, "writeCount", null, null)).getInt("count")
                check(runCatching { DictionaryDocumentStager.write(context, fixture, 1024) { it.writeBytes(ByteArray(1025)) } }
                    .exceptionOrNull() is IOException)
                check(checkNotNull(context.contentResolver.call(fixture, "writeCount", null, null)).getInt("count") == writes)
                context.contentResolver.call(fixture, "slowWrite", null, null)
                val generated = AtomicInteger()
                fun write() = async(start = CoroutineStart.UNDISPATCHED) {
                    DictionaryDocumentStager.write(context, fixture, 1024 * 1024) { file ->
                        file.writeBytes(ByteArray(512 * 1024))
                        generated.incrementAndGet()
                    }
                }
                val firstWrite = write()
                val secondWrite = write()
                try {
                    withTimeout(5_000) {
                        while (checkNotNull(context.contentResolver.call(fixture, "writeCount", null, null)).getInt("count") < writes + 2) delay(20)
                    }
                    check(generated.get() == 2)
                    check(runCatching { DictionaryDocumentStager.write(context, fixture, 1024) { it.writeBytes(byteArrayOf(1)) } }
                        .exceptionOrNull() is IOException)
                } finally { firstWrite.cancel(); secondWrite.cancel() }
                withTimeout(8_000) { firstWrite.join(); secondWrite.join() }
                context.contentResolver.call(fixture, "fastWrite", null, null)
                DictionaryDocumentStager.write(context, fixture, 1024) { it.writeBytes(byteArrayOf(1, 2, 3)) }
                check(File(context.cacheDir, "dictionary-import-staging").listFiles()!!.none { it.name.endsWith(".tmp") })
            }
            code = Activity.RESULT_OK
            result.putString("stream", "PASS: cross-UID bounded read/write, saturation, cancellation and recovery\n")
        } catch (failure: Throwable) {
            primary = failure
            result.putString("stream", android.util.Log.getStackTraceString(failure))
        } finally {
            for (method in listOf("fast", "fastWrite", "revoke")) {
                try {
                    context.contentResolver.call(fixture, method, null, null)
                } catch (failure: Throwable) {
                    code = Activity.RESULT_CANCELED
                    if (primary == null) primary = failure else primary.addSuppressed(failure)
                }
            }
            if (code != Activity.RESULT_OK && primary != null) result.putString("stream", android.util.Log.getStackTraceString(primary))
        }
        finish(code, result)
    }
}
