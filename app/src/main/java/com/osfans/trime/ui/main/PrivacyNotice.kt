/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

/** The packaged notice and repository policy have the same source text. */
internal fun localizedPrivacyNotice(markdown: String, language: String): String {
    val chineseHeading = "# xrime 隐私说明"
    val englishHeading = "# xrime privacy notice"
    val chinese = markdown.substringAfter(chineseHeading, "").substringBefore(englishHeading).trim()
    val english = markdown.substringAfter(englishHeading, "").trim()
    check(chinese.isNotEmpty() && english.isNotEmpty()) { "The bundled privacy notice is incomplete" }
    return (if (language.equals("zh", ignoreCase = true)) chinese else english)
        .replace("\n- ", "\n• ")
}
