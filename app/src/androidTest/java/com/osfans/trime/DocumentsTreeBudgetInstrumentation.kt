/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.system.Os
import com.osfans.trime.provider.DocumentsTreeBudget
import com.osfans.trime.provider.RimeDataProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Provider operations are restricted to uniquely named fixtures on dedicated emulators. */
class DocumentsTreeBudgetInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val context = targetContext
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        val external = context.getExternalFilesDir(null)
        val root = File(external, "documents-budget-${UUID.randomUUID()}")
        val outside = File(context.cacheDir, "documents-outside-${UUID.randomUUID()}")
        val privateLinked = File(context.cacheDir, "documents-link-${UUID.randomUUID()}")
        var primary: Throwable? = null
        try {
            waitForIdleSync()
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
            check(File(external, "runtime-audit-dedicated").isFile) { "Dedicated emulator required" }
            check(root.mkdir())
            outside.writeText("outside sentinel")
            val provider = RimeDataProvider()
            val info = checkNotNull(context.packageManager.resolveContentProvider("${context.packageName}.provider", 0))
            provider.attachInfo(context, info)
            val prefix = checkNotNull(checkNotNull(external).canonicalFile.parentFile).path + File.separator
            fun id(file: File) = file.canonicalPath.removePrefix(prefix)
            val target = File(root, "target").apply { check(mkdir()) }
            val simple = File(root, "simple").apply { check(mkdir()) }
            File(simple, "child").writeText("fixture")
            val copied = provider.copyDocument(id(simple), id(target))
            val copiedFile = File(target, "simple")
            check(copied == id(copiedFile) && File(copiedFile, "child").readText() == "fixture")
            provider.deleteDocument(copied)
            check(!copiedFile.exists() && File(simple, "child").readText() == "fixture")

            val deep = File(root, "deep").apply { check(mkdir()) }
            var leaf = deep
            repeat(DocumentsTreeBudget.MAX_DEPTH + 1) { leaf = File(leaf, "d").apply { check(mkdir()) } }
            val deepSentinel = File(leaf, "sentinel").apply { writeText("preserve") }
            check(runCatching { provider.deleteDocument(id(deep)) }.exceptionOrNull() is FileNotFoundException)
            check(deepSentinel.readText() == "preserve")
            check(runCatching { provider.copyDocument(id(deep), id(target)) }.exceptionOrNull() is FileNotFoundException)
            check(target.listFiles()!!.isEmpty() && deepSentinel.isFile)

            val oversized = File(root, "oversized")
            java.io.RandomAccessFile(oversized, "rw").use { it.setLength(DocumentsTreeBudget.MAX_COPY_BYTES + 1) }
            check(runCatching { provider.copyDocument(id(oversized), id(target)) }.exceptionOrNull() is FileNotFoundException)
            check(oversized.length() == DocumentsTreeBudget.MAX_COPY_BYTES + 1 && target.listFiles()!!.isEmpty())

            check(privateLinked.mkdir())
            Os.symlink(outside.absolutePath, File(privateLinked, "outside").absolutePath)
            check(runCatching {
                DocumentsTreeBudget.snapshot(privateLinked, allowed = { it.canonicalFile == it.absoluteFile }) { file, _ ->
                    checkNotNull(file.listFiles()).toList()
                }
            }.exceptionOrNull() is FileNotFoundException)
            check(outside.readText() == "outside sentinel")
            check(target.listFiles()!!.isEmpty())

            val large = File(root, "large").apply { check(mkdir()) }
            repeat(DocumentsTreeBudget.MAX_NODES + 1) { check(File(large, "f$it").createNewFile()) }
            check(runCatching { provider.deleteDocument(id(large)) }.exceptionOrNull() is FileNotFoundException)
            check(runCatching { provider.copyDocument(id(large), id(target)) }.exceptionOrNull() is FileNotFoundException)
            check(runCatching { provider.querySearchDocuments(id(large), "no-such-name", null) }.exceptionOrNull() is FileNotFoundException)
            check(runCatching { provider.queryChildDocuments(id(large), null, null as String?) }.exceptionOrNull() is FileNotFoundException)
            check(large.listFiles()!!.size == DocumentsTreeBudget.MAX_NODES + 1 && target.listFiles()!!.isEmpty())
            code = Activity.RESULT_OK
            result.putString("stream", "PASS: provider bounded preflight, normal copy/delete, depth, symlink and oversized trees\n")
        } catch (failure: Throwable) {
            primary = failure
            result.putString("stream", android.util.Log.getStackTraceString(failure))
        } finally {
            val cleanup = mutableListOf<Throwable>()
            try { if (root.exists() && !root.deleteRecursively()) error("Cannot clean document fixture") } catch (failure: Throwable) { cleanup.add(failure) }
            try { if (privateLinked.exists() && !privateLinked.deleteRecursively()) error("Cannot clean symlink fixture") } catch (failure: Throwable) { cleanup.add(failure) }
            try { if (outside.exists() && !outside.delete()) error("Cannot clean outside fixture") } catch (failure: Throwable) { cleanup.add(failure) }
            if (cleanup.isNotEmpty()) {
                code = Activity.RESULT_CANCELED
                cleanup.forEach { primary?.addSuppressed(it) }
                result.putString("stream", android.util.Log.getStackTraceString(primary ?: cleanup.first()))
            }
        }
        finish(code, result)
    }
}
