# 构建说明

## 默认构建方式

此仓库的 Android 项目在 `work/trime-native-panels`。默认复用 Trime 3.3.13 的 arm64 JNI 库和配套基础资源；不需要先编译 C++ 依赖，也不需要初始化 Git 子模块。

需要的环境：

| 组件 | 项目配置 |
| --- | --- |
| JDK | 17 |
| Android SDK Platform | 37 |
| Android Build Tools | 36.0.0 |
| Gradle | 9.8.1，由仓库包装器下载 |
| Android Gradle Plugin / Kotlin | 9.4.1 / 2.3.21 |
| 默认 CPU 架构 | arm64-v8a |

这些版本来自项目当前配置，首次构建需要下载依赖。无需另装系统 Gradle。

### Windows / PowerShell

先将 `JAVA_HOME` 指向 JDK 17 的安装目录，将 `ANDROID_HOME` 指向 Android SDK 根目录。也可以用 Android Studio 生成项目自己的 `local.properties`；该文件会被 Git 忽略。

```powershell
cd work/trime-native-panels
./prepare-build.ps1 -Tasks ':app:assembleDebug'
```

脚本读取环境变量，不包含作者电脑的路径。Windows 上会按已有系统代理设置传递 HTTP 代理；已有 `JAVA_OPTS` 会保留。

### Linux / macOS

配置好相同的环境变量，在 Android 项目目录运行：

```sh
./gradlew :app:assembleDebug --console=plain --no-configuration-cache
```

安装包在 `app/build/outputs/apk/debug/`。这是调试构建，使用编译者本机的调试签名。若已经安装了其他人签名的同包名版本，Android 可能拒绝直接覆盖；处理前先自行备份输入法数据。

## 资源边界

- 仓库内含基础拼音、笔画资源及参考主题。
- 滑行候选表来自公开万象数据，已保留来源、许可和校验值。
- 内置公开精简 v2 整句模型（约 46.2 MiB），来源和 SHA256 随资源保留；完整模型可在应用内按需下载。手机上的万象或雾凇个人配置、个人词库及邮箱规则不在本仓库。
- `app/prebuilt/arm64-v8a/librime_jni.so` 是原版预编译库。来源记录在 [`prebuilt-provenance.json`](../work/trime-native-panels/prebuilt-provenance.json)。该记录涵盖原版库和基础资源，不包含后来新增的参考主题、滑行词表等资源；`checksums.json` 由构建过程重新生成。

如需自行重建原生库或支持其他架构，请从 [Trime 对应源码提交](https://github.com/osfans/trime/tree/b5cad1a49095a3652e9188982de400ca3a653f37) 获取完整源码，并按上游文档准备子模块、NDK 和 CMake。固定的子模块版本记录在 [`native-source-lock.json`](native-source-lock.json)。本次整理未验证其他架构或原生库重建。

## 上传前检查

在仓库根目录运行：

```powershell
python scripts/verify-publication.py
git diff --cached --check -- README.md THIRD_PARTY.md docs scripts
```

检查工具只读取 Git 暂存区。修改后先将准备公开的文件加入暂存区，再检查。它能拦截常见敏感文件和密钥格式，但不能代替截图、数据来源和文本内容的人工复核。
