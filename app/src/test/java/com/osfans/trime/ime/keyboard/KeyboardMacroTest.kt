/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import com.osfans.trime.ime.core.KeyboardLanguageState
import com.osfans.trime.ime.core.KeyboardModeEffect
import com.osfans.trime.ime.core.ManualLanguageMode
import com.osfans.trime.ime.core.applyKeyboardMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KeyboardMacroTest :
    StringSpec({
        val languages = setOf("Keyboard_ascii", "Keyboard_native", "Keyboard_return", "Mode_switch")
        fun plan(value: String, texts: Map<String, String> = emptyMap(), commits: Set<String> = emptySet()) = expandLanguageMacro(
            value,
            isLanguageAction = { it in languages && it !in commits && texts[it].isNullOrEmpty() },
            textOfAction = { texts[it].takeIf { _ -> it !in commits } },
        )

        "pure input aliases expand before later navigation and literal commits" {
            val text = mapOf("outer" to "{inner}", "inner" to "x", "fence" to "```\n\n```")
            expandInputMacro("{outer}{End}{literal}", text::get) shouldBe listOf("{inner}", "{End}", "{literal}")
            expandInputMacro("{fence}{End}{Up}{Up}", text::get) shouldBe listOf("{fence}", "{End}", "{Up}", "{Up}")
        }

        "pure input expansion rejects recursive and excessive macros before dispatch" {
            shouldThrow<IllegalStateException> { expandInputMacro("{a}") { if (it == "a") "{a}" else null } }
            shouldThrow<IllegalStateException> { expandInputMacro("{a}".repeat(4097)) { null } }
            shouldThrow<IllegalStateException> { expandInputMacro("x".repeat(65537)) { null } }
        }

        "ordinary text and macros without semantic mode actions keep the legacy dispatcher" {
            plan("ordinary text") shouldBe null
            plan("{Control+a}{BackSpace}") shouldBe null
            plan("{nested}", mapOf("nested" to "{Control+a}{Control+c}")) shouldBe null
            plan("{cycle}", mapOf("cycle" to "{cycle}")) shouldBe null
            plan("{a}".repeat(4097)) shouldBe null
        }

        "plain and braced middle keys retain their original macro positions" {
            plan("{Keyboard_ascii}a{Keyboard_native}") shouldBe listOf("{Keyboard_ascii}", "a", "{Keyboard_native}")
            plan("{Keyboard_ascii}{a}{Keyboard_native}") shouldBe listOf("{Keyboard_ascii}", "{a}", "{Keyboard_native}")
        }

        "nested language text aliases flatten before a later outer input part" {
            plan("{outer}b", mapOf("outer" to "{inner}", "inner" to "{Keyboard_ascii}a{Keyboard_native}")) shouldBe
                listOf("{Keyboard_ascii}", "a", "{Keyboard_native}", "b")
        }

        "nonlanguage text aliases remain opaque so their case and modifiers use the normal listener" {
            plan("{Keyboard_ascii}{letter}{Keyboard_native}", mapOf("letter" to "a")) shouldBe
                listOf("{Keyboard_ascii}", "{letter}", "{Keyboard_native}")
            plan("{Keyboard_ascii}{navigation}{Keyboard_return}", mapOf("navigation" to "{Keyboard_number}{Keyboard_symbols}")) shouldBe
                listOf("{Keyboard_ascii}", "{Keyboard_number}", "{Keyboard_symbols}", "{Keyboard_return}")
        }

        "commit priority never interprets its literal text as a language macro" {
            plan("{literal}", mapOf("literal" to "{Keyboard_ascii}a{Keyboard_native}"), setOf("literal")) shouldBe null
            plan("{Keyboard_ascii}", mapOf("Keyboard_ascii" to "literal")) shouldBe null
            plan("{Keyboard_ascii}", commits = setOf("Keyboard_ascii")) shouldBe null
        }

        "Escape prefix modifier chords brace literals and invalid syntax keep the old tokenization" {
            keyboardMacroParts("{Escape}/fh{Control+a}{}{") shouldBe listOf("{Escape}/fh", "{Control+a}", "{braceleft}", "{braceright}", "{")
            keyboardMacroParts("") shouldBe emptyList()
            plan("{Keyboard_ascii}{unknown}{Control+a}{}", emptyMap()) shouldBe
                listOf("{Keyboard_ascii}", "{unknown}", "{Control+a}", "{braceleft}", "{braceright}")
        }

        "multiline text retains commands escape prefixes literal braces and supplementary characters" {
            keyboardMacroParts("a{End}\n{}\uD83D\uDE00{Left}\nZ") shouldBe
                listOf("a", "{End}", "\n", "{braceleft}", "{braceright}", "\uD83D\uDE00", "{Left}", "\nZ")
            keyboardMacroParts("{Escape}/fh{Control+a}\n{}\uD83D\uDE00") shouldBe
                listOf("{Escape}/fh", "{Control+a}", "\n", "{braceleft}", "{braceright}", "\uD83D\uDE00")
            keyboardMacroParts("{End}\r\ntext") shouldBe listOf("{End}", "\r\ntext")
            plan("{Keyboard_ascii}\n{inner}", mapOf("inner" to "{Keyboard_native}")) shouldBe
                listOf("{Keyboard_ascii}", "\n", "{Keyboard_native}")
        }

        "cyclic or excessive language aliases fail before partial acceptance" {
            shouldThrow<IllegalStateException> { plan("{Keyboard_ascii}{cycle}", mapOf("cycle" to "{cycle}")) }
            val deep = (0..8).associate { "d$it" to if (it == 8) "{Keyboard_native}" else "{d${it + 1}}" }
            shouldThrow<IllegalStateException> { plan("{d0}", deep) }
            shouldThrow<IllegalStateException> { plan("{Keyboard_ascii}{large}", mapOf("large" to "x".repeat(65537))) }
            shouldThrow<IllegalStateException> { plan("{Keyboard_ascii}" + "{a}".repeat(4096)) }
        }

        "parsing accepts immutable modes before later manual actions while the single FIFO preserves the middle key" {
            for (middle in listOf("a", "{a}")) {
                val state = KeyboardLanguageState()
                var nativeAscii = false
                val queue = mutableListOf<suspend () -> Unit>()
                val operations = mutableListOf<String>()
                for (part in checkNotNull(plan("{Keyboard_ascii}$middle{Keyboard_native}"))) {
                    if (part in setOf("{Keyboard_ascii}", "{Keyboard_native}")) {
                        val command = state.accept(41, "t9", nativeAscii, part == "{Keyboard_ascii}", false, true, KeyboardModeEffect.COMMIT)
                        queue += {
                            val result = applyKeyboardMode(command, { 41 }, { "t9" }, { false }, { nativeAscii }, { true }, {
                                nativeAscii = it
                                operations += "ascii:$it"
                            })
                            state.complete(command, result, 41, "t9")
                        }
                    } else {
                        queue += { operations += "$part:$nativeAscii" }
                    }
                }
                val laterManual = state.accept(41, "t9", nativeAscii, true, false, true, KeyboardModeEffect.COMMIT)
                queue += {
                    val result = applyKeyboardMode(laterManual, { 41 }, { "t9" }, { false }, { nativeAscii }, { true }, {
                        nativeAscii = it
                        operations += "later:$it"
                    })
                    state.complete(laterManual, result, 41, "t9")
                }
                queue.forEach { it() }
                operations shouldBe listOf("ascii:true", "$middle:true", "ascii:false", "later:true")
                state.remembered shouldBe ManualLanguageMode.ASCII
            }
        }
    })
