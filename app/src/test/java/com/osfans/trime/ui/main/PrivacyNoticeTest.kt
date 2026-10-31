/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class PrivacyNoticeTest :
    StringSpec({
        "the bundled notice is identical to the repository policy" {
            File("src/main/res/raw/privacy_notice.md").readBytes().toList() shouldBe File("../PRIVACY.md").readBytes().toList()
        }

        "Chinese devices show Chinese and other devices fall back to English" {
            val text = File("src/main/res/raw/privacy_notice.md").readText(Charsets.UTF_8)
            val chinese = localizedPrivacyNotice(text, "zh")
            val english = localizedPrivacyNotice(text, "en")
            chinese shouldContain "文字转语音"
            chinese shouldContain "私有复制"
            chinese shouldContain "不写入系统剪贴板"
            chinese shouldNotContain "xrime privacy notice"
            english shouldContain "text-to-speech"
            english shouldContain "Private copy"
            english shouldContain "without writing to the system clipboard"
            english shouldNotContain "隐私说明"
            localizedPrivacyNotice(text, "fr") shouldBe english
        }
    })
