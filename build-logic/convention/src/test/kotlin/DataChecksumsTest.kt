/* SPDX-License-Identifier: GPL-3.0-or-later */

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataChecksumsTest {
    @Test
    fun onlySharedAssetsEnterTheInstallDescriptor() {
        val root = createTempDirectory("rime-checksums").toFile()
        try {
            root.resolve("privacy.md").writeText("notice")
            root.resolve("other").mkdirs()
            root.resolve("other/non-rime.txt").writeText("other")
            root.resolve("shared/nested").mkdirs()
            root.resolve("shared/nested/config.yaml").writeText("config")
            val checksums = sharedDataChecksums(root)
            assertEquals(setOf("shared/nested", "shared/nested/config.yaml"), checksums.keys)
            assertEquals("", checksums.getValue("shared/nested"))
            assertTrue(checksums.getValue("shared/nested/config.yaml").isNotEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
