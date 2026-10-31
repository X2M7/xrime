/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.KeyActionToken
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlin.io.path.createTempDirectory

class Issue2016ThemeFixtureTest : BehaviorSpec({
    Given("an issue 2016 theme that inherits the standard theme") {
        Then("nested slash patch paths delegate to the native loader") {
            val dir = createTempDirectory("issue-2016-native-fallback").toFile()
            try {
                val fixture = dir.resolve("issue2016.trime.yaml").apply {
                    writeText("__include: trime:/\n__patch:\n  preset_keyboards/default/name: Test Keyboard\n")
                }
                val builtin = File("src/main/assets/shared/trime.yaml")
                check(builtin.isFile)
                val loader = ThemeLoader.SourceLoader { id -> if (id == "trime") builtin else null }
                val source = checkNotNull(loader.load("issue2016.trime", fixture))
                val error = shouldThrow<ThemeDslExpander.UnsupportedDsl> {
                    ThemeLoader.decodeSource("issue2016.trime", source) { id -> loader.load(id, null) }
                }
                error.message shouldBe "unsupported patch path 'preset_keyboards/default/name'"
                ThemeLoader.loadFromSource("issue2016.trime", fixture, loader) shouldBe null
            } finally {
                dir.deleteRecursively()
            }
        }

        Then("recursive include overrides preserve colors and replace the default keyboard") {
            val dir = createTempDirectory("issue-2016-theme").toFile()
            try {
                val fixture = dir.resolve("issue2016.trime.yaml")
                fixture.writeText("""
                    config_version: '1'
                    __include: trime:/
                    name: Issue 2016 Test Theme
                    preset_keyboards:
                      default:
                        name: Test Keyboard
                        keys:
                          - {click: a, label: A}
                          - {click: b, label: B}
                          - {click: space, label: Space}
                          - {click: Return, label: Enter}
                """.trimIndent())
                val builtin = File("src/main/assets/shared/trime.yaml")
                check(builtin.isFile)
                val loader = ThemeLoader.SourceLoader { id -> if (id == "trime") builtin else null }
                // Surface the exact unsupported DSL or decoding error; loadFromSource
                // intentionally returns null when it delegates to native deployment.
                val source = checkNotNull(loader.load("issue2016.trime", fixture))
                ThemeLoader.decodeSource("issue2016.trime", source) { id -> loader.load(id, null) }
                val result = ThemeLoader.loadFromSource("issue2016.trime", fixture, loader)
                val theme = result.shouldBeInstanceOf<ThemeLoader.ThemeLoadResult.Success>().theme
                theme.name shouldBe "Issue 2016 Test Theme"
                theme.presetColorSchemes.isNotEmpty() shouldBe true
                val keyboard = theme.presetKeyboards.getValue("default")
                keyboard.name shouldBe "Test Keyboard"
                keyboard.keys.map { it.label } shouldBe listOf("A", "B", "Space", "Enter")
                keyboard.keys.map { it.click } shouldBe listOf("a", "b", "space", "Return").map { KeyActionToken.Plain(it) }
            } finally {
                dir.deleteRecursively()
            }
        }
    }
})
