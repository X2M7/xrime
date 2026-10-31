/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

/** Preserve cancellation or the original failure when resource cleanup also fails. */
internal inline fun closePreservingFailure(primary: Throwable?, close: () -> Unit) {
    try {
        close()
    } catch (cleanup: Throwable) {
        if (primary == null) throw cleanup
        if (cleanup !== primary) primary.addSuppressed(cleanup)
    }
}
