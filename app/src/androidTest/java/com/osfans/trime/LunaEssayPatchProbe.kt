// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.osfans.trime.core.data.DataChecksums
import com.osfans.trime.core.data.DataDiff
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.DataResourceInstaller
import com.osfans.trime.core.data.LunaEssayPatch
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Runs only in a dedicated emulator; removes only its own temporary vocabulary. */
internal object LunaEssayPatchProbe {
    fun run(instrumentation: Instrumentation) {
        val result = Bundle()
        var checks = 0
        fun phase(message: String) {
            checks++
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS $checks: $message\n") })
        }
        val outcome = runCatching {
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
            val context = instrumentation.targetContext
            val assets = context.assets
            val json = assets.open("checksums.json").bufferedReader().use { JSONObject(it.readText()) }
            val hash = json.getJSONObject("files").getString(LunaEssayPatch.TARGET)
            check(runCatching { assets.open(LunaEssayPatch.TARGET).close() }.isFailure) { "Derived vocabulary was still packaged" }
            val patch = assets.open(LunaEssayPatch.PATCH).use { it.readBytes() }
            check(patch.isNotEmpty())
            val root = File(context.cacheDir, "xrime-luna-probe-${UUID.randomUUID()}")
            check(root.mkdir())
            val destination = File(root, "luna_pinyin_essay.txt")
            fun digest(file: File): String = file.inputStream().use { input ->
                val md = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    md.update(buffer, 0, count)
                }
                md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }
            fun rebuild(file: File, bytes: ByteArray = patch) {
                assets.open(LunaEssayPatch.SOURCE).use { source ->
                    LunaEssayPatch.reconstructToFile(source, ByteArrayInputStream(bytes), file, hash)
                }
            }
            try {
                val old = DataChecksums("old", mapOf(LunaEssayPatch.TARGET to "0".repeat(64)))
                val current = DataChecksums("current", mapOf(LunaEssayPatch.TARGET to hash))
                destination.writeText("previous test content")
                var published = false
                val update = DataDiff.diff(old, current) { destination.isFile }
                check(update == listOf(DataDiff.UpdateFile(LunaEssayPatch.TARGET)))
                val start = SystemClock.elapsedRealtime()
                DataResourceInstaller.install(root, update, { _, file -> rebuild(file) }, { published = true })
                val elapsed = SystemClock.elapsedRealtime() - start
                check(published && digest(destination) == hash)
                result.putLong("upgrade_reconstruction_ms", elapsed)
                result.putLong("vocabulary_bytes", destination.length())
                result.putInt("patch_bytes", patch.size)
                phase("old vocabulary atomically replaced with exact checksum")

                check(destination.delete())
                val repair = DataDiff.diff(current, current) { destination.isFile }
                check(repair == listOf(DataDiff.CreateFile(LunaEssayPatch.TARGET)))
                DataResourceInstaller.install(root, repair, { _, file -> rebuild(file) }, {})
                check(digest(destination) == hash)
                phase("missing logical vocabulary rebuilt offline")

                val before = digest(destination)
                check(runCatching { rebuild(destination, patch.copyOf(patch.size - 1)) }.isFailure)
                check(digest(destination) == before)
                check(root.listFiles()?.map { it.name } == listOf(destination.name))
                phase("truncated patch preserves previous file and removes temporary file")

                DataManager.sync()
                check(digest(File(DataManager.sharedDataDir, "luna_pinyin_essay.txt")) == hash)
                phase("real shared-data installation matches logical checksum")
            } finally {
                check(!destination.exists() || destination.delete()) { "Could not remove owned vocabulary fixture" }
                check(root.delete()) { "Unexpected files left in owned vocabulary fixture" }
            }
        }
        result.putBoolean("passed", outcome.isSuccess)
        result.putInt("checks", checks)
        result.putString("stream", outcome.exceptionOrNull()?.stackTraceToString() ?: "PASS: derived vocabulary offline upgrade and repair")
        instrumentation.finish(if (outcome.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
