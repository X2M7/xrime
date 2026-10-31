/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import com.osfans.trime.ime.core.PrivacyMode
import com.osfans.trime.ime.core.PrivacyModeState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException

class PrivateImageByteStoreTest : StringSpec({
    fun withStore(test: suspend (PrivateImageByteStore, java.io.File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("image-store-test").toFile()
        try { test(PrivateImageByteStore(root, 4, 6), root) } finally { root.deleteRecursively() }
    }
    "exact byte limit and digest survive publication" {
        withStore { store, root ->
            val staged = store.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))) { }
            staged.sha256 shouldBe "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
            val output = staged.publish({ it.length() shouldBe 4L }, {})
            staged.close()
            output.parentFile shouldBe root.resolve("clipboard-image-published")
            output.readBytes().toList() shouldBe listOf<Byte>(1, 2, 3, 4)
        }
    }
    "oversize, empty, stuck reads and cancellation leave no staged files" {
        withStore { store, root ->
            shouldThrow<IOException> { store.stage(ByteArrayInputStream(ByteArray(5))) {} }
            shouldThrow<IOException> { store.stage(ByteArrayInputStream(ByteArray(0))) {} }
            val stuck = object : InputStream() {
                override fun read() = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
            }
            shouldThrow<IOException> { store.stage(stuck) {} }
            val cancelled = CancellationException("revoked")
            shouldThrow<CancellationException> { store.stage(ByteArrayInputStream(byteArrayOf(1))) { throw cancelled } } shouldBe cancelled
            root.resolve("clipboard-image-staging").listFiles()!!.size shouldBe 0
        }
    }
    "validator cannot silently alter validated bytes and rejection cleans up" {
        withStore { store, root ->
            val staged = store.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3))) {}
            shouldThrow<IOException> { staged.publish({ it.writeBytes(byteArrayOf(1, 2, 4)) }, {}) }
            staged.close()
            root.resolve("clipboard-image-staging").listFiles()!!.size shouldBe 0
            root.resolve("clipboard-image-published").listFiles()!!.size shouldBe 0
        }
    }
    "revocation and total quota preserve previously published bytes" {
        withStore { store, root ->
            val first = store.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))) {}.publish({}, {})
            val staged = store.stage(ByteArrayInputStream(byteArrayOf(5, 6, 7))) {}
            shouldThrow<IOException> { staged.publish({}, {}) }
            staged.close()
            val revoked = store.stage(ByteArrayInputStream(byteArrayOf(8))) {}
            var checks = 0
            shouldThrow<CancellationException> { revoked.publish({}, { if (++checks == 2) throw CancellationException("revoked") }) }
            revoked.close()
            first.readBytes().toList() shouldBe listOf<Byte>(1, 2, 3, 4)
            root.resolve("clipboard-image-staging").listFiles()!!.size shouldBe 0
        }
    }
    "staging reservations bound concurrent objects and are released on close" {
        withStore { _, root ->
            val firstStore = PrivateImageByteStore(root, 4, 6, stagingLimit = 8, maxStagedImages = 2)
            val secondStore = PrivateImageByteStore(root, 4, 6, stagingLimit = 8, maxStagedImages = 2)
            val first = firstStore.stage(ByteArrayInputStream(byteArrayOf(1))) {}
            val second = secondStore.stage(ByteArrayInputStream(byteArrayOf(2))) {}
            shouldThrow<IOException> { firstStore.stage(ByteArrayInputStream(byteArrayOf(3))) {} }
            first.close()
            val third = firstStore.stage(ByteArrayInputStream(byteArrayOf(3))) {}
            second.close()
            third.close()
            root.resolve("clipboard-image-staging").listFiles()!!.size shouldBe 0
        }
    }
    "staging byte reservations bound capacity even below the operation count" {
        withStore { _, root ->
            val store = PrivateImageByteStore(root, 4, 6, stagingLimit = 6, maxStagedImages = 2)
            val first = store.stage(ByteArrayInputStream(byteArrayOf(1))) {}
            shouldThrow<IOException> { store.stage(ByteArrayInputStream(byteArrayOf(2))) {} }
            first.close()
            val second = store.stage(ByteArrayInputStream(byteArrayOf(2))) {}
            second.close()
        }
    }
    "explicit revocation and listening generation expire capture authorization" {
        PrivacyModeState.initialize(manual = false)
        var listening = true
        val revoked = requireNotNull(ImageCaptureAuthorization.acquire { listening })
        revoked.close()
        shouldThrow<PersistencePermitExpiredException> { revoked.requireAllowed() }
        val changed = requireNotNull(ImageCaptureAuthorization.acquire { listening })
        listening = false
        shouldThrow<PersistencePermitExpiredException> { changed.requireAllowed() }
        ImageCaptureAuthorization.acquire { listening } shouldBe null
        changed.close()
    }
    "capture accepts only a persistence permit issued for the same epoch" {
        PrivacyModeState.initialize(manual = false)
        val old = requireNotNull(PrivacyModeState.acquirePersistencePermit())
        try {
            PrivacyModeState.beginInput(779, PrivacyMode())
            val capture = requireNotNull(ImageCaptureAuthorization.acquire { true })
            try {
                PrivacyModeState.allowsPersistence(old) shouldBe true
                shouldThrow<PersistencePermitExpiredException> { capture.requireMatchingPermit(old) }
                val current = capture.acquirePersistencePermit()
                try {
                    capture.requireMatchingPermit(current)
                } finally {
                    PrivacyModeState.releasePersistencePermit(current)
                }
            } finally {
                capture.close()
            }
        } finally {
            PrivacyModeState.releasePersistencePermit(old)
            PrivacyModeState.initialize(manual = false)
        }
    }
    "a privacy boundary during read rejects returned bytes before writing" {
        withStore { store, root ->
            PrivacyModeState.initialize(manual = false)
            try {
                val authorization = requireNotNull(ImageCaptureAuthorization.acquire { true })
                val source = object : InputStream() {
                    override fun read(): Int = error("not used")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        PrivacyModeState.beginInput(778, PrivacyMode(password = true))
                        buffer[offset] = 42
                        return 1
                    }
                }
                var lengthAtRevocation = -1L
                shouldThrow<PersistencePermitExpiredException> {
                    store.stage(source) {
                        if (PrivacyModeState.active) {
                            lengthAtRevocation = root.resolve("clipboard-image-staging").listFiles()!!.single().length()
                        }
                        authorization.requireAllowed()
                    }
                }
                lengthAtRevocation shouldBe 0L
                root.resolve("clipboard-image-staging").listFiles()!!.size shouldBe 0
                PrivacyModeState.endInput(778)
                shouldThrow<PersistencePermitExpiredException> { authorization.requireAllowed() }
                authorization.close()
            } finally {
                PrivacyModeState.initialize(manual = false)
            }
        }
    }
    "a separately held OS lock prevents staging and recovery" {
        withStore { store, root ->
            java.io.RandomAccessFile(root.resolve(".clipboard-image.lock"), "rw").use { lockFile ->
                lockFile.channel.lock().use {
                    shouldThrow<IOException> { store.stage(ByteArrayInputStream(byteArrayOf(1))) {} }
                    shouldThrow<IOException> { store.withExclusiveRecovery { _, _ -> } }
                }
            }
            val staged = store.stage(ByteArrayInputStream(byteArrayOf(1))) {}
            staged.close()
        }
    }
    "exclusive recovery refuses a live stage and an unknown stage blocks capture" {
        withStore { store, root ->
            val staged = store.stage(ByteArrayInputStream(byteArrayOf(1))) {}
            shouldThrow<IOException> { store.withExclusiveRecovery { _, _ -> } }
            staged.close()
            val unknown = root.resolve("clipboard-image-staging/unknown").apply { writeBytes(byteArrayOf(2)) }
            shouldThrow<IOException> { store.stage(ByteArrayInputStream(byteArrayOf(1))) {} }
            unknown.exists() shouldBe true
            check(unknown.delete())
            val next = store.stage(ByteArrayInputStream(byteArrayOf(3))) {}
            next.close()
        }
    }
    "publication retains the OS reservation through the metadata callback" {
        withStore { store, _ ->
            val image = store.stage(ByteArrayInputStream(byteArrayOf(1, 2))) {}
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coroutineScope {
                val writer = async {
                    image.publishAndCommit({}, {}) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                entered.await()
                shouldThrow<IOException> { store.withExclusiveRecovery { _, _ -> } }
                release.complete(Unit)
                writer.await().readBytes().toList() shouldBe listOf<Byte>(1, 2)
            }
            store.withExclusiveRecovery { _, _ -> }
            image.close()
        }
    }
    "an ambiguous post-rename failure preserves the object for recovery" {
        withStore { store, root ->
            val image = store.stage(ByteArrayInputStream(byteArrayOf(1, 2))) {}
            shouldThrow<IOException> { image.publishAndCommit({}, {}) { throw IOException("unknown commit outcome") } }
            image.close()
            root.resolve("clipboard-image-published").listFiles()!!.single().readBytes().toList() shouldBe listOf<Byte>(1, 2)
            store.withExclusiveRecovery { _, _ -> }
        }
    }
    "concurrent publishers across instances cannot exceed quota" {
        withStore { store, root ->
            val other = PrivateImageByteStore(root, 4, 6)
            val one = store.stage(ByteArrayInputStream(ByteArray(4) { 1 })) {}
            val two = other.stage(ByteArrayInputStream(ByteArray(4) { 2 })) {}
            val outcomes = coroutineScope { listOf(one, two).map { item -> async { runCatching { item.publish({}, {}) }.isSuccess } }.awaitAll() }
            outcomes.count { it } shouldBe 1
            one.close()
            two.close()
            root.resolve("clipboard-image-published").listFiles()!!.sumOf { it.length() } shouldBe 4L
        }
    }
})
