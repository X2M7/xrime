/* SPDX-License-Identifier: GPL-3.0-or-later */

import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Fixes a code-point truncation in the pinned KAML scanner dependency. */
internal object YamlUnicodeBackport {
    const val JAR = "snakeyaml-engine-kmp-jvm-4.0.1.jar"
    const val JAR_SHA = "0a139cd3d79b8be82a9f85a79e316d23f31fe074468d0680baf61bbef3bf8d85"
    const val CLASS = "it/krzeminski/snakeyaml/engine/kmp/common/CharConstants.class"
    const val CLASS_SHA = "ee84365ae684f0b83a7dc562c2d14a55b730f63ff4fdac983d221b5ba1296e89"
    const val SCANNER = "it/krzeminski/snakeyaml/engine/kmp/scanner/ScannerImpl.class"
    const val SCANNER_SHA = "5f5de7816e7207407c93a107f053d89db2a51d8efe82d924416f66d248f1cd2e"
    private const val SCANNER_OWNER = "it/krzeminski/snakeyaml/engine/kmp/scanner/ScannerImpl"
    private const val SAFE_CHAR = "trimeCodePointToSyntaxChar"

    fun isTarget(name: String): Boolean {
        require(!name.startsWith("snakeyaml-engine-kmp-jvm-") || name == JAR) {
            "Unreviewed SnakeYAML KMP artifact: $name"
        }
        return name == JAR
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun patchJar(input: File, output: File) {
        require(input.canonicalFile != output.canonicalFile && input.isFile)
        require(sha256(input.readBytes()) == JAR_SHA) { "SnakeYAML KMP input changed; audit before building" }
        val patched = mutableSetOf<String>()
        ZipFile(input).use { source ->
            val entries = source.entries().asSequence().toList()
            require(entries.map { it.name }.distinct().size == entries.size) { "Duplicate entries in parser JAR" }
            require(entries.none { it.name.startsWith("META-INF/") && listOf(".SF", ".RSA", ".DSA", ".EC").any(it.name::endsWith) }) {
                "Signed parser JAR cannot be modified"
            }
            ZipOutputStream(output.outputStream().buffered()).use { dest ->
                for (entry in entries) {
                    val original = source.getInputStream(entry).use { it.readBytes() }
                    val data = when (entry.name) {
                        CLASS -> {
                            require(sha256(original) == CLASS_SHA) { "Parser character class changed; audit before building" }
                            patched += entry.name
                            patchClass(original)
                        }
                        SCANNER -> {
                            require(sha256(original) == SCANNER_SHA) { "Parser scanner changed; audit before building" }
                            patched += entry.name
                            patchScanner(original)
                        }
                        else -> original
                    }
                    dest.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                    dest.write(data)
                    dest.closeEntry()
                }
            }
        }
        require(patched == setOf(CLASS, SCANNER)) { "Expected exactly two parser classes, got $patched" }
    }

    private fun patchClass(input: ByteArray): ByteArray {
        val reader = ClassReader(input)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        var methods = 0
        var inserted = 0
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val downstream = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != "has" || descriptor != "(ILjava/lang/String;)Z") return downstream
                methods++
                return object : MethodVisitor(Opcodes.ASM9, downstream) {
                    override fun visitMethodInsn(opcode: Int, owner: String, method: String, desc: String, isInterface: Boolean) {
                        super.visitMethodInsn(opcode, owner, method, desc, isInterface)
                        if (opcode == Opcodes.INVOKESTATIC && owner == "kotlin/jvm/internal/Intrinsics" &&
                            method == "checkNotNullParameter" && desc == "(Ljava/lang/Object;Ljava/lang/String;)V"
                        ) {
                            inserted++
                            val original = Label()
                            super.visitVarInsn(Opcodes.ILOAD, 1)
                            super.visitLdcInsn(0xffff)
                            super.visitJumpInsn(Opcodes.IF_ICMPLE, original)
                            super.visitInsn(Opcodes.ICONST_0)
                            super.visitInsn(Opcodes.IRETURN)
                            super.visitLabel(original)
                            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
                        }
                    }
                }
            }
        }, 0)
        require(methods == 1 && inserted == 1) { "Unexpected parser method structure: $methods methods, $inserted patch sites" }
        return writer.toByteArray()
    }

    /** Preserve non-BMP code points when comparing scanner input to ASCII syntax. */
    private fun patchScanner(input: ByteArray): ByteArray {
        val reader = ClassReader(input)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        val expected = mapOf(
            "fetchMoreTokens" to 1,
            "checkPlain" to 2,
            "scanYamlDirectiveNumber" to 2,
            "scanBlockScalar" to 2,
            "scanBlockScalarIndicators" to 2,
            "scanFlowScalarNonSpaces" to 5,
            "scanFlowScalarSpaces" to 1,
            "scanFlowScalarBreaks" to 1,
            "scanPlain" to 1,
            "scanPlainSpaces" to 2,
        )
        val found = mutableMapOf<String, Int>()
        var collision = false
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val downstream = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name == SAFE_CHAR) collision = true
                if (name !in expected) return downstream
                return object : MethodVisitor(Opcodes.ASM9, downstream) {
                    override fun visitInsn(opcode: Int) {
                        if (opcode != Opcodes.I2C) return super.visitInsn(opcode)
                        val index = (found[name] ?: 0) + 1
                        found[name] = index
                        // These four casts follow explicit isSupplementaryCodePoint checks.
                        if (name == "scanFlowScalarNonSpaces" && index > 1) {
                            super.visitInsn(opcode)
                        } else {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SCANNER_OWNER, SAFE_CHAR, "(I)C", false)
                        }
                    }
                }
            }

            override fun visitEnd() {
                require(!collision && found == expected) { "Unexpected scanner structure: $found" }
                val method = super.visitMethod(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC, SAFE_CHAR, "(I)C", null, null)
                method.visitCode()
                val bmp = Label()
                method.visitVarInsn(Opcodes.ILOAD, 0)
                method.visitLdcInsn(0xffff)
                method.visitJumpInsn(Opcodes.IF_ICMPLE, bmp)
                method.visitLdcInsn(0xfffd)
                method.visitInsn(Opcodes.IRETURN)
                method.visitLabel(bmp)
                method.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
                method.visitVarInsn(Opcodes.ILOAD, 0)
                method.visitInsn(Opcodes.I2C)
                method.visitInsn(Opcodes.IRETURN)
                method.visitMaxs(0, 0)
                method.visitEnd()
                super.visitEnd()
            }
        }, 0)
        return writer.toByteArray()
    }
}

@CacheableTransform
abstract class YamlUnicodeBackportTransform : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        if (!YamlUnicodeBackport.isTarget(input.name)) {
            outputs.file(input)
            return
        }
        YamlUnicodeBackport.patchJar(input, outputs.file(input.name))
    }
}
