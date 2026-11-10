// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady

class MainViewModel : ViewModel() {
    val toolbarTitle = MutableLiveData<String>()

    val topOptionsMenu = MutableLiveData<Boolean>()

    val rime = RimeDaemon.createSession(javaClass.name)

    val restartBackgroundSyncWork = MutableLiveData(false)

    val toolbarEditButtonVisible = MutableLiveData(false)

    val toolbarEditButtonOnClickListener = MutableLiveData<(() -> Unit)?>()

    val toolbarDeleteButtonOnClickListener = MutableLiveData<(() -> Unit)?>()

    fun setToolbarTitle(title: String) {
        toolbarTitle.value = title
    }

    fun enableTopOptionsMenu() {
        topOptionsMenu.value = true
    }

    fun disableTopOptionsMenu() {
        topOptionsMenu.value = false
    }

    fun enableToolbarEditButton(
        visible: Boolean = true,
        onClick: () -> Unit,
    ) {
        toolbarEditButtonOnClickListener.value = onClick
        toolbarEditButtonVisible.value = visible
    }

    fun disableToolbarEditButton() {
        toolbarEditButtonOnClickListener.value = null
        hideToolbarEditButton()
    }

    fun hideToolbarEditButton() {
        toolbarEditButtonVisible.value = false
    }

    fun showToolbarEditButton() {
        toolbarEditButtonVisible.value = true
    }

    fun enableToolbarDeleteButton(onClick: () -> Unit) {
        toolbarDeleteButtonOnClickListener.value = onClick
    }

    fun disableToolbarDeleteButton() {
        toolbarDeleteButtonOnClickListener.value = null
    }

    /**
     * The single entry point for "deploy".
     *
     * Both the toolbar action of [MainActivity] and the 「部署」 row of the 「高级」 page call this,
     * so the two stay identical: fire the deploy once Rime is ready, and never block the caller.
     * This is exactly what the toolbar did inline before the advanced page started offering deploy.
     */
    fun deploy() {
        rime.launchOnReady { it.deploy() }
    }

    override fun onCleared() {
        RimeDaemon.destroySession(javaClass.name)
    }
}
