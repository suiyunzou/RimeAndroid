/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import com.osfans.trime.R
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsPageFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「许可证」页面：aboutlibraries 生成的第三方库清单与多许可选择。
 *
 * 清单来自打包的 `R.raw.aboutlibraries`，读取完成后重建页面；行为与旧页面一致：
 * 点击某一行，单许可直接打开许可地址，多许可先弹选择列表再打开。
 * 页面顶栏由 MainActivity 提供。
 */
class LicenseFragment : SettingsPageFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    /** 读取完成的库清单；null 表示还在读取或读取失败。 */
    private var libraries: List<Library>? = null

    /** 读取失败的原因，便于在页面上给出可读提示并允许重试。 */
    private var loadError: String? = null

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        loadLibraries()
    }

    override fun buildPage(builder: SettingsBuilder) {
        builder.space(12)
        builder.section("第三方库")
        builder.group().let { card ->
            val items = libraries
            val error = loadError
            if (error != null) {
                // 读取失败时保留重试入口，不显示空卡片。
                builder.row(card, "许可列表读取失败", summary = error) { retryLoadLibraries() }
            } else if (items == null) {
                builder.row(card, "正在读取许可列表…")
            } else if (items.isEmpty()) {
                builder.row(card, "没有可显示的第三方许可")
            } else {
                items.forEach { library ->
                    builder.row(
                        card,
                        "${library.uniqueId}:${library.artifactVersion}",
                        summary = library.licenses.joinToString { license -> license.spdxId ?: license.name },
                    ) {
                        showLicenseDialog(library.uniqueId, library.licenses)
                    }
                }
            }
        }
        builder.space(8)
    }

    override fun onResume() {
        super.onResume()
        // 与旧页面一致：许可证页不显示顶栏的面板操作菜单。
        viewModel.disableTopOptionsMenu()
    }

    /** 在 IO 线程解析打包的许可 JSON，完成后回到主线程重建列表。 */
    private fun loadLibraries() {
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val jsonString =
                            resources
                                .openRawResource(R.raw.aboutlibraries)
                                .bufferedReader()
                                .use { it.readText() }
                        Libs
                            .Builder()
                            .withJson(jsonString)
                            .build()
                            .libraries
                            .sortedBy {
                                if (it.tag == "native") it.uniqueId.uppercase() else it.uniqueId.lowercase()
                            }
                    }
                }
            result
                .onSuccess { loaded ->
                    libraries = loaded
                    loadError = null
                }.onFailure {
                    libraries = null
                    loadError = it.message ?: it.javaClass.simpleName
                }
            rerender()
        }
    }

    private fun retryLoadLibraries() {
        loadError = null
        rerender()
        loadLibraries()
    }

    private fun showLicenseDialog(
        uniqueId: String,
        licenses: Set<License>,
    ) {
        when (licenses.size) {
            0 -> {}
            1 -> showLicenseContent(licenses.first())
            else -> {
                val licenseArray = licenses.toTypedArray()
                val licenseNames = licenseArray.map { it.spdxId ?: it.name }.toTypedArray()
                AlertDialog
                    .Builder(requireContext())
                    .setTitle(uniqueId)
                    .setItems(licenseNames) { _, idx ->
                        showLicenseContent(licenseArray[idx])
                    }.setPositiveButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /** 旧页面的许可详情动作：有地址就打开地址。 */
    private fun showLicenseContent(license: License) {
        license.url
            ?.takeIf { it.isNotBlank() }
            ?.let { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
