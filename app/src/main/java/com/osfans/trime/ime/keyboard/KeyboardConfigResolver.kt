/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import com.osfans.trime.data.theme.model.TextKeyboard

/** Keep numeric actions and positions while borrowing the originating T9 key style. */
internal fun pairNumberStyle(number: TextKeyboard, t9: TextKeyboard, defaultWidth: Float = 10f): TextKeyboard {
    fun positioned(layout: TextKeyboard): List<Pair<Int, Float>> {
        var row = 0
        var x = 0f
        var column = 0
        val columns = if (layout.columns == -1) Int.MAX_VALUE else layout.columns
        return layout.keys.map { key ->
            val slot = key.hasKeyboardSlot
            val width = if (key.width == 0f && slot) {
                layout.width.takeIf { it > 0 } ?: defaultWidth
            } else {
                key.width
            }
            if (column >= columns || x + width > 100.01f) {
                row++
                x = 0f
                column = 0
            }
            val position = row to (x + width / 2f)
            x += width
            if (slot) column++
            position
        }
    }
    if (t9.keys.isEmpty()) return number
    val sourcePositions = positioned(t9)
    val targetPositions = positioned(number)
    val styledKeys = number.keys.mapIndexed { index, key ->
        val (row, x) = targetPositions[index]
        val sourceIndex = sourcePositions.indices.minByOrNull {
            val (sourceRow, sourceX) = sourcePositions[it]
            kotlin.math.abs(sourceRow - row) * 1000f + kotlin.math.abs(sourceX - x)
        } ?: return@mapIndexed key
        val style = t9.keys[sourceIndex]
        key.copy(
            keyBackColor = style.keyBackColor,
            keyTextColor = style.keyTextColor,
            keyBorderColor = style.keyBorderColor,
            keySymbolColor = style.keySymbolColor,
            hilitedKeyBackColor = style.hilitedKeyBackColor,
            hilitedKeyTextColor = style.hilitedKeyTextColor,
            hilitedKeyBorderColor = style.hilitedKeyBorderColor,
            hilitedKeySymbolColor = style.hilitedKeySymbolColor,
        )
    }
    return number.copy(horizontalGap = t9.horizontalGap, verticalGap = t9.verticalGap, roundCorner = t9.roundCorner, keyBorder = t9.keyBorder, keys = styledKeys)
}

internal fun resolveKeyboardConfig(name: String, presets: Map<String, TextKeyboard>): TextKeyboard? {
    val visited = mutableSetOf<String>()
    var current = name
    while (true) {
        val id = if (current in presets) current else "default"
        require(visited.add(id)) { "Cyclic keyboard import: ${visited.joinToString()} -> $id" }
        val config = presets[id] ?: return null
        if (config.importPreset.isEmpty()) return config
        current = config.importPreset
    }
}

/** Resolves an existing layout, including an injected compatibility layout. */
internal fun findAvailableKeyboard(
    name: String,
    presets: Map<String, TextKeyboard>,
    fallbacks: Map<String, String>,
): String? = name.takeIf { it in presets }
    ?: fallbacks[name]?.takeIf { it in presets }

/** Resolves a switch target to a layout that is guaranteed to exist. */
internal fun normalizeKeyboardTarget(
    name: String,
    presets: Map<String, TextKeyboard>,
    fallbacks: Map<String, String>,
): String {
    require(presets.isNotEmpty()) { "No keyboard layouts are available" }
    return findAvailableKeyboard(name, presets, fallbacks)
        ?: findAvailableKeyboard("default", presets, fallbacks)
        ?: findAvailableKeyboard("letter", presets, fallbacks)
        ?: presets.keys.first()
}

/** Applies a landscape override only after [base] has resolved to an existing layout. */
internal fun resolveLandscapeKeyboard(
    base: String,
    presets: Map<String, TextKeyboard>,
    fallbacks: Map<String, String>,
): String {
    val landscape = presets.getValue(base).landscapeKeyboard
    return landscape.takeIf { it.isNotEmpty() }
        ?.let { findAvailableKeyboard(it, presets, fallbacks) }
        ?: base
}

/** Resolves ASCII from the current native schema layout, never the transient active layout. */
internal fun resolveAsciiKeyboard(
    configuredAsciiKeyboard: String,
    presets: Map<String, TextKeyboard>,
    fallbacks: Map<String, String>,
): String {
    for (name in listOf(configuredAsciiKeyboard, "letter", fallbacks["letter"].orEmpty()).distinct()) {
        if (name.isEmpty()) continue
        val id = findAvailableKeyboard(name, presets, fallbacks) ?: continue
        val config = try {
            resolveKeyboardConfig(id, presets)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (config?.asciiMode == 1) return id
    }
    return normalizeKeyboardTarget("default", presets, fallbacks)
}

internal fun matchKeyboard(
    schemaId: String,
    requested: String,
    pinyinT9: Boolean,
    alphabet: String,
    presets: Map<String, TextKeyboard>,
    fallbacks: Map<String, String>,
): String {
    if (requested.isNotEmpty()) {
        findAvailableKeyboard(requested, presets, fallbacks)?.let { return it }
    }
    findAvailableKeyboard(schemaId, presets, fallbacks)?.let { return it }
    if (pinyinT9) {
        return findAvailableKeyboard("luna_pinyin_t9", presets, fallbacks)
            ?: normalizeKeyboardTarget("default", presets, fallbacks)
    }
    val layout = when {
        alphabet.isEmpty() -> "default"
        alphabet.all { it.isLetter() } -> "qwerty"
        alphabet.all { it.isLetter() || it in ",./;" } -> "qwerty_"
        alphabet.all { it.isLetterOrDigit() } -> "qwerty0"
        else -> "default"
    }
    return findAvailableKeyboard(layout, presets, fallbacks)
        ?: normalizeKeyboardTarget("default", presets, fallbacks)
}
