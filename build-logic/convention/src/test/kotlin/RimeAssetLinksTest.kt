/* SPDX-License-Identifier: GPL-3.0-or-later */

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RimeAssetLinksTest {
    @Test
    fun windowsPlaceholderResolvesOnlyThePinnedBundledTarget() {
        val root = createTempDirectory("rime-links").toFile()
        try {
            val shared = root.resolve("shared").apply { mkdirs() }
            val data = root.resolve("data").apply { mkdirs() }
            data.resolve("prelude/default.yaml").apply { parentFile.mkdirs(); writeText("config: valid\n") }
            val link = shared.resolve("default.yaml")
            link.writeText("../../../../data/rime/prelude/default.yaml")
            assertEquals("config: valid\n", RimeAssetLinks.resolve(shared, data, "default.yaml").readText())
            link.writeText("../../../../data/rime/prelude/other.yaml")
            assertFailsWith<IllegalStateException> { RimeAssetLinks.resolve(shared, data, "default.yaml") }
            assertFailsWith<IllegalStateException> { RimeAssetLinks.resolve(shared, data, "not-bundled") }
        } finally {
            root.deleteRecursively()
        }
    }
}
