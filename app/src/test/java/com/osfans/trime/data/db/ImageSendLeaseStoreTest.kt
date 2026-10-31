/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

class ImageSendLeaseStoreTest : StringSpec({
    fun withStore(test: (ImageSendLeaseStore, java.io.File, (Long) -> Unit, (Int) -> Unit) -> Unit) {
        val root = Files.createTempDirectory("image-lease-test").toFile().canonicalFile
        var time = 1000L
        var boot = 1
        try { test(ImageSendLeaseStore(root, { time }, { boot }), root, { time = it }, { boot = it }) }
        finally { root.deleteRecursively() }
    }
    "lease is limited to its target UID and expires monotonically" {
        withStore { store, _, time, _ ->
            val id = UUID.randomUUID().toString()
            val lease = store.create(id, 12345)
            store.find(lease.token, 12345) shouldBe lease
            store.find(lease.token, 12346) shouldBe null
            store.revoke(lease.token, 12346) shouldBe false
            store.hasActive(id) shouldBe true
            time(lease.expiresAt)
            store.find(lease.token, 12345) shouldBe null
            store.hasActive(id) shouldBe false
        }
    }
    "explicit revoke invalidates only the matching token and UID" {
        withStore { store, _, _, _ ->
            val id = UUID.randomUUID().toString()
            val first = store.create(id, 12345)
            val second = store.create(id, 12346)
            store.revoke(first.token, 12345) shouldBe true
            store.find(first.token, 12345) shouldBe null
            store.find(second.token, 12346) shouldBe second
        }
    }
    "reboot expires old leases and malformed entries prevent cleanup" {
        withStore { store, root, _, boot ->
            val id = UUID.randomUUID().toString()
            val lease = store.create(id, 12345)
            val directory = root.resolve("clipboard-image-leases")
            val unknown = directory.resolve("unknown").apply { writeBytes(byteArrayOf(1)) }
            boot(2)
            shouldThrow<IOException> { store.find(lease.token, 12345) }
            directory.resolve("${lease.token}.lease").exists() shouldBe true
            check(unknown.delete())
            store.find(lease.token, 12345) shouldBe null
        }
    }
    "capacity refuses an additional lease without touching active ones" {
        withStore { store, root, _, _ ->
            val id = UUID.randomUUID().toString()
            val entries = List(ImageSendLeaseStore.MAX_LEASES) { store.create(id, 10000 + it) }
            shouldThrow<IOException> { store.create(id, 20000) }
            root.resolve("clipboard-image-leases").listFiles()!!.size shouldBe ImageSendLeaseStore.MAX_LEASES
            store.find(entries.first().token, entries.first().targetUid) shouldBe entries.first()
        }
    }
})
