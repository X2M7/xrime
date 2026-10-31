// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.data.opencc.DictionaryConversionCache
import com.osfans.trime.data.opencc.OpenCCDictManager
import java.io.File
import java.io.IOException
import java.util.UUID

/** Verifies real OpenCC and same-directory publication on dedicated emulator storage. */
internal object OpenCCAtomicProbe {
    fun run(instrumentation: Instrumentation) {
        val result = Bundle()
        var checks = 0
        fun phase(name: String) {
            checks++
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS $checks: $name\n") })
        }
        val outcome = runCatching {
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
            val marker = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "runtime-audit-dedicated")
            check(marker.isFile && marker.length() == 0L) { "Dedicated marker required" }
            for ((label, parent) in listOf("private" to instrumentation.targetContext.cacheDir, "user-data" to DataManager.userDataDir)) {
                val root = File(parent, ".xrime-opencc-probe-${UUID.randomUUID()}")
                check(root.mkdir())
                val source = File(root, "fixture.txt")
                val output = File(root, "fixture.ocd2")
                val roundTrip = File(root, "fixture-roundtrip.txt")
                val cache = DictionaryConversionCache()
                fun convert() = cache.convert(source, output) { input, destination ->
                    OpenCCDictManager.openCCDictConv(input.absolutePath, destination.absolutePath, OpenCCDictManager.MODE_TXT_TO_BIN)
                }
                try {
                    source.writeText("abc\told\n")
                    check(convert() && output.length() > 0)
                    val first = output.readBytes()
                    val timestamp = source.lastModified()
                    source.writeText("abc\tnew\n")
                    check(source.setLastModified(timestamp))
                    check(convert() && !output.readBytes().contentEquals(first))
                    OpenCCDictManager.openCCDictConv(output.absolutePath, roundTrip.absolutePath, OpenCCDictManager.MODE_BIN_TO_TXT)
                    check(roundTrip.readText().contains("abc\tnew"))
                    phase("$label actual conversion atomically replaces existing output")
                    val good = output.readBytes()
                    source.writeText("abc\tlater\n")
                    val error = runCatching {
                        cache.convert(source, output) { _, temporary ->
                            temporary.writeText("partial")
                            throw IOException("injected conversion failure")
                        }
                    }.exceptionOrNull()
                    check(error is IOException && output.readBytes().contentEquals(good))
                    check(convert())
                    val retried = output.readBytes()
                    check(!retried.contentEquals(good))
                    check(!cache.convert(source, output) { _, _ -> error("Unchanged content was rebuilt") })
                    check(root.listFiles()?.map { it.name }?.toSet() == setOf(source.name, output.name, roundTrip.name))
                    phase("$label failure retains old bytes and successful retry is cached")
                } finally {
                    listOf(source, output, roundTrip).forEach { check(!it.exists() || it.delete()) }
                    check(root.delete()) { "Unexpected files left in owned OpenCC fixture" }
                }
            }
        }
        result.putBoolean("passed", outcome.isSuccess)
        result.putInt("checks", checks)
        result.putString("stream", outcome.exceptionOrNull()?.stackTraceToString() ?: "PASS: real OpenCC replacement and failure recovery")
        instrumentation.finish(if (outcome.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
