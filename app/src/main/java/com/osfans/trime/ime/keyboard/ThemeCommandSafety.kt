/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import android.content.ActivityNotFoundException
import java.net.URISyntaxException
import java.util.IllegalFormatException

private val placeholderPattern = Regex(".*(%([1-4]\\$)?s).*")

/** Preserve valid theme formatting while refusing malformed format strings. */
internal fun expandThemeCommand(input: String, activeText: (Int) -> String): String? {
    if (!input.matches(placeholderPattern)) return input
    return try {
        input.format(activeText(1), activeText(2), activeText(3), activeText(4))
    } catch (_: IllegalFormatException) {
        null
    }
}

/** A missing, malformed or forbidden external target is a failed action, not an IME crash. */
internal inline fun <T> launchThemeCommand(create: () -> T?, launch: (T) -> Unit): Boolean {
    return try {
        val target = create() ?: return false
        launch(target)
        true
    } catch (_: URISyntaxException) {
        false
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}
