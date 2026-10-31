/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main.settings

import com.osfans.trime.core.RimeUnavailableException
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class ColorPickerPresentationTest : StringSpec({
    "load failure is reported and a subsequent attempt can show a picker" {
        val failures = mutableListOf<Exception>()
        val shown = mutableListOf<String>()
        suspend fun attempt(create: suspend () -> String) = presentColorPicker(create, { true }, { shown.add(it) }, { failures.add(it) })
        val failure = IllegalStateException("theme unavailable")
        attempt { throw failure }
        failures shouldBe listOf(failure)
        shown shouldBe emptyList()
        attempt { "picker" }
        shown shouldBe listOf("picker")
    }

    "engine unavailability is reported but ordinary cancellation propagates" {
        val failures = mutableListOf<Exception>()
        val unavailable = RimeUnavailableException(null)
        presentColorPicker<String>({ throw unavailable }, { true }, {}, { failures.add(it) })
        failures shouldBe listOf(unavailable)
        val cancelled = CancellationException("owner destroyed")
        val observed = try {
            presentColorPicker<String>({ throw cancelled }, { true }, {}, { failures.add(it) })
            null
        } catch (failure: CancellationException) { failure }
        (observed === cancelled) shouldBe true
        failures shouldBe listOf(unavailable)
    }

    "an owner lost while loading receives neither a picker nor an error" {
        coroutineScope {
            var active = true
            var shown = 0
            var failed = 0
            val loading = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val job = async {
                presentColorPicker(
                    create = { loading.complete(Unit); release.await(); "picker" },
                    ownerActive = { active },
                    present = { shown++ },
                    reportFailure = { failed++ },
                )
            }
            loading.await()
            active = false
            release.complete(Unit)
            job.await()
            shown shouldBe 0
            failed shouldBe 0
            presentColorPicker<String>({ error("unavailable") }, { active }, { shown++ }, { failed++ })
            shown shouldBe 0
            failed shouldBe 0
        }
    }
})
