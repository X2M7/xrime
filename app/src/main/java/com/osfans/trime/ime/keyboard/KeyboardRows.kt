/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.orAbsent

internal data class KeyboardRow(val widthWeight: Float, val heightDp: Int)

/** An explicitly empty click is a visible inert key; an omitted click is a spacer. */
internal val TextKeyboard.TextKey.hasKeyboardSlot: Boolean
    get() = click.orAbsent != null || (click is KeyActionToken.Plain && click.token.isEmpty())

/** Measures the same rows used by layout, before scaling to a requested height. */
internal fun measureKeyboardRows(config: TextKeyboard, allowedWidth: Int, styleKeyWidth: Float, defaultKeyHeight: Int): List<KeyboardRow> {
    val width = resolveKeyboardKeyWidth(config.width, styleKeyWidth)
    val maxColumns = if (config.columns == -1) Int.MAX_VALUE else config.columns
    val fallbackHeight = defaultKeyHeight.takeIf { it > 0 } ?: 44
    val rows = mutableListOf<KeyboardRow>()
    var x = 0
    var column = 0
    var rowHeight = fallbackHeight
    var totalWidth = 0f
    for (key in config.keys) {
        val slot = key.hasKeyboardSlot
        val weight = if (key.width == 0f && slot) width else key.width
        val widthPx = (weight * allowedWidth / 100).toInt()
        if (column >= maxColumns || x + widthPx > allowedWidth) {
            rows.add(KeyboardRow(totalWidth, rowHeight))
            x = 0
            column = 0
            totalWidth = 0f
        }
        if (column == 0) {
            rowHeight = key.height.toInt().takeIf { it > 0 } ?: fallbackHeight
        }
        totalWidth += weight
        if (slot) column++
        x += widthPx
    }
    rows.add(KeyboardRow(totalWidth, rowHeight))
    return rows
}

/** A positive configured height always wins; absent geometry gets a usable window. */
internal fun resolveKeyboardHeightPx(configuredPx: Int, rows: List<KeyboardRow>, hasKeys: Boolean, dp: (Int) -> Int): Int {
    if (configuredPx > 0) return configuredPx
    val naturalDp = if (hasKeys) rows.sumOf { it.heightDp.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
    return dp(naturalDp.takeIf { it > 0 } ?: 250)
}
