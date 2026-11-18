# 轻声键盘 iOS 预览版

iPhone、iPad 需要 iOS / iPadOS 16 或以上。主 App 使用 Apple Speech 的本机识别，支持选择普通话或英文。结束后可修改草稿，点「复制并清空草稿」，再到目标输入框粘贴。复制内容限本设备，五分钟后过期。本版尚未验证中英文混说效果。

系统键盘扩展提供基础全拼候选、英文、符号、退格、回车和折叠，无需完全访问。键盘扩展无法读取麦克风。全拼基于雾凇拼音的基础词表和单字读音索引，未移植 Android 的完整 Rime 引擎和本地写作模型。

词表来源为 [雾凇拼音](https://github.com/iDvel/rime-ice/tree/927e1cf1d3eeb9bf4be85e3f684dba4dd485b6f6)，遵循 GPL-3.0，许可证随扩展分发。

安装 Xcode 和 XcodeGen 后，在本目录运行 `xcodegen generate`，打开 MicBar.xcodeproj，选择 MicBar。模拟器构建无需开发者签名。真机和 App Store 分发需要为两个 target 配置同一开发团队的签名和可注册的 bundle ID。

0.1.2 的竖屏简体中文键盘在拼写时整行显示候选，选词后按光标前文字推荐后续词句。点建议只补入后续部分，可横滑查看更多候选，底行「工具」可切回工具栏。联想词表包含雾凇基础词典派生的补全和常用口语衔接。

本次预览扩大了空格键，收起了首页的使用说明。真机预览文件为 [iPhone 和 iPad 设备包](https://github.com/murrymujia/MicBar/releases/download/v1.28/MicBar-iOS-0.1.2-unsigned.ipa)。它需要个人签名。已经使用 AltStore Classic 的用户可从 My Apps 的加号导入 IPA，询问是否保留扩展时选择保留键盘。首次使用需要电脑上的 AltServer，免费账号通常每七天刷新一次。参阅 [AltStore 安装指南](https://faq.altstore.io/altstore-classic/how-to-install-altstore-macos)。安装后到「设置 → 通用 → 键盘 → 键盘 → 添加新键盘」选择轻声键盘。
