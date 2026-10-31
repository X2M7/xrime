/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

import android.content.ClipDescription
import android.net.Uri
import android.os.Build
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.annotation.RequiresApi

/** API-isolated so legacy Android can load the IME without InputContentInfo. */
@RequiresApi(Build.VERSION_CODES.N_MR1)
internal object ClipboardImageCommitApi25 {
    fun dispatch(connection: InputConnection, uri: Uri, mime: String): Boolean {
        val description = ClipDescription("Image", arrayOf(mime))
        return connection.commitContent(
            InputContentInfo(uri, description, null),
            InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
            null,
        )
    }
}
