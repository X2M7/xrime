/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KeyboardRowsTest :
    StringSpec({
        fun key(width: Float = 0f, height: Float = 0f) = TextKeyboard.TextKey(
            width = width,
            height = height,
            click = KeyActionToken.Plain("a"),
        )

        "natural height uses the real column wraps and first key height of each row" {
            val config = TextKeyboard(width = 20f, columns = 2, keys = listOf(key(height = 40f), key(), key(height = 60f)))
            val rows = measureKeyboardRows(config, 1000, 0f, 44)
            rows.map { it.heightDp } shouldBe listOf(40, 60)
            resolveKeyboardHeightPx(0, rows, true) { it * 2 } shouldBe 200
            resolveKeyboardHeightPx(600, rows, true) { it * 2 } shouldBe 600
        }

        "width wrapping and blank spacers use layout rules" {
            val config = TextKeyboard(width = 40f, columns = 10, keys = listOf(key(), TextKeyboard.TextKey(width = 20f), key(), key(height = 55f)))
            val rows = measureKeyboardRows(config, 1000, 0f, 42)
            rows.map { it.widthWeight } shouldBe listOf(100f, 40f)
            rows.map { it.heightDp } shouldBe listOf(42, 55)
        }

        "explicit empty click is a key slot but omitted click remains a spacer" {
            val blank = TextKeyboard.TextKey(click = KeyActionToken.Plain(""))
            val spacer = TextKeyboard.TextKey(width = 10f)
            val explicitBlank = blank.copy(width = 30f)
            val absentMapping = TextKeyboard.TextKey(click = KeyActionToken.Inline())
            blank.hasKeyboardSlot shouldBe true
            explicitBlank.hasKeyboardSlot shouldBe true
            spacer.hasKeyboardSlot shouldBe false
            absentMapping.hasKeyboardSlot shouldBe false
            val config = TextKeyboard(width = 20f, columns = 2, keys = listOf(key(), blank, spacer, key(), explicitBlank))
            val rows = measureKeyboardRows(config, 1000, 10f, 44)
            rows.map { it.widthWeight } shouldBe listOf(40f, 60f)
            rows.map { it.heightDp } shouldBe listOf(44, 44)
        }

        "explicit empty clicks have default width even when the whole keyboard is blank" {
            val blank = TextKeyboard.TextKey(click = KeyActionToken.Plain(""))
            val rows = measureKeyboardRows(TextKeyboard(width = 40f, columns = 2, keys = listOf(blank, blank, blank)), 1000, 10f, 42)
            rows.map { it.widthWeight } shouldBe listOf(80f, 40f)
            resolveKeyboardHeightPx(0, rows, true) { it } shouldBe 84
        }

        "missing row height and empty layout retain a nonzero height" {
            val rows = measureKeyboardRows(TextKeyboard(width = 100f, keys = listOf(key(), key())), 1000, 0f, 0)
            rows.map { it.heightDp } shouldBe listOf(44, 44)
            resolveKeyboardHeightPx(0, rows, true) { it * 3 } shouldBe 264
            resolveKeyboardHeightPx(0, emptyList(), false) { it * 3 } shouldBe 750
        }
    })
