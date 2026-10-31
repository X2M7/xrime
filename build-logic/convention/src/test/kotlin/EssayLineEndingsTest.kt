/* SPDX-License-Identifier: GPL-3.0-or-later */

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EssayLineEndingsTest {
    @Test
    fun normalizesOnlyCrLfAndKeepsSourceAndOtherBytes() {
        val dir = createTempDirectory("essay-line-endings").toFile()
        try {
            val source = dir.resolve("essay.txt")
            // Put CR at the end of the default buffered stream's first block.
            val bytes = ByteArray(8191) { 'a'.code.toByte() } +
                byteArrayOf(13, 10, 13, 13, 10, 10, 0, 0xc3.toByte(), 0xa9.toByte(), 13)
            source.writeBytes(bytes)
            val output = dir.resolve("shared/essay.txt")
            output.parentFile.mkdirs()
            copyEssayWithLf(source, output)
            val expected = ByteArray(8191) { 'a'.code.toByte() } +
                byteArrayOf(10, 13, 10, 10, 0, 0xc3.toByte(), 0xa9.toByte(), 13)
            assertContentEquals(expected, output.readBytes())
            assertContentEquals(bytes, source.readBytes())
            val lfOnly = dir.resolve("lf-only.txt").apply { writeText("a\nb\n") }
            val second = dir.resolve("again.txt")
            copyEssayWithLf(lfOnly, second)
            assertContentEquals(lfOnly.readBytes(), second.readBytes())
            val hashes = sharedDataChecksums(dir)
            assertEquals(DataChecksumsPlugin.DataChecksumsTask.sha256(output), hashes.getValue("shared/essay.txt"))
            assertNotEquals(DataChecksumsPlugin.DataChecksumsTask.sha256(source), hashes.getValue("shared/essay.txt"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun vocabularyAdaptationIsIdenticalForCrLfAndLf() {
        val dir = createTempDirectory("essay-vocabulary-line-endings").toFile()
        try {
            val dictionary = dir.resolve("source.dict.yaml").apply {
                writeText("---\nname: luna_pinyin\nversion: '1'\nuse_preset_vocabulary: true\n...\na\ta\nb\tb\n")
            }
            val content = "# vocabulary\na\t20\nb\t10\nab\t30\na.name\t40\n"
            val crlf = dir.resolve("crlf.txt").apply { writeText(content.replace("\n", "\r\n")) }
            val lf = dir.resolve("lf.txt").apply { writeText(content) }
            val normalized = dir.resolve("normalized.txt")
            copyEssayWithLf(crlf, normalized)
            assertContentEquals(lf.readBytes(), normalized.readBytes())
            fun adapt(essay: java.io.File, prefix: String): List<ByteArray> {
                val outputs = listOf("dictionary.yaml", "accepted.txt", "rejected.tsv").map { dir.resolve("$prefix-$it") }
                LunaVocabulary.adapt(dictionary, essay, outputs[0], outputs[1], outputs[2])
                return outputs.map { it.readBytes() }
            }
            val before = crlf.readBytes()
            val original = adapt(crlf, "crlf")
            val packaged = adapt(normalized, "normalized")
            original.zip(packaged).forEach { (a, b) -> assertContentEquals(a, b) }
            assertContentEquals(before, crlf.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }
}
