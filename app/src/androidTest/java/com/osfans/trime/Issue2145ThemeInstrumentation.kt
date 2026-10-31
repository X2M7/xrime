/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Build.VERSION
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.theme.ThemeLoader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

/** Loads the original issue attachment through native deployment on an isolated emulator. */
class Issue2145ThemeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        var session: RimeSession? = null
        var source: File? = null
        var artifact: File? = null
        var ownsSource = false
        var ownsArtifact = false
        var failure: Throwable? = null
        fun <T> main(block: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            var outcome: Result<T>? = null
            runOnMainSync { outcome = runCatching(block) }
            return checkNotNull(outcome).getOrThrow()
        }
        try {
            waitForIdleSync()
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
            val external = checkNotNull(targetContext.getExternalFilesDir(null))
            check(File(external, "runtime-audit-dedicated").isFile) { "Requires a dedicated runtime-audit installation" }
            val fixture = File(external, "issue2145-source.yaml")
            check(fixture.isFile && fixture.length() in 1L..1_000_000L) { "Original issue fixture is missing" }
            val id = "issue2145-${UUID.randomUUID().toString().replace("-", "")}.trime"
            source = File(DataManager.userDataDir, "$id.yaml")
            artifact = File(DataManager.stagingDir, "$id.yaml")
            check(!checkNotNull(artifact).exists()) { "Fixture artifact collision" }
            check(checkNotNull(source).createNewFile()) { "Fixture source collision" }
            ownsSource = true
            checkNotNull(source).writeBytes(fixture.readBytes())
            session = main { RimeDaemon.createSession(javaClass.name) }
            runBlocking {
                withTimeout(120_000) { checkNotNull(session).runOnReady { } }
                // The original attachment includes duplicate keys, requiring native fallback.
                check(ThemeLoader.loadFromSource(id) == null) { "Expected the original source to use native fallback" }
                ownsArtifact = true
                val loaded = withTimeout(120_000) { ThemeLoader.loadTheme(id) }
                check(loaded is ThemeLoader.ThemeLoadResult.Success) { "Native theme load failed: $loaded" }
                check(loaded.theme.presetColorSchemes.isNotEmpty())
                check(loaded.theme.presetKeyboards.values.asSequence().flatMap { it.keys.asSequence() }
                    .any { key -> key.hint.any(Char::isHighSurrogate) }) { "Supplementary-plane hint was lost" }
            }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: original issue 2145 theme native fallback retains supplementary-plane hints\n") })
            if (VERSION.SDK_INT >= 29) Issue2145UiProbe.verify(this, id)
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                if (session != null) main { RimeDaemon.destroySession(javaClass.name) }
                if (ownsSource && source?.exists() == true) check(source.delete())
                if (ownsArtifact && artifact?.exists() == true) check(artifact.delete())
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        result.putBoolean("passed", failure == null)
        result.putString("stream", failure?.stackTraceToString() ?: "PASS: original issue 2145 native theme load\n")
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
