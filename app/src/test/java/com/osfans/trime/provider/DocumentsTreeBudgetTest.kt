/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.provider

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.FileNotFoundException
import kotlin.io.path.createTempDirectory

class DocumentsTreeBudgetTest : StringSpec({
    "complete traversal accepts its exact budget and rejects overflow before mutation" {
        val root = createTempDirectory("documents-budget").toFile()
        try {
            val directory = File(root, "nested").apply { check(mkdir()) }
            val original = File(directory, "sentinel").apply { writeText("preserve") }
            fun scan(limit: Int) = DocumentsTreeBudget.snapshot(root, limit, 3, allowed = { true }) { file, _ ->
                checkNotNull(file.listFiles()).toList()
            }
            scan(3).map { it.file }.toSet() shouldBe setOf(root, directory, original)
            shouldThrow<FileNotFoundException> { scan(2) }
            original.readText() shouldBe "preserve"
        } finally { root.deleteRecursively() }
    }

    "depth, cycles and unsafe nodes reject the entire snapshot" {
        val root = createTempDirectory("documents-depth").toFile()
        try {
            val directory = File(root, "child").apply { check(mkdir()) }
            File(directory, "leaf").writeText("preserve")
            shouldThrow<FileNotFoundException> {
                DocumentsTreeBudget.snapshot(root, 10, 1, allowed = { true }) { file, _ -> checkNotNull(file.listFiles()).toList() }
            }
            shouldThrow<FileNotFoundException> {
                DocumentsTreeBudget.snapshot(root, 10, 4, allowed = { true }) { file, _ -> if (file == root) listOf(root) else emptyList() }
            }
            shouldThrow<FileNotFoundException> {
                DocumentsTreeBudget.snapshot(root, 10, 4, allowed = { it != directory }) { file, _ -> checkNotNull(file.listFiles()).toList() }
            }
            File(directory, "leaf").readText() shouldBe "preserve"
        } finally { root.deleteRecursively() }
    }

    "copy byte budget rejects oversize before any mutation" {
        val root = createTempDirectory("documents-bytes").toFile()
        try {
            val file = File(root, "sentinel").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
            fun scan(limit: Long) = DocumentsTreeBudget.snapshot(root, maxBytes = limit, allowed = { true }) { directory, _ ->
                checkNotNull(directory.listFiles()).toList()
            }
            scan(5).sumOf { it.size } shouldBe 5L
            shouldThrow<FileNotFoundException> { scan(4) }
            file.readBytes().toList() shouldBe listOf<Byte>(1, 2, 3, 4, 5)
        } finally { root.deleteRecursively() }
    }

    "copy reads exactly the snapshot and rejects growth, shrinkage and no progress" {
        fun copy(bytes: ByteArray, size: Long): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            DocumentsCopyBudget.copy(bytes.inputStream(), output, size)
            return output.toByteArray()
        }
        copy(byteArrayOf(), 0).toList() shouldBe emptyList()
        copy(byteArrayOf(1, 2, 3), 3).toList() shouldBe listOf<Byte>(1, 2, 3)
        shouldThrow<java.io.IOException> { copy(byteArrayOf(1, 2, 3, 4), 3) }
        shouldThrow<java.io.IOException> { copy(byteArrayOf(1, 2), 3) }
        val growing = object : java.io.InputStream() { override fun read() = 1 }
        shouldThrow<java.io.IOException> { DocumentsCopyBudget.copy(growing, java.io.ByteArrayOutputStream(), 3) }
        val stalled = object : java.io.InputStream() {
            override fun read() = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
        }
        shouldThrow<java.io.IOException> { DocumentsCopyBudget.copy(stalled, java.io.ByteArrayOutputStream(), 3) }
    }
})
