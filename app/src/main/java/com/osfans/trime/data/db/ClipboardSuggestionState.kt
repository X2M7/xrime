/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

/** Serializes listener admission, suggestion invalidation and queued notification identity. */
internal class ClipboardSuggestionState<T : Any>(private val onChanged: (T?) -> Unit) {
    class Update<T : Any>(val bean: T?, val privacyEpoch: Long?)

    private var enabled = false
    private var generation = 0L
    private var current = Update<T>(null, null)

    @Synchronized
    fun setEnabled(value: Boolean): Update<T>? {
        if (enabled == value) return null
        enabled = value
        generation++
        return if (!value) replace(null, null) else null
    }

    @Synchronized
    fun admit(): Long? = if (enabled) generation else null

    @Synchronized
    fun isAdmissionCurrent(admission: Long): Boolean = enabled && admission == generation

    @Synchronized
    fun publish(admission: Long, bean: T, privacyEpoch: Long, condition: () -> Boolean = { true }): Update<T>? {
        if (!enabled || admission != generation || !condition()) return null
        return replace(bean, privacyEpoch)
    }

    @Synchronized
    fun clear(admission: Long): Update<T>? = if (enabled && admission == generation) replace(null, null) else null

    @Synchronized
    fun clear(): Update<T> = replace(null, null)

    @Synchronized
    fun isCurrent(update: Update<T>): Boolean = current === update

    private fun replace(bean: T?, privacyEpoch: Long?): Update<T> {
        val update = Update(bean, privacyEpoch)
        current = update
        onChanged(bean)
        return update
    }
}
