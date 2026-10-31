/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.provider

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Copy exactly a preflighted file length; never follow an indefinitely growing source. */
internal object DocumentsCopyBudget {
    fun copy(input: InputStream, output: OutputStream, expectedBytes: Long) {
        require(expectedBytes >= 0)
        val buffer = ByteArray(32 * 1024)
        var remaining = expectedBytes
        var emptyReads = 0
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("Source document became shorter")
            if (count == 0) {
                if (++emptyReads > 16) throw IOException("Source document made no read progress")
                continue
            }
            emptyReads = 0
            output.write(buffer, 0, count)
            remaining -= count
        }
        if (input.read() >= 0) throw IOException("Source document grew during copy")
    }
}
