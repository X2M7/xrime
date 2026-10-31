/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import com.osfans.trime.ime.core.PrivacyModeState
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** A stricter capture lease; unlike persistence permits it expires at any privacy/editor boundary. */
internal class ImageCaptureAuthorization private constructor(
    private val epoch: Long,
    private val listeningIsCurrent: () -> Boolean,
    private val acceptEvent: () -> Boolean,
) : Closeable {
    private val revoked = AtomicBoolean(false)
    private val acceptanceLock = Any()

    fun requireAllowed() {
        if (revoked.get() || !listeningIsCurrent() || !PrivacyModeState.isOrdinaryEpoch(epoch)) {
            throw PersistencePermitExpiredException()
        }
    }

    /** The caller owns the returned permit and must release it after its transaction. */
    fun acquirePersistencePermit(): Long {
        requireAllowed()
        val permit = PrivacyModeState.acquirePersistencePermit(epoch) ?: throw PersistencePermitExpiredException()
        try {
            requireAllowed()
            return permit
        } catch (failure: Throwable) {
            PrivacyModeState.releasePersistencePermit(permit)
            throw failure
        }
    }

    fun requireMatchingPermit(permit: Long) {
        requireAllowed()
        if (!PrivacyModeState.isPersistencePermitFromEpoch(permit, epoch)) throw PersistencePermitExpiredException()
    }

    /**
     * Linearize publication after validation without holding any lock during file or Room IO.
     * A later event/privacy transition cannot retroactively revoke this accepted transaction.
     */
    fun acceptPublication(permit: Long) = synchronized(acceptanceLock) {
        if (revoked.get()) throw PersistencePermitExpiredException()
        var accepted = false
        PrivacyModeState.publishIfOrdinary(permit) {
            accepted = PrivacyModeState.isPersistencePermitFromEpoch(permit, epoch) && acceptEvent()
        }
        if (!accepted) throw PersistencePermitExpiredException()
    }

    override fun close() = synchronized(acceptanceLock) {
        revoked.set(true)
    }

    companion object {
        fun acquire(
            acceptEvent: (() -> Boolean)? = null,
            listeningIsCurrent: () -> Boolean,
        ): ImageCaptureAuthorization? {
            if (!listeningIsCurrent()) return null
            val epoch = PrivacyModeState.observeOrdinaryPersistence() ?: return null
            return ImageCaptureAuthorization(epoch, listeningIsCurrent, acceptEvent ?: listeningIsCurrent).takeIf {
                !it.revoked.get() && listeningIsCurrent() && PrivacyModeState.isOrdinaryEpoch(epoch)
            }
        }
    }
}
