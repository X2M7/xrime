/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import android.content.ClipData
import java.util.concurrent.CancellationException

/** Limit exception handling to untrusted platform clipboard data extraction. */
internal object ClipboardReadSafety {
    inline fun <T> readOrNull(read: () -> T): T? = try {
        read()
    } catch (failure: RuntimeException) {
        if (failure is CancellationException || failure is PersistencePermitExpiredException) throw failure
        null
    }

    fun sensitiveOrUnreadable(clip: ClipData): Boolean =
        readOrNull { SensitiveClipboardCompat.isSensitive(clip.description) } ?: true
}
