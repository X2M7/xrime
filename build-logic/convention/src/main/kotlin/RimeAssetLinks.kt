/* SPDX-License-Identifier: GPL-3.0-or-later */

import java.io.File
import java.nio.file.Files

/** Fixed repository links, also usable when Git checks them out as files on Windows. */
internal object RimeAssetLinks {
    private val targets = linkedMapOf(
        "default.yaml" to "prelude/default.yaml",
        "essay.txt" to "essay/essay.txt",
        "key_bindings.yaml" to "prelude/key_bindings.yaml",
        "luna_pinyin.dict.yaml" to "luna-pinyin/luna_pinyin.dict.yaml",
        "luna_pinyin.schema.yaml" to "luna-pinyin/luna_pinyin.schema.yaml",
        "luna_pinyin_fluency.schema.yaml" to "luna-pinyin/luna_pinyin_fluency.schema.yaml",
        "luna_pinyin_simp.schema.yaml" to "luna-pinyin/luna_pinyin_simp.schema.yaml",
        "luna_pinyin_tw.schema.yaml" to "luna-pinyin/luna_pinyin_tw.schema.yaml",
        "luna_quanpin.schema.yaml" to "luna-pinyin/luna_quanpin.schema.yaml",
        "pinyin.yaml" to "luna-pinyin/pinyin.yaml",
        "punctuation.yaml" to "prelude/punctuation.yaml",
        "stroke.dict.yaml" to "stroke/stroke.dict.yaml",
        "stroke.schema.yaml" to "stroke/stroke.schema.yaml",
        "symbols.yaml" to "prelude/symbols.yaml",
    )

    val names: Set<String> get() = targets.keys

    fun resolve(shared: File, data: File, name: String): File {
        val relative = targets[name] ?: error("Unknown bundled Rime link: $name")
        val link = shared.resolve(name)
        val expectedLink = "../../../../data/rime/$relative"
        val observed = if (Files.isSymbolicLink(link.toPath())) {
            Files.readSymbolicLink(link.toPath()).toString().replace('\\', '/')
        } else {
            check(link.isFile && link.length() <= 512) { "Expected a bundled Rime link: $link" }
            link.readText(Charsets.UTF_8).trimEnd('\r', '\n')
        }
        check(observed == expectedLink) { "Unexpected bundled Rime link target: $link" }
        val root = data.canonicalFile.toPath()
        val target = data.resolve(relative).canonicalFile
        check(target.toPath().startsWith(root) && target.isFile) { "Bundled Rime data is missing or outside its root: $relative" }
        return target
    }
}
