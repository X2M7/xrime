/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main.settings

import com.osfans.trime.core.RimeUnavailableException
import kotlinx.coroutines.CancellationException

/** Check the original UI owner after every suspension and preserve normal cancellation. */
internal suspend fun <T> presentColorPicker(
    create: suspend () -> T,
    ownerActive: () -> Boolean,
    present: (T) -> Unit,
    reportFailure: (Exception) -> Unit,
) {
    try {
        val value = create()
        if (ownerActive()) present(value)
    } catch (failure: CancellationException) {
        if (failure !is RimeUnavailableException) throw failure
        if (ownerActive()) reportFailure(failure)
    } catch (failure: Exception) {
        if (ownerActive()) reportFailure(failure)
    }
}
