/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

import com.charleskorn.kaml.Yaml
import com.osfans.trime.core.data.ConfigImportValidator
import com.osfans.trime.util.get
import com.osfans.trime.util.sequence
import com.osfans.trime.util.string
import io.kotest.core.spec.style.StringSpec
import it.krzeminski.snakeyaml.engine.kmp.common.CharConstants
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit

/** Runs malformed-parser regressions in a disposable process: a hang cannot wedge the test worker. */
object YamlUnicodeProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        // Low 16 bits coincide with YAML syntax; none are ASCII syntax characters.
        val problematic = listOf(0x00, 0x09, 0x0a, 0x0d, 0x20, 0x22, 0x23, 0x27, 0x2c, 0x2d, 0x30, 0x3a, 0x3f, 0x5b, 0x5c, 0x5d, 0x7b, 0x7d)
            .map { String(Character.toChars(0x100000 + it)) }
        for (glyph in problematic) {
            val examples = listOf(
                "key: \"a${glyph}b\"\n" to "a${glyph}b",
                "key: 'a${glyph}b'\n" to "a${glyph}b",
                "key: a${glyph}b\n" to "a${glyph}b",
                "key: $glyph\n" to glyph,
                "key: |\n  a${glyph}b\n" to "a${glyph}b\n",
                "key: \"a\n  ${glyph}b\"\n" to "a ${glyph}b",
                "key: \"a ${glyph}\n  b\"\n" to "a ${glyph} b",
            )
            for ((source, expected) in examples) {
                check(ThemeYaml.parser.parseToYamlNode(source)["key"]?.string == expected)
            }
            check(ThemeYaml.parser.parseToYamlNode("key: [$glyph]\n")["key"]?.sequence?.items?.single()?.string == glyph)
            val file = File.createTempFile("yaml-unicode-probe", ".yaml")
            try {
                file.writeText("value: \"$glyph\"\n")
                check(ConfigImportValidator.validate(file, "probe.yaml"))
            } finally {
                file.delete()
            }
        }
        check(!CharConstants.NULL_BL_T_LINEBR.has(0x10005c, "'\"\\"))
        check(CharConstants.NULL_BL_T_LINEBR.hasNo(0x10005c, "'\"\\"))
        check(CharConstants.NULL_BL_T_LINEBR.has('\\'.code, "'\"\\"))
        check(!CharConstants.NULL_BL_T_LINEBR.hasNo('\\'.code, "'\"\\"))
        val aliases = (0..1100).joinToString("\n") { "key$it: *base" }
        check(runCatching { ThemeYaml.parser.parseToYamlNode("base: &base [one, two]\n$aliases\n") }.isFailure)
    }
}

class YamlUnicodeBackportTest :
    StringSpec({
        "the resolved parser handles supplementary characters without hanging or weakening budgets" {
            val paths = linkedSetOf<String>()
            var loader: ClassLoader? = YamlUnicodeProbe::class.java.classLoader
            while (loader != null) {
                if (loader is URLClassLoader) {
                    loader.urLs.filter { it.protocol == "file" }.forEach { paths += File(it.toURI()).path }
                }
                loader = loader.parent
            }
            val probeLoader = YamlUnicodeProbe::class.java.classLoader
            val runtimeClasses = listOf(
                YamlUnicodeProbe::class.java,
                ThemeYaml::class.java,
                Yaml::class.java,
                CharConstants::class.java,
                Unit::class.java,
                Class.forName("kotlinx.serialization.KSerializer", false, probeLoader),
                Class.forName("okio.Source", false, probeLoader),
                Class.forName("net.thauvin.erik.urlencoder.UrlEncoderUtil", false, probeLoader),
            )
            runtimeClasses.forEach {
                paths += File(checkNotNull(it.protectionDomain?.codeSource?.location).toURI()).path
            }
            val executable = File(checkNotNull(System.getProperty("java.home")), "bin/java${if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else ""}")
            val process = ProcessBuilder(executable.path, "-cp", paths.joinToString(File.pathSeparator), YamlUnicodeProbe::class.java.name)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(finished) { "Parser did not terminate: $output" }
            check(process.exitValue() == 0) { "Parser probe failed: $output" }
        }
    })
