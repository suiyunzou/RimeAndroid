# 来源、致谢与许可

本项目保留各来源的署名和许可证。下面说明各项目实际提供了什么，避免把界面参考、应用代码和词典数据混为一谈。

## Trime / Rime

Android 代码基于 [osfans/trime](https://github.com/osfans/trime) 的提交 [`b5cad1a49095a3652e9188982de400ca3a653f37`](https://github.com/osfans/trime/commit/b5cad1a49095a3652e9188982de400ca3a653f37)，对应 Trime 3.3.13。

本项目在此基础上增加原生面板、按键设置和输入交互等修改，当前定制版本为 2.30。原作者信息见 [`AUTHORS`](work/trime-native-panels/AUTHORS)，代码许可见 [`LICENSE`](LICENSE)，为 GPL-3.0-or-later。

预编译原生库来自原版 Trime 3.3.13，库文件哈希及导入记录见 [`prebuilt-provenance.json`](work/trime-native-panels/prebuilt-provenance.json)。其对应源码、依赖子模块版本见 [上游源码](https://github.com/osfans/trime/tree/b5cad1a49095a3652e9188982de400ca3a653f37) 和 [`native-source-lock.json`](docs/native-source-lock.json)。

基础输入资源沿用 Trime 随包的 Rime 资源，包括 [rime-prelude](https://github.com/rime/rime-prelude)、[rime-luna-pinyin](https://github.com/rime/rime-luna-pinyin)、[rime-stroke](https://github.com/rime/rime-stroke)、[rime-essay](https://github.com/rime/rime-essay)。具体署名与许可遵循各来源，不用本项目许可覆盖原有条款。

## ReRime

**明确感谢 [ReRime-IME](https://github.com/ReRime-IME)。** 本项目参考了 ReRime 展示的键盘布局和交互，包括拨动、长按变体和面板组织，并使用其公开发布的万象词典资源作为滑行词表的来源。

- 官方组织：<https://github.com/ReRime-IME>
- 官方网站：<https://rerime.com>
- 公开词典与构建工具：[rerime-dictionaries](https://github.com/ReRime-IME/rerime-dictionaries)

ReRime 的公开词典仓库不包含其应用源码。本项目没有把 ReRime iOS 应用源码或专有滑行模型作为开源来源，也不代表其官方 Android 版本。参考视频未随仓库分发。

## 万象滑行词表

`app/src/main/assets/glide` 来自 ReRime 公开资源版本 `wanxiang-precompiled-50-880ac18dd08e`，词典上游为 [amzxyz/rime-wanxiang](https://github.com/amzxyz/rime-wanxiang)。原版本号用于识别当时的输入数据，不保证上游永久保留历史下载文件。

本项目的处理包括去除拼音声调、将 `ü` 转成 `v`、筛选字母编码，并保留高权重的 80,000 条中文和 30,000 条英文记录。这些来自公开词表，未加入用户学习数据。

保留的文件：

- [CC BY 4.0 原许可](work/trime-native-panels/app/src/main/assets/glide/LICENSE-ReRime-Wanxiang.txt)
- [上游 NOTICE](work/trime-native-panels/app/src/main/assets/glide/NOTICE-ReRime-Wanxiang.md)
- [本地改编说明](work/trime-native-panels/app/src/main/assets/glide/NOTICE-glide.txt)
- [来源和输出哈希](work/trime-native-panels/app/src/main/assets/glide/source-receipt.json)
- [提取源码](work/trime-native-panels/tools/generate_glide_lexicon.py)

ReRime 构建工具的 Apache-2.0 许可与万象词典数据的 CC BY 4.0 许可不同，不应互相替代。

## 其他资源

| 资源 | 来源及说明 |
| --- | --- |
| 简繁转换数据 | [OpenCC](https://github.com/BYVoid/OpenCC)，Apache-2.0；保留 [许可证](docs/licenses/OpenCC-LICENSE.txt) |
| Emoji 字符清单 | 从 Trime 原主题整理，文件内保留出处；显示字形由设备系统提供，未打包 Apple Emoji 字体 |
| `wechat-outline.ttf` | 本项目自行绘制的线条图标，生成源码在 [`work/trime-theme`](work/trime-theme)，随项目使用 GPL-3.0-or-later |
| 按键音 | 本项目通过程序合成，CC0；见 [声音说明](work/trime-native-panels/app/src/main/assets/feedback-tones/NOTICE.txt) |
| 文本摘录 | [eBible.org 新标点和合本（简体）](https://ebible.org/find/details.php?id=cmn-cu89s)；来源提供方声明为公有领域，保留 [来源记录](work/trime-native-panels/app/src/main/assets/book-quotes/SOURCE.json) 和随包版权说明 |
| Gradle Wrapper | [Gradle](https://github.com/gradle/gradle)，Apache-2.0；见 [许可证](docs/licenses/Gradle-LICENSE.txt) |
| Kotlin / Android 依赖 | 版本见 [`libs.versions.toml`](work/trime-native-panels/gradle/libs.versions.toml)；各库继续遵守自身许可，应用使用 AboutLibraries 汇集依赖信息 |

README 的界面图片由本项目的 Android 模拟器生成，不是 ReRime 的产品截图。
