/* SPDX-License-Identifier: GPL-3.0-or-later */

import java.io.DataInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LunaEssayPatchBuilderTest {
    @Test
    fun `encodes exact removed byte ranges without changing source`() {
        val root = createTempDirectory("luna-patch").toFile()
        try {
            val source = root.resolve("source").apply { writeText("first\nremove\nkeep\nlast") }
            val target = root.resolve("target").apply { writeText("first\nkeep\nlast") }
            val patch = root.resolve("patch")
            LunaEssayPatchBuilder.create(source, target, patch)
            DataInputStream(patch.inputStream()).use { input ->
                assertContentEquals("XRIMEP01".toByteArray(), ByteArray(8).also(input::readFully))
                assertEquals(source.length(), input.readLong())
                assertEquals(target.length(), input.readLong())
                input.skipBytes(64)
                assertEquals(1, input.readInt())
                assertEquals(6L, input.readLong())
                assertEquals(7L, input.readLong())
                assertEquals(-1, input.read())
            }
            assertEquals("first\nremove\nkeep\nlast", source.readText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `rejects target that cannot be produced by deleting whole lines`() {
        val root = createTempDirectory("luna-patch-invalid").toFile()
        try {
            val source = root.resolve("source").apply { writeText("first\nsecond\n") }
            val target = root.resolve("target").apply { writeText("first\nchanged\n") }
            assertFailsWith<IllegalArgumentException> { LunaEssayPatchBuilder.create(source, target, root.resolve("patch")) }
        } finally { root.deleteRecursively() }
    }
}
