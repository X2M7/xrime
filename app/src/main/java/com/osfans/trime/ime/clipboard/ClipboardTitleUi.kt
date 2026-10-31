/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.content.Context
import android.view.View
import com.osfans.trime.R
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.ui.ToolButton
import com.osfans.trime.ime.core.InputTabLayout
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.wrapContent

class ClipboardTitleUi(override val ctx: Context, private val scope: ThemeScope) : Ui {
    private val theme: Theme get() = scope.theme

    val tabLayout = InputTabLayout(ctx)

    val deleteAllButton = ToolButton(ctx, R.drawable.ic_baseline_delete_sweep_24, scope).apply {
        id = View.generateViewId()
    }
    val returnAfterPasteButton = ToolButton(ctx, R.drawable.ic_baseline_keyboard_24, scope).apply {
        id = View.generateViewId()
        accessibilityLabel = ctx.getString(R.string.clipboard_return_after_paste)
    }
    val moreActionsButton = ToolButton(ctx, R.drawable.ic_baseline_more_horiz_24, scope).apply {
        id = View.generateViewId()
        accessibilityLabel = ctx.getString(R.string.clipboard_more_actions)
    }
    val searchButton = ToolButton(ctx, R.drawable.ic_baseline_search_24, scope).apply {
        id = View.generateViewId()
        accessibilityLabel = ctx.getString(R.string.clipboard_search)
    }
    val clearSearchButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_back_24, scope).apply {
        id = View.generateViewId()
        accessibilityLabel = ctx.getString(R.string.clipboard_search_clear)
        visibility = View.GONE
    }

    private val size = theme.style.run { candidateViewHeight + commentHeight }

    override val root = constraintLayout {
        add(
            tabLayout,
            lParams(wrapContent, dp(size)) {
                startOfParent()
                endToStartOf(clearSearchButton)
                centerVertically()
                constrainedWidth = true
                horizontalBias = 0f
            },
        )
        add(
            clearSearchButton,
            lParams(dp(size), dp(size)) {
                centerVertically()
                endToStartOf(searchButton)
            },
        )
        add(
            searchButton,
            lParams(dp(size), dp(size)) {
                centerVertically()
                endToStartOf(moreActionsButton)
            },
        )
        add(
            moreActionsButton,
            lParams(dp(size), dp(size)) {
                centerVertically()
                endToStartOf(returnAfterPasteButton)
            },
        )
        add(
            returnAfterPasteButton,
            lParams(dp(size), dp(size)) {
                centerVertically()
                endToStartOf(deleteAllButton)
            },
        )
        add(
            deleteAllButton,
            lParams(dp(size), dp(size)) {
                centerVertically()
                endOfParent()
            },
        )
    }
}
