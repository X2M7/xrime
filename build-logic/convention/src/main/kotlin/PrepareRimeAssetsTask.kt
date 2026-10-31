/* SPDX-License-Identifier: GPL-3.0-or-later */

import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.inject.Inject

abstract class PrepareRimeAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rimeDataDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val reportDir: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun prepare() {
        val source = inputDir.get().asFile
        val output = outputDir.get().asFile
        val reports = reportDir.get().asFile.apply { mkdirs() }
        fileSystem.sync {
            from(source)
            into(output)
            exclude(DataChecksumsPlugin.FILE_NAME)
        }
        val shared = output.resolve("shared")
        val sourceShared = source.resolve("shared")
        val data = rimeDataDir.get().asFile
        val resolved = RimeAssetLinks.names.associateWith { RimeAssetLinks.resolve(sourceShared, data, it) }
        resolved.forEach { (name, file) ->
            if (name == "essay.txt") copyEssayWithLf(file, shared.resolve(name))
            else file.copyTo(shared.resolve(name), overwrite = true)
        }
        LunaVocabulary.adapt(
            resolved.getValue("luna_pinyin.dict.yaml"),
            shared.resolve("essay.txt"),
            shared.resolve("luna_pinyin.dict.yaml"),
            shared.resolve("luna_pinyin_essay.txt"),
            reports.resolve("luna-unencodable.tsv"),
        )
        val hashes = linkedMapOf<String, String>()
        for (name in listOf("essay.txt", "luna_pinyin.dict.yaml")) {
            hashes["source/$name"] = DataChecksumsPlugin.DataChecksumsTask.sha256(resolved.getValue(name))
        }
        for (name in listOf("essay.txt", "luna_pinyin.dict.yaml", "luna_pinyin_essay.txt")) {
            hashes["packaged/$name"] = DataChecksumsPlugin.DataChecksumsTask.sha256(shared.resolve(name))
        }
        hashes["rejected/luna-unencodable.tsv"] = DataChecksumsPlugin.DataChecksumsTask.sha256(reports.resolve("luna-unencodable.tsv"))
        reports.resolve("sha256.json").writeText(json.encodeToString(hashes))
    }
}

/** Normalize only CRLF in the generated essay while preserving every other byte. */
internal fun copyEssayWithLf(source: File, destination: File) {
    source.inputStream().buffered().use { input ->
        destination.outputStream().buffered().use { output ->
            var pendingCr = false
            while (true) {
                val value = input.read()
                if (value < 0) break
                if (pendingCr) {
                    if (value != 10) output.write(13)
                    pendingCr = false
                }
                if (value == 13) pendingCr = true else output.write(value)
            }
            if (pendingCr) output.write(13)
        }
    }
}
