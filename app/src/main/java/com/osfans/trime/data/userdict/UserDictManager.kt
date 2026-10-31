/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.userdict

import com.osfans.trime.core.data.DataManager
import com.osfans.trime.util.appContext
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** JNI operations must run inside RimeApi.maintainUserDictionaries, with session handles closed. */
object UserDictManager {
    private fun store() = DictionaryFileStore(
        DataManager.userDataDir,
        File(appContext.filesDir, "userdict-backups"),
    )

    private inline fun <T> result(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private inline fun <T> staged(suffix: String, block: (File) -> T): T {
        val temporary = File.createTempFile("userdict-", suffix, appContext.cacheDir)
        try {
            return block(temporary)
        } finally {
            temporary.delete()
        }
    }

    /** Called at startup before native sessions are created, and before every mutation. */
    fun recoverPendingMutation(): Boolean = store().recoverPendingMutation()

    fun backupForRecovery(dictName: String): Result<Unit> = result {
        val name = requireDictionaryName(dictName)
        store().capture("$name.userdb")
        if (!backupUserDict(name)) throw IOException("Rime could not create the portable dictionary backup")
    }

    fun hasRecoveryBackup(dictName: String): Boolean = store().latest("${requireDictionaryName(dictName)}.userdb") != null

    fun recoverUserDict(dictName: String): Result<Unit> = result {
        val target = "${requireDictionaryName(dictName)}.userdb"
        val files = store()
        files.recoverPendingMutation()
        val backup = files.latest(target) ?: throw IOException("No dictionary recovery backup is available")
        files.mutate(target) { files.restore(backup) }
    }

    fun restoreUserDict(stream: InputStream, snapshotFile: String): Result<Unit> = result {
        staged(".userdb.txt") { temporary ->
            DictionaryTextFormats.copyBounded(stream, temporary)
            val name = DictionaryTextFormats.snapshotDictionaryName(temporary)
            store().mutate("$name.userdb") {
                if (!restoreUserDictChecked(name, temporary.absolutePath)) {
                    throw IOException("Rime could not merge the dictionary snapshot: $snapshotFile")
                }
            }
        }
    }

    fun importUserDict(stream: InputStream, dictName: String, textFile: String): Result<Int> = result {
        val name = requireDictionaryName(dictName)
        staged(".txt") { temporary ->
            DictionaryTextFormats.copyBounded(stream, temporary)
            val expected = DictionaryTextFormats.validateTable(temporary)
            store().mutate("$name.userdb") {
                val count = importUserDict(name, temporary.absolutePath)
                if (count != expected) throw IOException("Rime did not import every dictionary entry from $textFile")
                count
            }
        }
    }

    /** The caller owns a private staging path and exports it only after Rime is released. */
    internal fun exportUserDictToFile(destination: File, dictName: String, textFile: String): Result<Int> = result {
        val name = requireDictionaryName(dictName)
        val count = exportUserDict(name, destination.absolutePath)
        if (count < 0 || !destination.isFile) throw IOException("Rime could not export the dictionary to $textFile")
        // The librime API accepts only a path, so this check occurs after native generation.
        if (destination.length() > DictionaryDocumentStager.MAX_EXPORT_BYTES) {
            throw DictionarySizeLimitException("Dictionary export exceeds the 256 MiB limit")
        }
        val header = "# Rime user dictionary export\n".toByteArray(Charsets.UTF_8)
        val actualHeader = ByteArray(header.size)
        val headerRead = destination.inputStream().use { input ->
            var total = 0
            while (total < actualHeader.size) {
                val read = input.read(actualHeader, total, actualHeader.size - total)
                if (read <= 0) break
                total += read
            }
            total
        }
        if (headerRead != header.size || !actualHeader.contentEquals(header)) {
            throw IOException("Rime produced an incomplete dictionary export")
        }
        if (DictionaryTextFormats.validateTable(destination) != count) {
            throw IOException("Rime could not export the dictionary to $textFile")
        }
        count
    }

    /** Compatibility helper for app-owned outputs; external documents use DictionaryDocumentStager. */
    fun exportUserDict(dest: OutputStream, dictName: String, textFile: String): Result<Int> = result {
        staged(".txt") { temporary ->
            val count = exportUserDictToFile(temporary, dictName, textFile).getOrThrow()
            temporary.inputStream().use { input ->
                DictionaryTextFormats.copyBounded(input, dest, DictionaryDocumentStager.MAX_EXPORT_BYTES) {}
            }
            count
        }
    }

    @JvmStatic
    external fun getUserDictList(): Array<String>

    @JvmStatic
    external fun backupUserDict(dictName: String): Boolean

    @JvmStatic
    external fun restoreUserDict(snapshotFile: String): Boolean

    @JvmStatic
    private external fun restoreUserDictChecked(dictName: String, snapshotFile: String): Boolean

    @JvmStatic
    external fun exportUserDict(dictName: String, textFile: String): Int

    @JvmStatic
    external fun importUserDict(dictName: String, textFile: String): Int
}
