/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.Looper
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.theme.ThemeYaml
import com.osfans.trime.util.mapping
import com.osfans.trime.util.pairs
import com.osfans.trime.util.scalar
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

/** Native config compilation and ThemeYaml scalar round-trip on a dedicated emulator. */
class ConfigScalarRoundTripInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        var failure: Throwable? = null
        var source: File? = null
        var deployed: File? = null
        var ownsSource = false
        var ownsDeployed = false
        var session: RimeSession? = null
        fun <T> main(block: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            var result: Result<T>? = null
            runOnMainSync { result = runCatching(block) }
            return checkNotNull(result).getOrThrow()
        }
        try {
            waitForIdleSync()
            check(Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(File(checkNotNull(targetContext.getExternalFilesDir(null)), "runtime-audit-dedicated").isFile)
            val id = "scalar-roundtrip-${UUID.randomUUID().toString().replace("-", "")}.trime"
            source = File(DataManager.userDataDir, "$id.yaml")
            deployed = File(DataManager.resolveDeployedResourcePath(id))
            check(!checkNotNull(deployed).exists() && checkNotNull(source).createNewFile())
            ownsSource = true
            ownsDeployed = true
            val values = linkedMapOf(
                "empty" to "",
                "no_final_lf" to "```\n\n```",
                "one_final_lf" to "one\n",
                "multiple_final_lf" to "two\n\n\n",
                "only_newlines" to "\n\n",
                "leading_whitespace" to "  first\n second",
                "crlf" to "first\r\nsecond\r\n",
                "lone_cr" to "first\rsecond",
                "escapes" to "quote\" slash\\ tab\t\nline",
                "supplementary" to "hint 😀\nend",
                "successor" to "after-all-values",
            )
            fun quote(value: String): String = buildString {
                append('"')
                for (char in value) when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
                append('"')
            }
            val document = buildString {
                append("config_version: '1'\nvalues:\n")
                values.forEach { (key, value) -> append("  $key: ${quote(value)}\n") }
            }
            checkNotNull(source).writeText(document)
            fun decode(text: String): Map<String, String> {
                val root = checkNotNull(ThemeYaml.parser.parseToYamlNode(text).mapping)
                val map = checkNotNull(root.pairs["values"]?.mapping)
                return values.keys.associateWith { key -> checkNotNull(map.pairs[key]?.scalar).content }
            }
            check(decode(document) == values) { "Source fixture did not decode as intended" }
            session = main { RimeDaemon.createSession(javaClass.name) }
            runBlocking {
                withTimeout(120_000) { checkNotNull(session).runOnReady { check(deployConfigFile(id, "config_version")) } }
            }
            val artifact = checkNotNull(deployed)
            check(artifact.isFile) { "Native deployment did not produce $artifact" }
            val actual = decode(artifact.readText())
            val differences = values.keys.filter { actual[it] != values[it] }
            if (differences.isNotEmpty()) {
                val detail = differences.joinToString { key ->
                    "$key expected=${values.getValue(key).map { "U+%04X".format(it.code) }} actual=${actual.getValue(key).map { "U+%04X".format(it.code) }}"
                }
                error("Native deploy / ThemeYaml scalar mismatch: $detail")
            }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: native deployment and ThemeYaml preserve scalar values and following key\n") })
        } catch (error: Throwable) { failure = error }
        finally {
            fun clean(block: () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (failure == null) failure = error else checkNotNull(failure).addSuppressed(error)
                }
            }
            clean { if (session != null) main { RimeDaemon.destroySession(javaClass.name) } }
            clean { source?.let { if (ownsSource && it.exists()) check(it.delete()) } }
            clean { deployed?.let { if (ownsDeployed && it.exists()) check(it.delete()) } }
        }
        val result = Bundle().apply {
            putBoolean("passed", failure == null)
            putString("stream", failure?.stackTraceToString() ?: "PASS: config scalar round trip\n")
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
