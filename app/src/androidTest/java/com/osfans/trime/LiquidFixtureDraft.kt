/* Test-only unique temporary liquid keyboard, no normal symbol-history writes. */
package com.osfans.trime

internal object LiquidFixtureDraft {
    const val tabCount = 100
    const val indexName = "edge_index"
    fun tagId(index: Int): String = "edge_tab_" + index.toString().padStart(3, '0')
    fun tagLabel(index: Int): String = "EDGE" + index.toString().padStart(3, '0')
    fun tagSymbol(index: Int): String = (0x4e00 + index).toChar().toString()

    fun theme(baseAsset: String): String {
        val base = baseAsset.replace("\r\n", "\n")
        val start = base.indexOf("\nliquid_keyboard:\n")
        val end = base.indexOf("\npreset_keys:\n", start + 1)
        check(start >= 0 && end > start)
        val ids = (0 until tabCount).joinToString(", ") { tagId(it) }
        val generated = buildString {
            append("\nliquid_keyboard:\n")
            append("  row: 6\n  key_height: 40\n  single_width: 90\n  margin_x: 5\n")
            append("  fixed_key_bar:\n    position: bottom\n    keys: []\n")
            append("  keyboards: [").append(indexName).append(", ").append(ids).append("]\n")
            append("  ").append(indexName).append(":\n    name: EDGEINDEX\n    type: TABS\n")
            repeat(tabCount) { i ->
                append("  ").append(tagId(i)).append(":\n")
                append("    name: ").append(tagLabel(i)).append("\n")
                append("    type: SINGLE\n    keys: '").append(tagSymbol(i)).append("'\n")
            }
        }
        return base.substring(0, start) + generated + base.substring(end)
    }
}
