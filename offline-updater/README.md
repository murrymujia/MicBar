# 离线更新工具

这个可选模块在同一 APK 中携带轻量版和完整版各自的差分。它不申请联网权限。系统安装器负责覆盖安装。它只适用于指定的起始版本，安装版型和个人数据沿用原应用。

发布时建立单独的资源目录，将正式发布的两个差分文件复制为 `Lite.mupd` 和 `Full.mupd`。在该目录创建 `release.properties`。其中 `from`、`to` 是起始和目标版本号（例如 1.20 和 1.23）。`Lite.size`、`Full.size` 是两个目标 APK 的字节数，`Lite.sha256`、`Full.sha256` 是对应已签名 APK 的 SHA-256 小写十六进制值。所有值均可从对应 GitHub Release 的资产元数据核对。

在项目根目录运行以下命令，然后用项目的发布签名签署生成的 APK。常规键盘构建无需指定该参数。

```sh
./gradlew -PofflineUpdateAssets=/absolute/path/to/assets :offline-updater:assembleRelease
```
