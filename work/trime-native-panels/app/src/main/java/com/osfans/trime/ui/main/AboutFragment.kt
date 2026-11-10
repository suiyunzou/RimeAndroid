/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsPageFragment
import com.osfans.trime.util.Const
import com.osfans.trime.util.formatDateTime
import com.osfans.trime.util.toast

/**
 * 「关于」页面：版本、构建信息、项目链接、许可与交流群。
 *
 * 行为与旧页面一致：每一条版本行都跳到对应的代码提交，源码/隐私/许可/交流群都打开同一个 URL；
 * 构建信息较长，放在副标题里换行显示，旧页面的「可复制」改为长按整行复制。
 * 页面顶栏由 MainActivity 提供。
 */
class AboutFragment : SettingsPageFragment() {
    override fun buildPage(builder: SettingsBuilder) {
        val ctx = requireContext()

        // 版本
        builder.space(12)
        builder.section("版本")
        builder.group().let { card ->
            builder.row(card, ctx.getString(R.string.current_version), Const.VERSION_NAME) {
                openUrl("${BuildConfig.BUILD_GIT_REPO}/commit/${BuildConfig.BUILD_COMMIT_HASH}")
            }
            builder.row(card, ctx.getString(R.string.librime_version), BuildConfig.LIBRIME_VERSION) {
                openUrl("${Const.LIBRIME_URL}/commit/${getCommitFromVersionName(BuildConfig.LIBRIME_VERSION)}")
            }
            builder.row(card, ctx.getString(R.string.opencc_version), BuildConfig.OPENCC_VERSION) {
                openUrl("${Const.OPENCC_URL}/commit/${getCommitFromVersionName(BuildConfig.OPENCC_VERSION)}")
            }
            val buildInfo =
                ctx.getString(
                    R.string.build_info_format,
                    BuildConfig.BUILDER,
                    BuildConfig.BUILD_COMMIT_HASH,
                    formatDateTime(BuildConfig.BUILD_TIMESTAMP),
                )
            // 旧页面用 isCopyingEnabled 让构建信息可复制；这里保留长按整行复制。
            val buildInfoRow = builder.row(card, ctx.getString(R.string.build_info), summary = buildInfo)
            buildInfoRow.setOnLongClickListener {
                copyToClipboard(ctx.getString(R.string.build_info), buildInfo)
                true
            }
        }

        // 项目与许可
        builder.space(12)
        builder.section("项目与许可")
        builder.group().let { card ->
            builder.row(card, ctx.getString(R.string.privacy_policy)) {
                openUrl(Const.PRIVACY_POLICY_URL)
            }
            builder.row(card, ctx.getString(R.string.source_code), ctx.getString(R.string.git_repo)) {
                openUrl(BuildConfig.BUILD_GIT_REPO)
            }
            builder.row(card, ctx.getString(R.string.license), Const.LICENSE_SPDX_ID) {
                openUrl(Const.LICENSE_URL)
            }
            builder.row(
                card,
                ctx.getString(R.string.open_source_licenses),
                summary = ctx.getString(R.string.licenses_of_third_party_libraries),
            ) {
                navigate(NavigationRoute.License)
            }
        }

        // 联系我们
        builder.space(12)
        builder.section("联系我们")
        builder.group().let { card ->
            builder.row(card, ctx.getString(R.string.qq_group_1), Const.QQ_GROUP_1_NUM) {
                openUrl(Const.QQ_GROUP_1_URL)
            }
            builder.row(card, ctx.getString(R.string.qq_group_2), Const.QQ_GROUP_2_NUM) {
                openUrl(Const.QQ_GROUP_2_URL)
            }
            builder.row(card, ctx.getString(R.string.rime_qq_group), Const.RIME_QQ_GROUP_NUM) {
                openUrl(Const.RIME_QQ_GROUP_URL)
            }
            builder.row(card, ctx.getString(R.string.telegram), Const.TELEGRAM_NAME) {
                openUrl(Const.TELEGRAM_URL)
            }
        }
        builder.space(8)
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun copyToClipboard(
        label: String,
        text: String,
    ) {
        val ctx = requireContext()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        if (clipboard.hasPrimaryClip()) {
            ctx.toast(R.string.copy_done)
        }
    }

    companion object {
        private val DASH_G_PATTERN = Regex("^(.*-g)([0-9a-f]+)(.*)$")
        private val COMMON_PATTERN = Regex("^([^-]*)(-.*)$")

        private fun getCommitFromVersionName(versionCode: String): String {
            val dashG = DASH_G_PATTERN.find(versionCode)?.groupValues?.get(2)
            val common = COMMON_PATTERN.find(versionCode)?.groupValues?.get(1)
            return dashG ?: common ?: versionCode
        }
    }
}
