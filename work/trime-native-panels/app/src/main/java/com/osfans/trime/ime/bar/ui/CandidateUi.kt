// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.view.View
import com.osfans.trime.R
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add

class CandidateUi(
    override val ctx: Context,
    private val scope: ThemeScope,
    private val compatView: View,
) : Ui {
    private val theme: Theme get() = scope.theme

    val panelBackButton =
        ToolButton(ctx, R.drawable.ic_baseline_keyboard_24, scope).apply {
            visibility = View.GONE
            contentDescription = ctx.getString(R.string.virtual_keyboard)
        }

    val unrollButton =
        ToolButton(ctx, R.drawable.ic_baseline_expand_more_24, scope).apply {
            visibility = View.INVISIBLE
        }

    override val root =
        ctx.constraintLayout {
            add(
                panelBackButton,
                lParams(dp(40)) {
                    centerVertically()
                    startOfParent()
                },
            )
            add(
                unrollButton,
                lParams(dp(40)) {
                    centerVertically()
                    endOfParent()
                },
            )
            add(
                compatView,
                lParams {
                    centerVertically()
                    after(panelBackButton, dp(theme.style.candidatePadding / 2))
                    before(unrollButton)
                },
            )
        }

    /** Restyles the candidate bar after a scheme switch. */
    fun refreshColors() {
        panelBackButton.refreshColors()
        unrollButton.refreshColors()
    }
}
