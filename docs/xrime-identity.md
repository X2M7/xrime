# xrime 应用身份与发布状态

xrime 的 Android application ID 为 `com.x2m7.xrime`，显示名称为 `xrime`。代码命名空间保持 `com.osfans.trime`；这是代码类名，不是安装包身份。新 ID 使系统将 xrime 与旧 Trime 视为独立应用，应用私有数据不会自动迁移。Android 15 模拟器已同时安装 xrime 调试包与 X2M7/trime v3.3.13-t9.18，系统包列表和输入法列表均显示两个独立应用；该验证未清除应用数据或更改默认输入法。旧用户词典、配置和同步资料需单独导出并导入，迁移尚未完成设备验证。若两个应用手动选用同一外部数据目录，仍可能共享或互相改写数据。

`release.properties` 指定 xrime 独立仓库、应用 ID、Alpha 版本和发布证书指纹。原有 `trime.forkReleaseIdentity` 签名选项仍被拒绝。xrime 的发布密钥独立于 Trime，私钥和密码不得提交到仓库；后续 APK 更新必须使用同一签名密钥。历史 Trime 发布记录可在 [X2M7/trime 仓库](https://github.com/X2M7/trime)查阅。

旧 `script/quality` 及 `script/baseline` 中的一些设备工具仍硬编码 `com.osfans.trime.debug`，用于历史 Trime 评估；不要将它们作为 xrime 验收脚本运行。移植这些脚本前需逐项检查目标包、ADB 操作和旧应用数据保护。Android 类名、JNI 和 Gradle 插件中保留的 `com.osfans.trime` 与安装 ID 不同，不应机械替换。
