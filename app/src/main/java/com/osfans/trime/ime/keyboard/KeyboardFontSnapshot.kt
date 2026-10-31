/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

/** Reuses one snapshot until the refresh interval elapses; callers own its thread. */
internal class ExpiringKeyboardFontSnapshot<T : Any>(
    private val intervalMillis: Long,
    private val now: () -> Long,
    private val load: () -> T,
) {
    init {
        require(intervalMillis > 0)
    }

    private var value: T? = null
    private var loadedAt = 0L

    fun get(): T {
        val currentTime = now()
        val existing = value
        if (existing != null && currentTime >= loadedAt && currentTime - loadedAt < intervalMillis) return existing
        val refreshed = load()
        loadedAt = currentTime
        value = refreshed
        return refreshed
    }
}
