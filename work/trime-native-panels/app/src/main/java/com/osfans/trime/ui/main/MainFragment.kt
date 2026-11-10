/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.osfans.trime.ime.smart.FeedbackTuningActivity
import com.osfans.trime.ime.smart.OfflinePrivacyActivity
import com.osfans.trime.ime.smart.SmartSettingsActivity
import com.osfans.trime.ime.smart.WordLibraryActivity
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsPalette
import com.osfans.trime.util.navigateWithAnim

/**
 * The page the launcher opens.
 *
 * It is built with the same [SettingsBuilder] kit as the keyboard settings page, so the app's
 * front page, the keyboard page and the word-library page share one iOS-style grouped look
 * (light `#F2F2F6` page, white cards, `›` rows) instead of the old `androidx.preference` list.
 * Every entry of the former preference screen is kept; entries that only existed on the
 * keyboard page (词库 / 声音与触感 / 隐私) are offered here as well.
 */
class MainFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onStart() {
        super.onStart()
        viewModel.enableTopOptionsMenu()
    }

    override fun onStop() {
        viewModel.disableTopOptionsMenu()
        super.onStop()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val builder = SettingsBuilder(context, SettingsPalette.isNightMode(context))
        val page =
            ScrollView(context).apply {
                isFillViewport = true
                clipToPadding = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(builder.palette.background)
                setPadding(dp(16), dp(8), dp(16), dp(28))
            }
        page.addView(builder.body)
        // The app identity and IME status live in the redesigned top bar (MainActivity) now,
        // so the page no longer repeats a 「同文输入法」 card that the top bar already shows.
        section(builder, "键盘")
        builder.group().let { card ->
            builder.row(card, "键盘设置", summary = "外观、中英文切换、按键拨动与空格轮盘") {
                startActivity(Intent(context, SmartSettingsActivity::class.java))
            }
            builder.row(card, "打开键盘体验", summary = "在此输入文字，长按空格可打开快捷轮盘") {
                showTestKeyboard()
            }
            builder.row(card, "虚拟键盘", summary = "全局键盘高度、垂直偏移与按键行为") {
                findNavController().navigateWithAnim(NavigationRoute.VirtualKeyboard)
            }
            builder.row(card, "候选窗口", summary = "候选词数量、字号与布局") {
                findNavController().navigateWithAnim(NavigationRoute.CandidatesWindow)
            }
            builder.row(card, "键盘样式", summary = "主题、配色与渐变") {
                findNavController().navigateWithAnim(NavigationRoute.Theme)
            }
            builder.row(card, "常规", summary = "语言、状态栏与启动行为") {
                findNavController().navigateWithAnim(NavigationRoute.General)
            }
        }

        section(builder, "输入与词库")
        builder.group().let { card ->
            builder.row(card, "方案", summary = "选择输入方案并查看方案文件") {
                findNavController().navigateWithAnim(NavigationRoute.SchemaList)
            }
            builder.row(card, "用户词典", summary = "查看与编辑 Rime 学习到的词条") {
                findNavController().navigateWithAnim(NavigationRoute.UserDict)
            }
            builder.row(card, "词库", summary = "公开词包版本、检查更新与我的词条") {
                startActivity(Intent(context, WordLibraryActivity::class.java))
            }
            builder.row(card, "本地规则", summary = "用 Lua 扩展输入规则") {
                findNavController().navigateWithAnim(NavigationRoute.LocalRules)
            }
        }

        section(builder, "反馈与隐私")
        builder.group().let { card ->
            builder.row(card, "声音与触感", summary = "试听音色与调节按键反馈") {
                startActivity(Intent(context, FeedbackTuningActivity::class.java))
            }
            builder.row(card, "隐私", summary = "离线运行与本地数据") {
                startActivity(Intent(context, OfflinePrivacyActivity::class.java))
            }
        }

        section(builder, "数据与备份")
        builder.group().let { card ->
            builder.row(card, "高级", summary = "方案、部署与数据备份") {
                findNavController().navigateWithAnim(NavigationRoute.Advanced)
            }
            builder.row(card, "配置", summary = "恢复与备份输入法配置") {
                findNavController().navigateWithAnim(NavigationRoute.Profile)
            }
        }

        section(builder, "关于")
        builder.group().let { card ->
            builder.row(card, "开发者", summary = "诊断、日志与调试选项") {
                findNavController().navigateWithAnim(NavigationRoute.Developer)
            }
            builder.row(card, "关于", summary = "版本信息与项目主页") {
                findNavController().navigateWithAnim(NavigationRoute.About)
            }
            builder.row(card, "许可证", summary = "开源许可与第三方组件") {
                findNavController().navigateWithAnim(NavigationRoute.License)
            }
        }
        builder.space(8)
        return page
    }

    private fun section(
        builder: SettingsBuilder,
        title: String,
    ) {
        builder.space(12)
        builder.section(title)
    }

    private fun showTestKeyboard() {
        (activity as? MainActivity)?.showTestKeyboard()
    }

    private fun dp(value: Int): Int = SettingsPalette.dp(requireContext(), value)
}
