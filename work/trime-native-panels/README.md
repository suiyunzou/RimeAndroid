# Android 源码入口

本目录是 RimeAndroid 2.32 的 Android 项目，基于 Trime 3.3.13。

- [项目介绍与界面图](../../README.md)
- [构建说明](../../docs/BUILD.md)
- [使用说明](../../docs/USAGE.md)
- [来源与许可](../../THIRD_PARTY.md)
- [原作者](AUTHORS) · [GPL 许可](LICENSE)

配置好 JDK 17 和 Android SDK 后，在本目录执行：

```powershell
./prepare-build.ps1 -Tasks ':app:assembleDebug'
```

本地历史记录和部署脚本没有进入公共仓库。公开版本不依赖作者手机的数据或本机配置。
