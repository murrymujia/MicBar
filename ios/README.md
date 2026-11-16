# 轻声键盘 iOS 预览版

iPhone、iPad 需要 iOS / iPadOS 16 或以上。主 App 使用 Apple Speech 的本机识别，支持选择普通话或英文。结束后可修改草稿，点「复制并清空草稿」，再到目标输入框粘贴。复制内容限本设备，五分钟后过期。本版尚未验证中英文混说效果。

系统键盘扩展提供基础全拼候选、英文、符号、退格、回车和折叠，无需完全访问。键盘扩展无法读取麦克风。全拼基于雾凇拼音的基础词表和单字读音索引，未移植 Android 的完整 Rime 引擎和本地写作模型。

词表来源为 [雾凇拼音](https://github.com/iDvel/rime-ice/tree/927e1cf1d3eeb9bf4be85e3f684dba4dd485b6f6)，遵循 GPL-3.0，许可证随扩展分发。

安装 Xcode 和 XcodeGen 后，在本目录运行 `xcodegen generate`，打开 MicBar.xcodeproj，选择 MicBar。模拟器构建无需开发者签名。真机和 App Store 分发需要为两个 target 配置同一开发团队的签名和可注册的 bundle ID。
