# xrime 验证记录

本记录是开发过程中的证据，不代表所有上游 issue、漏洞或设备兼容问题均已解决。项目保留在本地，尚未推送；当前软件包身份为 `com.x2m7.xrime`，与原 Trime 分开安装。

## 2026-10-30 的测试快照

- D 盘独立 Linux 环境以真实 Linux 文件系统和 JDK 17 执行 `:app:testDebugUnitTest`，122 个测试套件、1010 项测试全部通过，0 项跳过。该次使用官方校验的 Linux AAPT2 离线覆盖项，Gradle 对该临时选项给出实验性警告；它不是应用发行构建配置。结果保存在 `D:\xrime-tools\linux-test\linux-full-results-v2`，构建日志为 `D:\xrime-tools\linux-test\gradle-full-v2.log`。该快照不包含此后新增的改动。
- Windows 宿主的较早全量测试有 27 项失败：11 项无法创建符号链接，1 项缺少 Unix shell，另外 15 项依赖 Linux/Android 原子覆盖文件的行为。未为通过测试而删除旧文件或弱化断言；上述对应测试在 Linux 全量运行中通过。Windows 报告保存在 `D:\xrime-tools\linux-test\windows-full-results-20261030`。
- Android 5 上真实用户词库持久事务、OpenCC 转换及剪贴板/收藏编辑通过；日志位于 `D:\xrime-tools\instrument-api21-dictionary-t10-current.log`、`instrument-api21-opencc-atomic-v1.log`、`instrument-api21-clipsave-v5.log` 和 `instrument-api21-clipreal-v5.log`。
- Android 15 的图片生产监听、系统 URI 捕获、独立 UID 的只读授权及实际向外部编辑器发送通过设备探针；发送覆盖接收方权限申请、图片哈希、MIME 不匹配、双击、慢接收方和编辑器切换。Android 7 测试镜像拒绝为测试来源的 URI 发放系统读取授权，捕获安全拒绝。日志为 `D:\xrime-tools\instrument-api35-image-listener-v9.log`、`instrument-api35-image-provider-v9.log` 和 `instrument-api35-image-send-v2.log`；其他设备和应用兼容性仍需验证。
- Android 14 使用上游 #2145 原附件的原生部署、特殊字符键盘、Markdown 文本和稳定光标检查通过；独立宏测试覆盖导航与提交次序、Shift 及真实拼音组合。日志为 `D:\xrime-tools\instrument-api34-issue2145-ui-fixed-v6.log` 和 `D:\xrime-tools\instrument-api34-macro-order-v8.log`。
- Android 15 的 16 KiB 页大小 x86_64 模拟器通过调试包实际输入与键盘几何检查；这不等于 ARM64、发布构建或全部设备已通过。
- 本地 Semgrep Community Edition 1.183.0 使用规则仓库提交 `16b9e6d45f10649f6a531ec4838ed9924372c3bc` 扫描应用源码、JNI 接口和清单，运行 29 条规则、检查 390 个文件，提示两个导出入口。入口分别用于启动器和系统输入法设置；外部参数正进一步审查。报告为 `D:\xrime-tools\semgrep-results-v1.json`，不能据此断定不存在其他问题。

## 后续快照

- v20 的 x86_64 调试包和设备测试包构建及所选音效、文件预算单元测试通过，Gradle 进程退出码为 0；日志为 `D:\xrime-tools\build-current-v20.log`，固定包位于 `D:\xrime-tools\device-batch-v20`。这不是当前之后修改的最终源码验证。
- v23 构建退出码为 0，但构建日志同时记录了多次本机 Gradle 连接握手超时；来源尚未确定，不能将此次构建称为无警告。日志为 `D:\xrime-tools\build-current-v23.log`。
- Android 7 和 Android 15 的文件提供器探针验证了普通复制删除、目录深度及节点限制、链接防护和超过 512 MiB 的稀疏文件拒绝；日志为 `D:\xrime-tools\instrument-api24-documents-budget-v18.log` 和 `D:\xrime-tools\instrument-api35-documents-budget-v18.log`。并发更改和一般 IO 故障仍可能留下部分复制结果，兼容限制见 [文件提供器说明](documents-provider-limits.md)。
- Android 15 的 v20 音效探针在音量设置为 0 时验证了自定义 WAV 加载和 SoundPool 接受播放请求，并检查音效切换和句柄关闭；这不证明扬声器实际有声，也不能确定原 #2103 报告的根因。Android 7 的旧文件描述符审计因测试镜像不允许校准部分系统句柄而失败，v21 独立音效行为探针通过但未执行该审计。日志为 `D:\xrime-tools\instrument-api35-feedback-v20.log`、`D:\xrime-tools\instrument-api24-feedback-v20.log` 和 `D:\xrime-tools\instrument-api24-feedback-audio-v21.log`。
- v29 首阶段音效安全文件打开组件已接入，但尚未替换生产 SoundPool 加载路径。Android 5 项目内设备探针通过（`D:\xrime-tools\instrument-api21-sound-open-v29.log`）；x86_64 调试包中该库的 ELF 段对齐为 16 KiB，APK 的 `zipalign -c -P 16` 检查通过。该库在包内压缩后约 3 KiB；ARM64、发布包和生产接线仍待验证。由于 JNI 库使用压缩打包，ZIP 检查不能替代 ELF 对齐检查。
- v41 固定包的音效回归在 Android 5 与 Android 15 通过，设备仪器均返回 `passed=true`、`INSTRUMENTATION_CODE=-1`。Android 5 验证了自定义 WAV 加载、零音量设置下的播放请求、系统音默认/显式音量及关闭路径和严格的缺失文件拒绝；未执行文件描述符审计。Android 15 还完成 200 次配置扫描及目标文件描述符审计，检查音效切换和释放。日志为 `D:\xrime-tools\instrument-api21-feedback-audio-v41.log`、`D:\xrime-tools\instrument-api35-feedback-v41.log`。这些结果不证明扬声器实际发声、音色或静音听感。
- v43 的 Android 5 与 Android 15 冷启动设置探针均返回 `passed=true`、`INSTRUMENTATION_CODE=-1`：在测试进程模拟设置向导已显示后，观察到真实 MainActivity 与对话框显示期间音效状态仍未初始化，真实音效选择对话框选中保存的配置；此观察不能排除更晚发生的后台任务。测试前临时切换到系统输入法，结束后两台设备均恢复并核验原默认输入法；日志和恢复记录为 `D:\xrime-tools\instrument-emulator-5560-sound-picker-v43.log`、`D:\xrime-tools\instrument-emulator-5554-sound-picker-v43.log` 及各自 `-restore.log`。该测试不覆盖真实首次设置向导流程。v41、v42 冷启动仪器的前置失败日志仍保留，分别反映应用初始化等待和设置向导跳转，不能作为 v43 成功结果。
- Android 14 的 v20 受限宽度候选栏检查观察到候选正文视图左侧越界；v21/v22 检查误把 TextView 内部超宽布局坐标当作视图坐标，不能作为真实位置判定。v24 使用视图边界、文字宽度及省略状态检查，短正文加长注释及正文自身超长的视图边界在两套内置主题均通过，完整候选动作回归也通过；日志为 `D:\xrime-tools\instrument-api34-candidate2138-v20.log` 和 `D:\xrime-tools\instrument-api34-candidate2138-v24.log`。原始第三方词典仍需验证。
- Android 15 的 v19 编辑面板通过复制、剪切、粘贴和方向键；返回检查当时因对象身份变化失败。v20 诊断到返回的布局 ID、九键状态和语言状态相同，之后已增加完整按键和几何比较，仍需设备重跑。

上游 issue 的逐项状态见 [核对清单](xrime-upstream-issues.md)。发布版和 ARM64 的后续验证结果见下文；完整 lint、真实设备、图片发送/隐私竞态和性能对照仍需继续验证。

## 2026-10-31 补充验证

当前源码的 v61r 隔离 Linux 快照运行完整 JVM 测试，真实退出码 0：应用 1044 项、构建兼容性 9 项、构建约定 46 项，共 1099 项，失败、错误和跳过均为 0。快照与日志位于 `D:\xrime-tools\linux-test\`，该测试使用单独的 Linux aapt2 选项，不代表正式发布配置；快照后仅测试探针与本文档有调整。

回退 AGP 9.4.1 后的 x86_64 未签名发布包已通过 lintVital、R8 守卫及 ZIP 对齐检查；其本机测试签名副本在独立 Android 5 模拟器完成首次向导、Rime 部署、主线程协程调度与真实中文输入。原始包位于 `D:\xrime-tools\release-audit\current-x86_64-v63\xrime-release-unsigned.apk`，大小 9886334 字节，SHA-256 `efe0cb0eec0c68e18262d358de2b8a4a8a3ae10900ecb63c81574e9504b284db`。模拟器首次因未挂载存储失败，补建 D 盘镜像后保留数据重试成功。

ARM64 未签名发布包构建通过，大小 9790972 字节，SHA-256 `f16b1a21002345f85137903c11cb43b23a88582fb19e1a4d39a03cbf35cb3904`；ZIP 完整性、16 KB ZIP 对齐和两个 ARM64 原生库 16 KB 加载段对齐通过。包位于 `D:\xrime-tools\release-audit\current-arm64-v70\xrime-release-unsigned.apk`；尚未在 ARM 真机运行，也未进行正式签名。

完整 lint 仍有三条 AGP 9.4.1 可升级至 9.5.0 的提示，项目将其作为错误；官方 Lifecycle 检查在 9.5 下与 API21 兼容版本冲突，因此回退并保留检查，详细见构建兼容说明。音效目录扫描在启用 CheckJNI 的 Android 5 与 Android 15 隔离测试中遇到特定非法或补充平面文件名字节可导致 Java 目录枚举进程退出；生产扫描仍使用同类枚举，该风险尚未修复。安全子任务被自动审查中止，实验原型未合入，不得将该项标记为已解决。
