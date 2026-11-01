<!--
SPDX-FileCopyrightText: 2015 - 2024 Rime community
SPDX-License-Identifier: GPL-3.0-or-later
-->

# xrime

xrime 是基于 [Trime](https://github.com/osfans/trime)、[X2M7/trime](https://github.com/X2M7/trime) 和 [Rime](https://rime.im/) 输入法引擎的独立 Android 输入法。应用 ID 为 `com.x2m7.xrime`，可与原 Trime 同时安装；旧应用的私有数据不会自动迁移。

[English](README.md) · [繁體中文](README_tc.md) · [项目主页](https://x2m7.github.io/xrime/) · [下载](https://github.com/X2M7/xrime/releases)

## 下载与状态

当前版本为 **[v0.1.0-alpha.1](https://github.com/X2M7/xrime/releases/tag/v0.1.0-alpha.1)**，是支持 Android 5.0 及以上版本的 Alpha 预发布版。兼容的手机请选择 ARM64 APK；x86_64 APK 适用于对应设备或模拟器。测试前请将词典和配置备份到独立位置。

部分上游问题仍待处理；完整 lint 仍有构建插件升级提示；音效包目录扫描在特定文件名和 Android 运行环境下存在崩溃风险。ARM64 安装包尚未在实体设备上验证。已测范围和未解决问题详见 [验证记录](docs/xrime-verification.md)与[上游问题清单](docs/xrime-upstream-issues.md)。

## 获取源码与构建

递归克隆以获取原生依赖：

```sh
git -c core.autocrlf=false -c core.eol=lf clone --recurse-submodules https://github.com/X2M7/xrime.git xrime
```

固定源码及本工作区 Windows 构建环境见[源码获取与构建说明](docs/source-checkout.md)。应用 ID、签名和历史工具限制见[身份说明](docs/xrime-identity.md)，数据处理方式见[隐私说明](PRIVACY.md)。

## 参与贡献与许可证

欢迎通过 [xrime Issues](https://github.com/X2M7/xrime/issues) 反馈，并参阅[贡献指南](CONTRIBUTING.md)。项目采用 [GPL-3.0-or-later](LICENSE) 许可证。感谢 Rime、Trime 社区及 [AUTHORS](AUTHORS) 中列出的贡献者；第三方声明随源码保留。
