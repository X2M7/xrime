/* SPDX-License-Identifier: GPL-3.0-or-later */

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/** Package the logical essay once, preserving the generated target in checksum inputs. */
abstract class PackageRimeAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun packageAssets() {
        val input = inputDir.get().asFile
        val output = outputDir.get().asFile
        require(!input.resolve("xrime-internal").exists()) { "Reserved asset directory already exists" }
        fileSystem.sync {
            from(input)
            into(output)
            exclude("shared/luna_pinyin_essay.txt")
        }
        val patch = output.resolve("xrime-internal/luna-essay.patch")
        patch.parentFile.mkdirs()
        LunaEssayPatchBuilder.create(input.resolve("shared/essay.txt"), input.resolve("shared/luna_pinyin_essay.txt"), patch)
    }
}

internal object LunaEssayPatchBuilder {
    private val magic = byteArrayOf(88, 82, 73, 77, 69, 80, 48, 49) // XRIMEP01
    private const val MAX_SIZE = 64 * 1024 * 1024
    private const val MAX_RANGES = 65_536

    /** The target must be a byte-exact subsequence of complete source lines. */
    fun create(source: File, target: File, patch: File) {
        require(source.length() <= MAX_SIZE && target.length() <= MAX_SIZE)
        val base = source.readBytes()
        val filtered = target.readBytes()
        val removed = mutableListOf<Pair<Int, Int>>()
        var from = 0
        var to = 0
        while (from < base.size) {
            var end = from
            while (end < base.size && base[end] != '\n'.code.toByte()) {
                end++
            }
            if (end < base.size) end++
            val length = end - from
            val matches = length <= filtered.size - to && base.regionMatches(from, filtered, to, length)
            if (matches) {
                to += length
            } else if (removed.isNotEmpty() && removed.last().first + removed.last().second == from) {
                val previous = removed.removeAt(removed.lastIndex)
                removed.add(previous.first to previous.second + length)
            } else {
                removed.add(from to length)
            }
            from = end
        }
        require(to == filtered.size && removed.size <= MAX_RANGES) { "Filtered essay is not a bounded source subsequence" }
        DataOutputStream(patch.outputStream().buffered()).use { out ->
            out.write(magic)
            out.writeLong(base.size.toLong())
            out.writeLong(filtered.size.toLong())
            out.write(MessageDigest.getInstance("SHA-256").digest(base))
            out.write(MessageDigest.getInstance("SHA-256").digest(filtered))
            out.writeInt(removed.size)
            removed.forEach { (offset, length) -> out.writeLong(offset.toLong()); out.writeLong(length.toLong()) }
        }
    }

    private fun ByteArray.regionMatches(start: Int, other: ByteArray, otherStart: Int, length: Int): Boolean {
        for (index in 0 until length) if (this[start + index] != other[otherStart + index]) return false
        return true
    }
}
