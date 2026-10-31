/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.soundeffect

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException

class OwnedSoundFdAccessTest : StringSpec({
    "missing library fails closed and is loaded only once" {
        var loads = 0
        val access = OwnedSoundFdAccess<Int>(
            load = { loads++; throw UnsatisfiedLinkError("missing") },
            openFd = { _, _, _ -> error("open must not run") },
            closeFd = { error("close must not run") },
            adopt = { it },
        )
        access.open("root", "", "file") shouldBe null
        access.open("root", "", "file") shouldBe null
        loads shouldBe 1
    }

    "unavailable symbol and rejected path do not create an owner" {
        var calls = 0
        val access = OwnedSoundFdAccess<Int>(
            load = {},
            openFd = { _, _, _ -> calls++; if (calls == 1) throw UnsatisfiedLinkError("missing symbol") else -1 },
            closeFd = { error("no fd was returned") },
            adopt = { error("must not adopt") },
        )
        access.open("root", "folder", "file") shouldBe null
        access.open("root", "folder", "file") shouldBe null
    }

    "successful adoption transfers exactly one descriptor" {
        val closed = mutableListOf<Int>()
        val access = OwnedSoundFdAccess<String>(
            load = {}, openFd = { _, _, _ -> 17 }, closeFd = { closed.add(it) },
            adopt = { "owned:$it" },
        )
        access.open("root", "folder", "file") shouldBe "owned:17"
        closed shouldBe emptyList()
    }

    "adoption failure closes the raw fd and preserves original and cleanup errors" {
        val failure = CancellationException("cancelled")
        val cleanup = IllegalStateException("cleanup")
        val closed = mutableListOf<Int>()
        val access = OwnedSoundFdAccess<Int>(
            load = {}, openFd = { _, _, _ -> 23 },
            closeFd = { closed.add(it); throw cleanup },
            adopt = { _: Int -> throw failure },
        )
        shouldThrow<CancellationException> { access.open("root", "", "file") } shouldBe failure
        closed shouldBe listOf(23)
        failure.suppressed.toList() shouldBe listOf(cleanup)
    }

    "fatal loader errors propagate" {
        val failure = OutOfMemoryError("fatal")
        val access = OwnedSoundFdAccess<Int>(
            load = { throw failure }, openFd = { _, _, _ -> 1 }, closeFd = {}, adopt = { it },
        )
        shouldThrow<OutOfMemoryError> { access.open("root", "", "file") } shouldBe failure
    }
})
