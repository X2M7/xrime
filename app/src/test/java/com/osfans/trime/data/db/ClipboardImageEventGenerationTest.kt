/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import com.osfans.trime.ime.core.PrivacyModeState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ClipboardImageEventGenerationTest : StringSpec({
    "each new callback invalidates prior image authorization" {
        PrivacyModeState.initialize(manual = false)
        val events = ClipboardImageEventGeneration()
        val listener = ClipboardSuggestionState<String> {}
        listener.setEnabled(true)
        val admission = requireNotNull(listener.admit())
        val first = events.advance()
        val auth = requireNotNull(events.acquireAuthorization(first) { listener.isAdmissionCurrent(admission) })
        auth.requireAllowed()
        // The new callback can later prove empty or sensitive; it already revoked the old read.
        events.advance()
        shouldThrow<PersistencePermitExpiredException> { auth.requireAllowed() }
        events.isCurrent(first) shouldBe false
        auth.close()
        val second = events.snapshot()
        val next = requireNotNull(events.acquireAuthorization(second) { listener.isAdmissionCurrent(admission) })
        listener.setEnabled(false)
        listener.setEnabled(true)
        shouldThrow<PersistencePermitExpiredException> { next.requireAllowed() }
        next.close()
    }

    "publication is accepted only before event, listener, privacy or close boundaries" {
        PrivacyModeState.initialize(manual = false)
        val events = ClipboardImageEventGeneration()
        fun authorization() = requireNotNull(events.acquireAuthorization(events.advance()) { true })
        val stale = authorization()
        val stalePermit = stale.acquirePersistencePermit()
        events.advance()
        shouldThrow<PersistencePermitExpiredException> { stale.acceptPublication(stalePermit) }
        PrivacyModeState.releasePersistencePermit(stalePermit)
        stale.close()

        val disabled = authorization()
        val disabledPermit = disabled.acquirePersistencePermit()
        events.disable()
        events.advance() // A queued callback cannot re-enable the listener.
        shouldThrow<PersistencePermitExpiredException> { disabled.acceptPublication(disabledPermit) }
        PrivacyModeState.releasePersistencePermit(disabledPermit)
        disabled.close()
        events.enable()

        val closed = authorization()
        val closedPermit = closed.acquirePersistencePermit()
        closed.close()
        shouldThrow<PersistencePermitExpiredException> { closed.acceptPublication(closedPermit) }
        PrivacyModeState.releasePersistencePermit(closedPermit)

        val privateAuth = authorization()
        val privatePermit = privateAuth.acquirePersistencePermit()
        PrivacyModeState.setManual(true)
        shouldThrow<PersistencePermitExpiredException> { privateAuth.acceptPublication(privatePermit) }
        PrivacyModeState.releasePersistencePermit(privatePermit)
        privateAuth.close()
        PrivacyModeState.initialize(manual = false)

        val accepted = authorization()
        val acceptedPermit = accepted.acquirePersistencePermit()
        accepted.acceptPublication(acceptedPermit)
        events.advance()
        PrivacyModeState.setManual(true)
        // A pre-boundary persistence permit remains valid to finish its accepted write.
        PrivacyModeState.allowsPersistence(acceptedPermit) shouldBe true
        shouldThrow<PersistencePermitExpiredException> { accepted.acceptPublication(acceptedPermit) }
        PrivacyModeState.releasePersistencePermit(acceptedPermit)
        accepted.close()
        PrivacyModeState.initialize(manual = false)
    }
})
