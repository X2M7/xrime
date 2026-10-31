<!-- SPDX-FileCopyrightText: 2015 - 2026 Rime community -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# xrime 隐私说明

更新时间：2026 年 10 月 30 日。

xrime 基于同文输入法和 Rime。输入法需要处理你在当前应用中输入的文字，以提供候选词和输入功能。用户词典、配置、可选的剪贴板历史及收藏可以保存在设备上；所保存的内容取决于你的设置和操作。关闭历史记录或启用隐私模式不会自动删除此前保存的数据，可在相应的管理页面删除记录或导出、迁移所需数据。

启用剪贴板监听后，符合条件的 PNG 或 JPEG 图片也可能保存在应用私有存储中；图片原始内容可能包含 EXIF、位置等元数据。关闭监听或启用隐私模式不会删除已有图片，可在剪贴板图片页逐项删除。图片文件不参加安卓自动备份，恢复备份后可能出现缺少图片文件的记录。

密码输入框、应用请求不进行个性化学习的输入框，以及手动开启的隐私模式会启用隐私限制，例如暂停学习、剪贴板历史和朗读。切换前已获准写入的剪贴板操作可能仍会完成；切换后开始的操作不会因此获准。不同应用、安卓版本和外部服务的行为不完全由 xrime 控制，请勿将这一模式视为对所有系统或第三方记录的保证。

以下操作可能将内容交给其他应用或服务：

- 你主动选择网页搜索、分享或打开链接时，相关文本或地址会交给你选择的浏览器、搜索应用或分享目标；其处理方式由对方决定。
- 启用按键或上屏朗读时，需朗读的内容会交给设备所选的文字转语音（TTS）引擎。语音输入使用你选择的其他输入法或系统服务，它们可能具有各自的隐私政策和网络行为。
- 选择外部目录或导出数据时，配置、词典或同步数据会交给你选择的存储提供方。系统备份功能也可能根据设备与系统设置备份应用数据。
- 普通复制会将内容放入系统剪贴板；系统和其他有权访问剪贴板的应用可能处理它。“私有复制”将选中文字保存到已启用的本地剪贴板历史，不写入系统剪贴板。日志或诊断信息由你主动查看或分享，可能包含设备信息或其他敏感信息，请在分享前检查。
- 你在支持图片的编辑器中选择粘贴剪贴板图片时，目标应用会获得该图片的临时只读访问权限。约 15 分钟后不能再打开该图片，延迟读取可能失败；已经打开的文件或接收方保存的副本无法撤回。
- 自定义主题和配置可以定义打开链接、调用其他应用或使用当前文本的动作。安装或使用第三方配置前，请检查其来源与命令。

xrime 的主清单声明了开机接收、振动和通知权限；实际可用的系统权限还取决于安卓版本及最终构建。外部应用与服务请求的权限由其自身管理。

# xrime privacy notice

Updated October 30, 2026.

xrime is based on Trime and Rime. It processes text entered in the current app to provide input and candidates. Depending on your settings and actions, user dictionaries, configuration, optional clipboard history, and collections can be stored on the device. Turning off history or enabling privacy mode does not erase previously saved data; use the corresponding management screens to delete records or export data you want to keep.

When clipboard listening is enabled, eligible PNG or JPEG images may also be saved in private app storage; original image data may contain EXIF, location, or other metadata. Disabling listening or enabling privacy mode does not delete saved images; delete them individually on the clipboard images page. Image files are excluded from Android automatic backup, so restored records may refer to missing image files.

Password fields, editors that request no personalized learning, and manually enabled privacy mode activate restrictions such as suspending learning, clipboard history, and speech feedback. Clipboard writes already authorized before a transition may still finish; a later operation does not gain authorization from that earlier approval. xrime cannot control every behavior of Android, other apps, or external services; privacy mode is not a guarantee that they keep no records.

Some user-initiated or optional features transfer information to other apps or services:

- Web search, sharing, or opening a link passes relevant text or an address to the chosen browser, search app, or recipient. That service controls its own processing.
- When keypress or commit speech feedback is enabled, text to be spoken is passed to the selected text-to-speech engine. Voice input uses another selected input method or system service, which may have its own privacy and network practices.
- Selecting an external folder or exporting data gives configuration, dictionaries, or synchronization data to the selected storage provider. System backup may also back up app data according to device and system settings.
- Ordinary copying places content on the system clipboard, which the system and other apps with access may process. Private copy saves selected text in the enabled local clipboard history without writing to the system clipboard. Logs or diagnostics viewed or shared by you may contain device details or other sensitive information; inspect them before sharing.
- Pasting a clipboard image into a supporting editor gives that app temporary read-only access. New opens are rejected after about 15 minutes, so delayed reads may fail; already opened files and copies saved by the recipient cannot be recalled.
- Custom themes and configuration can define actions that open links, launch other apps, or use current text. Review the source and commands of third-party configuration before using it.

The main xrime manifest declares boot reception, vibration, and notification permissions. Actual system permissions also depend on the Android version and final build; other apps and services manage their own permissions.
