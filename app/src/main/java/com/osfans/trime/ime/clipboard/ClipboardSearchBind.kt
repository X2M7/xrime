/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/** A native bind belongs to the service scope and must be cancelled if its waiter ends. */
internal suspend fun awaitClipboardSearchBind(job: Job, timeoutMillis: Long = 4_000): Boolean = try {
    withTimeoutOrNull(timeoutMillis) {
        job.join()
        !job.isCancelled
    } ?: false
} finally {
    if (!job.isCompleted) job.cancel()
}
