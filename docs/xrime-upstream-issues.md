# 上游开放 Issue 核对清单

快照时间：2026-10-30 14:13（北京时间，UTC+08:00）。来源：[osfans/trime 开放 issues](https://github.com/osfans/trime/issues)，通过 GitHub 搜索开放 issue 按创建时间正序、倒序各取 100 条后按编号去重，共 114 条。快照不是实时状态，不含拉取请求或已关闭条目。

表中分类主要依据标题进行初步分流，并非逐条核实正文、评论、设备或当前代码。下文列出的部分条目已获得单元或模拟器证据；其余条目仍须核实。功能与设计请求需要评估需求和取舍，求助或讨论需先确认适用范围。标为异常的条目也可能已经过时、依赖外部配置，或者包含多个问题。

后续验证记录：Android 15 和 Android 17 模拟器上的设备探针已验证 #2033 的完整弹出标签与动作；#2193 在 Android 17 启用 TalkBack FOSS 时通过生产 View 的无障碍动作和几何探针，但尚未验证实际触摸探索与语音播报。#2145 的 YAML 扫描器修复通过 JVM 测试和 Android DEX 检查；Android 14 模拟器上的原始附件原生部署、特殊字符提示键盘和 Markdown 代码块按键输出通过，完整性能及其他设备仍待验收。设备和测试结果只覆盖所列场景，不能据此推定相关 issue 的所有报告均已解决。

已识别的关联代码证据：上游提交 [e1e0ece9](https://github.com/osfans/trime/commit/e1e0ece9) 针对 #1879 在候选替换工具栏后忽略统一工具栏动作；xrime 另保护了独立隐藏键和剪贴板建议回调，Android 15 真触摸回归见下表。#1443 有候选修复：当键盘未指定宽度时采用主题 `style.key_width`，并新增 [宽度解析单元测试](../app/src/test/java/com/osfans/trime/ime/keyboard/KeyboardWidthTest.kt)；相关单元测试通过，尚未进行设备回归验证。

#2033 的弹出键盘原先将预设键标签截为首个 Unicode 码点；候选修复保留完整标签，扩展的 [设备探针](../app/src/androidTest/java/com/osfans/trime/PopupColorRegression.kt) 已在 Android 15 和 Android 17 模拟器检查多字符标签及其触发动作。#1316 的原高度解析在键盘与主题均未配置总高度、且用户未覆盖高度时可得到零；液态窗口跟随该高度。候选修复在未指定总高度时按实际分行与行高计算自然高度，并为缺失行高和空布局提供回退；新增 [分行单元测试](../app/src/test/java/com/osfans/trime/ime/keyboard/KeyboardRowsTest.kt) 和实际键盘模型设备探针，相关单元测试通过；液态窗口、横屏与分屏设备回归尚待验证。#1657 当前代码已将 YAML 内联映射解析为独立的 `text` 和 `label`，已有测试分别覆盖内联动作和混合按键序列；原 issue 所给配置的完整输入行为尚未在设备上验证。

| 编号 | 标题 | 初步分类与下一步 | 状态 / 证据 |
| --- | --- | --- | --- |
| [#2193](https://github.com/osfans/trime/issues/2193) | 不支持配合Talkback使用 | 无障碍兼容性 | Android 17 的生产 View 动作及几何探针通过；实际触摸探索和语音播报待验证 |
| [#2190](https://github.com/osfans/trime/issues/2190) | [功能请求] 为剪贴板历史增加关键词搜索 | 剪贴板历史搜索 | 已实现并在 Android 15 覆盖英文、26 键和九键中文候选、筛选与编辑器隔离；完整编辑器回归及其他设备验收仍在进行 |
| [#2168](https://github.com/osfans/trime/issues/2168) | 使用 Chrome 在网页文本框弹出对话框时会收起键盘 | 异常报告：待复现 | Android 16 Google Play x86_64 + Chrome 133：GitHub 移动和桌面搜索框、Chrome 地址栏的方案弹窗均未触发收起；原报告为三星真机，旧版 Chrome 不能代表最新版本，原因和最新版/真机复现待查； [Chromium 相关报告](https://issues.chromium.org/issues/572515598) 记录 Chrome Beta 156/Gboard 焦点移动后的隐藏请求，但未确认根因，不能据此判定本项原因；记录及截图保存在 D:\xrime-tools\issue2168-reproduction.md |
| [#2159](https://github.com/osfans/trime/issues/2159) | 软件自带的标准键盘和预设键盘选取配色后表现不一致 | 内置键盘配色 | Android 15 上标准及预设键盘的 Google+、Google 和丹青真实按键颜色与绘制探针通过；其他设备和自定义主题仍待验证 |
| [#2151](https://github.com/osfans/trime/issues/2151) | 剪贴板提示取消按钮保持 | 固定提示中的取消按钮位置 | 修复后的生产布局在 Android 5、15、17 模拟器上通过三种宽度、长短文本及按钮点击的独立布局探针；完整输入法窗口交互待复核 |
| [#2145](https://github.com/osfans/trime/issues/2145) | 昨天的夜版，按键含有`hint`参数，输入法不能唤起。点击app卡在灰色界面不能进入。输入非常卡 | YAML 扫描器及原生部署多行标量问题 | JVM 测试、DEX 检查和原生多行标量往返通过；Android 14 原附件部署、特殊字符键盘、Markdown 代码块输出及稳定光标通过（D:\xrime-tools\instrument-api34-issue2145-ui-fixed-v6.log），宏次序和真实拼音组合也通过独立回归；完整性能及其他设备仍待验收 |
| [#2140](https://github.com/osfans/trime/issues/2140) | Cannot use external storage | 更新后外部用户目录授权失败 | 原截图为目录选择失败并回退应用私有目录；评论称 LineageOS/GrapheneOS 更新后复现，清除应用数据后恢复。清数据会丢失应用内数据，不能作为自动修复；Android 15 隔离 provider 探针覆盖授权撤销与失败回滚，Android 7 真实系统目录选择器通过新建目录、持久读写授权和生产写入替换（`D:\xrime-tools\instrument-api24-system-picker-v24.log`）。原系统、Profile 设置选目录及保留旧授权的覆盖升级尚未复现，根因未确认。xrime 是独立应用，不能继承 Trime 的目录授权，需重新选择授权 |
| [#2138](https://github.com/osfans/trime/issues/2138) | 我在自定义一些功能时遇到的两个问题 | 长候选及悬浮窗翻页 | Android 14 的受限宽度生产候选视图中复现正文左侧越界；调整注释宽度约束后，两套内置主题的短正文加长注释、正文自身超长时的视图边界及候选操作回归通过。纵向翻页按钮已扩大并增加间距，Android 14 两主题的视图几何和无障碍点击通过（`D:\xrime-tools\instrument-api34-candidate2138-v27.log`）；实际悬浮窗翻页及原始第三方词典仍待验证 |
| [#2120](https://github.com/osfans/trime/issues/2120) | 剪切板支持图片 | 图片剪贴板 | Android 15 生产监听的实际系统复制、隐私与替换事件、独立 UID 授权读取和真实编辑器跨进程发送通过；发送回归覆盖 MIME 拒绝、双击、阻塞接收方及编辑器切换（D:\xrime-tools\instrument-api35-image-send-v2.log）。v42 图片页在普通重启、隐私/焦点切换及反复切页后通过显示、置顶与删除回归（D:\xrime-tools\instrument-api35-image-refresh-v4.log，完整 editors passed=true、CODE=-1）；但此前 v39/v41 曾出现页内有记录而缩略图未显示，原因尚未定位（D:\xrime-tools\instrument-api35-image-refresh-v2.log、D:\xrime-tools\instrument-api35-image-refresh-v3.log）。Android 7 测试镜像对测试来源拒绝系统 URI 授权；其他设备与更广泛兼容性待验证 |
| [#2112](https://github.com/osfans/trime/issues/2112) | 编辑中拼音点击错位 | 拼音预编辑光标映射 | Android 14 复现 `zhong guo` 分词空格导致的原始光标偏移；限定可明确对应的英文拼音与显示装饰后，四个实际触摸位置通过（D:\xrime-tools\instrument-api34-preedit2112-v4.log）。九键和其他自定义转换不使用该映射，原设备及其他输入方案待验证 |
| [#2107](https://github.com/osfans/trime/issues/2107) | Suggestion: add a "private copy" that bypasses the system clipboard | 私有复制到本地历史 | 已提供剪贴板菜单入口和同文风复制键长按；Android 14 跨 UID 验证系统剪贴板内容和变更事件保持不变，并覆盖关闭历史、超长、慢读取及隐私/编辑器转代，等待后台任务结束后无迟到写入（D:\xrime-tools\instrument-api34-private-copy-v2.log）；其他设备待验证 |
| [#2103](https://github.com/osfans/trime/issues/2103) | 自定义音效无声音 | 系统音量模式与资源释放 | 修复自定义音效将设置中的 0（系统音量模式）误当静音的问题；Android 7 和 15 已验证真实 WAV 加载与 SoundPool 接受播放请求，Android 15 还验证了句柄关闭（`D:\xrime-tools\instrument-api24-feedback-audio-v21.log`、`D:\xrime-tools\instrument-api35-feedback-v20.log`）。未测量实际扬声器声音，原报告的 Android 13 设备和音效包根因未确认；更严格的安全后台加载仍在隔离验证 |
| [#2094](https://github.com/osfans/trime/issues/2094) | 悬浮键盘和全面屏模式 | 功能或设计：待评估 | 未验证 |
| [#2085](https://github.com/osfans/trime/issues/2085) | 做了一個線上主題編輯器 | 求助或讨论：待研判 | 未验证 |
| [#2068](https://github.com/osfans/trime/issues/2068) | 如何用backspace左滑来实现选择文字并删除？（滑动自由向左选词且松开即删除） | 求助或讨论：待研判 | 未验证 |
| [#2048](https://github.com/osfans/trime/issues/2048) | Bug | 异常报告：待复现 | 未验证 |
| [#2044](https://github.com/osfans/trime/issues/2044) | 滑动空格时通过震动表示光标移动 | 功能或设计：待评估 | 未验证 |
| [#2033](https://github.com/osfans/trime/issues/2033) | popup的label显示不正常 | 完整标签显示 | 候选修复通过 Android 15 和 17 模拟器的标签及动作探针 |
| [#2016](https://github.com/osfans/trime/issues/2016) | [BUG] custom preset_keyboards/default not applied after deployment (v3.3.10) | 自定义主题加载与部署 | 源解析测试覆盖继承配置和斜杠补丁回退；Android 14 的继承主题经原生编译、真实按键输入和修改后重新部署均通过，斜杠补丁及原第三方主题待设备验证 |
| [#2015](https://github.com/osfans/trime/issues/2015) | 像preset_keyboards添加键盘布局一样，给liquid_keyboard添加各种不同的布局。 | 功能或设计：待评估 | 未验证 |
| [#2011](https://github.com/osfans/trime/issues/2011) | 随浏览器无痕模式一起无痕 | 功能或设计：待评估 | 未验证 |
| [#2009](https://github.com/osfans/trime/issues/2009) | 關於切換輸入法的問題 | 求助或讨论：待研判 | 未验证 |
| [#2008](https://github.com/osfans/trime/issues/2008) | 在选取配色时会崩溃 | 冷启动时主题尚未初始化 | 原日志显示 `_activeTheme` 未初始化；Android 17 独立模拟器从其他输入法冷启动，在主题未初始化时进入设置、打开并再次打开配色选择通过（`D:\xrime-tools\instrument-api37-color-cold-v6.log`）。探针支持跳过向导，但该日志未单独确认进入向导分支；原 HyperOS 设备与万象拼音配置未验证 |
| [#2003](https://github.com/osfans/trime/issues/2003) | 移动端虚拟键盘存在响应点击方面的问题 | 异常报告：待复现 | 未验证 |
| [#1981](https://github.com/osfans/trime/issues/1981) | 部署无法结束 | 异常报告：待复现 | 未验证 |
| [#1942](https://github.com/osfans/trime/issues/1942) | 中文輸入時字母自動上屏 | 异常报告：待复现 | 未验证 |
| [#1938](https://github.com/osfans/trime/issues/1938) | 文本操作和剪贴板 | 默认主题剪贴板标题菜单增加文字编辑面板，保留已有主题手势；修复异步 Ctrl+C 提前折叠选区 | Android 15 模拟器真实编辑器验证全选、复制、剪切、粘贴、方向键及旧菜单失效，`hookCtrlCV` 开关两种状态通过；返回后 26 键与九键布局、按键动作/几何和中英文状态一致，详见 `D:\xrime-tools\instrument-api35-editor-panel-standalone-v4.log`（passed=true，CODE=-1）。其他应用和设备尚未全面验证 |
| [#1934](https://github.com/osfans/trime/issues/1934) | 请求支持与「说点啥」语音输入键盘的联动 | 功能或设计：待评估 | 未验证 |
| [#1915](https://github.com/osfans/trime/issues/1915) | Cannot tap tool bar items after setting `style/keyboard_height` | 同文风自定义高度后的工具栏触摸 | Android 16 专用模拟器原生部署原始 280dp 补丁，等待系统输入法窗口绘制后，两个工具栏开关的中心、上沿、下沿共六次真实触摸均切换对应选项（`D:\xrime-tools\issue-1915\instrument-api36-v31.log`）；其他按钮、分辨率和原设备尚未验证。先前未等待输入法上屏的失败及截图保留供核对 |
| [#1905](https://github.com/osfans/trime/issues/1905) | wiki 里添加对一些参数和功能的说明 | 本地文档已补充；上游 wiki 未修改 | [横屏键盘](../doc/Keyboard.md)及[剪贴板规则](../doc/ClipboardRules.md)；规则解析单元测试通过，设备复制与用户设置交互待验证；比较后的不同原文目前仍不会去重 |
| [#1897](https://github.com/osfans/trime/issues/1897) | 收藏夹增加分类 | 功能或设计：待评估 | 未验证 |
| [#1883](https://github.com/osfans/trime/issues/1883) | 加高 style/keyboard_padding_bottom 造成主鍵區底部空隙 | 异常报告：待复现 | 未验证 |
| [#1879](https://github.com/osfans/trime/issues/1879) | 在候选项显示后的短时间内，点击工具栏上的候选项，实际触发的仍然是对应位置的快捷按钮 | 候选出现后拒绝迟到的旧工具栏及剪贴板建议操作 | Android 15 真实触摸验证正常工具栏/隐藏点击与下滑、DOWN 后候选绘制再 UP 的旧动作、建议迟到点击/长按/关闭及重叠位置真实选词；v39 曾复现隐藏键误收起（D:\xrime-tools\instrument-api35-issue1879-v1.log），v42 回归通过（D:\xrime-tools\instrument-api35-issue1879-v3.log，passed=true、CODE=-1）。其他版本及厂商设备尚未验收 |
| [#1869](https://github.com/osfans/trime/issues/1869) | 「導航欄背景」不會立刻「跟隨鍵盤背景色」 | 导航栏配色刷新 | Android 14 专用编辑器的 COLOR_ONLY 模式下实测同一窗口配色与图标颜色跟随；原报告中的外部应用及 FULL 模式尚待验证 |
| [#1865](https://github.com/osfans/trime/issues/1865) | 数字键盘点击“EN”切换后的中英文键盘和默认中英文键盘不一样 | 异常报告：待复现 | 未验证 |
| [#1860](https://github.com/osfans/trime/issues/1860) | 用户文件夹下“标准”主题出现bug | 异常报告：待复现 | 未验证 |
| [#1853](https://github.com/osfans/trime/issues/1853) | 將【貼上後返回】的開關放在剪貼板上 | 剪贴板标题开关 | 已接入现有持久设置；Android 15 的开关、240dp 窄屏布局、外部设置同步和两种粘贴行为通过，完整编辑器回归通过（D:\xrime-tools\instrument-api35-image-ui-v3.log）；其他设备待验证 |
| [#1812](https://github.com/osfans/trime/issues/1812) | Feature Request: Event-based Automatic Keyboard Switching for Multi-layer Input Methods | 功能或设计：待评估 | 未验证 |
| [#1809](https://github.com/osfans/trime/issues/1809) | ascii_keyboard似乎無效 | 异常报告：待复现 | 未验证 |
| [#1791](https://github.com/osfans/trime/issues/1791) | Potential UI thread blocking issue | 异常报告：待复现 | 未验证 |
| [#1778](https://github.com/osfans/trime/issues/1778) | 能否根据现有键盘自定义某一个按键 | 求助或讨论：待研判 | 未验证 |
| [#1777](https://github.com/osfans/trime/issues/1777) | 请默认开启「点击候选词」功能 | 功能或设计：待评估 | 未验证 |
| [#1762](https://github.com/osfans/trime/issues/1762) | toolbar上的按钮可否支持长按等不同动作 | 功能或设计：待评估 | 未验证 |
| [#1761](https://github.com/osfans/trime/issues/1761) | alternative models for STT voice input | 功能或设计：待评估 | 未验证 |
| [#1733](https://github.com/osfans/trime/issues/1733) | Add option to choose the behavior of keys exactly at the center for the split keyboard | 功能或设计：待评估 | 未验证 |
| [#1732](https://github.com/osfans/trime/issues/1732) | Navigation buttons sometimes overlay the soft keyboard | 异常报告：待复现 | 未验证 |
| [#1705](https://github.com/osfans/trime/issues/1705) | 什么时候支持滑行输入？ | 功能或设计：待评估 | 未验证 |
| [#1704](https://github.com/osfans/trime/issues/1704) | Remember ASCII mode by app | 功能或设计：待评估 | 未验证 |
| [#1685](https://github.com/osfans/trime/issues/1685) | 在3.3.5中，设置了空按键的地方不显示出来 | 空按键显示 | 空动作可见按键与省略动作的间隔区分；Android 15 设备检查了空按键交互、长按、滑动和无障碍，原 HarmonyOS 设备尚未验证 |
| [#1677](https://github.com/osfans/trime/issues/1677) | 输入法在特定网页输入框中键盘无法弹出 | 异常报告：待复现 | 未验证 |
| [#1673](https://github.com/osfans/trime/issues/1673) | Introduce WebView Keyboard / WebView 键盘 | 功能或设计：待评估 | 未验证 |
| [#1661](https://github.com/osfans/trime/issues/1661) | Add shortcut for inserting current timestamp in ISO 8601 format (e.g. 2025-06-02T11:10:56+12:00) | 标准主题 B 键下滑候选实现 | 本地日期及 UTC、非整点偏移、夏令时单元测试通过；实际编辑框提交及 Android 5/6 设备回归待验证 |
| [#1657](https://github.com/osfans/trime/issues/1657) | 主题文件中的longclick无法正常使用花括号{}语法 | 异常报告：待复现 | 现有代码和测试有相关覆盖；原配置设备回归未运行 |
| [#1653](https://github.com/osfans/trime/issues/1653) | 建议增加对swipe_over/in/out的支持 | 功能或设计：待评估 | 未验证 |
| [#1645](https://github.com/osfans/trime/issues/1645) | 液态键盘横向移动后，无法点击 | 液体键盘页签及开关栏的滚动点击 | Android 15 在临时主题中对液体键盘页签的普通滚动及左右边缘越界后点击通过（v70）；同次测试未验证原报告的开关栏，也未在华为 HarmonyOS 设备复现，不能认定该 issue 全部解决。日志 `D:\xrime-tools\instrument-api35-issue1556-v70.log`。 |
| [#1637](https://github.com/osfans/trime/issues/1637) | 建议支持 SenseVoiceSmall 语音识别模型 | 功能或设计：待评估 | 未验证 |
| [#1635](https://github.com/osfans/trime/issues/1635) | 在 v3.3.3 版本中，keys 里的 ascci 标签失效 | 英文状态按键动作 | Android 14 原配置及显式关闭绑定的按键经实际主题部署、触摸后，英文编辑框均正确收到 `g`（D:\xrime-tools\instrument-api34-ascii1635-v9.log）；其他设备待验证 |
| [#1632](https://github.com/osfans/trime/issues/1632) | "preset_keyboards/default/label_transform" 设置中文模式下自動大寫不好使 | 异常报告：待复现 | 未验证 |
| [#1611](https://github.com/osfans/trime/issues/1611) | 关于按键定义的问题 | 求助或讨论：待研判 | 未验证 |
| [#1610](https://github.com/osfans/trime/issues/1610) | Remove some non-determinism | 构建时间来源 | 已支持显式构建时间及 SOURCE_DATE_EPOCH，解析和优先级单元测试通过；完整 APK 的可复现性尚待比较 |
| [#1604](https://github.com/osfans/trime/issues/1604) | 中文狀態下鍵盤的映射與輸出不符 | 异常报告：待复现 | 未验证 |
| [#1601](https://github.com/osfans/trime/issues/1601) | 建议中文是键盘字母显示为大写 | 功能或设计：待评估 | 未验证 |
| [#1593](https://github.com/osfans/trime/issues/1593) | shift长按（）光标问题 | 异常报告：待复现 | 未验证 |
| [#1556](https://github.com/osfans/trime/issues/1556) | 候选列表滚动到最底部时候选项点选无反应 | 列表越界后的触摸响应 | Android 15 真实触摸：修复前候选顶部及液体页签、正文越界后下一次点击被滚动状态接管；关闭三处列表的越界拉伸后，候选上下边缘、液体页签左右及正文底部的快速滑动和停留释放、普通滚动对照均通过（v70）。候选底部以末项完整可见及 Rime 查询无后续候选确认末尾，未将滚动范围估算当作末尾判断；原华为 HarmonyOS 设备和其他系统仍待验证。日志 `D:\xrime-tools\instrument-api35-issue1556-v70.log`，保留此前失败及坐标诊断记录。 |
| [#1529](https://github.com/osfans/trime/issues/1529) | 在程序中显示预编辑码时不在候选窗口上重复显示 | 功能或设计：待评估 | 未验证 |
| [#1515](https://github.com/osfans/trime/issues/1515) | `"style/keyboard_height_land"` has no effect to set landscape keyboard height | 横屏键盘高度 | Android 14 原生部署 `style/keyboard_height_land: 100`，实际旋转且关闭横屏布局时，输入法模型和 View 高度均为 100dp（D:\xrime-tools\instrument-api34-customgeometry-v9.log）；原 Android 15 设备待验证 |
| [#1477](https://github.com/osfans/trime/issues/1477) | 不能切換自定義letter keyboard布局 | 异常报告：待复现 | 未验证 |
| [#1476](https://github.com/osfans/trime/issues/1476) | Trime can't insert uppercase letters when mode "中文" is enabled | 中文模式与自定义方案的大写输入 | 原作者在后续评论中使用自定义 foo 方案报告 3.3.3、3.3.4 和当时 nightly 可输入大写，3.3.0/3.3.2 不可；当前 fork 的 HEAD 包含 3.3.3 提交，但尚未按该原始方案在 xrime 设备复现。该评论另报告导航栏遮挡和启动主题崩溃，需分别验证，不能由历史版本结果推断当前问题全部解决。 |
| [#1474](https://github.com/osfans/trime/issues/1474) | 使用配置文件管理 GUI 中的设置选项 | 功能或设计：待评估 | 未验证 |
| [#1469](https://github.com/osfans/trime/issues/1469) | 按键定义无效 | 异常报告：待复现 | 未验证 |
| [#1464](https://github.com/osfans/trime/issues/1464) | 希望原生支持中文九键输入 | 内置朙月拼音九键 | 已有内置方案和设置入口；Android 15 真实输入、两主题布局及 Android 17 中英文/数字往返通过。评论提及的微软双拼九键和四叶草兼容仍未验证 |
| [#1443](https://github.com/osfans/trime/issues/1443) | Version 3.2.16 后面出现 style.key_width 不生效问题 | 异常报告：待复现 | 有候选修复；单元测试通过，设备回归未运行 |
| [#1438](https://github.com/osfans/trime/issues/1438) | 按键行间距 vertical_gap 不生效 | 主题行距与键盘覆盖 | Android 14 原生部署全局 8dp 和九键局部 4dp，实际 KeyView 几何与绘制像素通过（D:\xrime-tools\instrument-api34-customgeometry-v9.log）；Android 5 绘制探针也通过 |
| [#1414](https://github.com/osfans/trime/issues/1414) | 大宽度下分割键盘比例较大时部分键盘无法正常显示 | 异常报告：待复现 | 未验证 |
| [#1389](https://github.com/osfans/trime/issues/1389) | 让英文输入法变成默认 | 功能或设计：待评估 | 未验证 |
| [#1379](https://github.com/osfans/trime/issues/1379) | fixed_key_bar的按键如何设定宽度 | 求助或讨论：待研判 | 未验证 |
| [#1374](https://github.com/osfans/trime/issues/1374) | 关于颜色支持material design的设想。 | 功能或设计：待评估 | 未验证 |
| [#1321](https://github.com/osfans/trime/issues/1321) | 为[中/西]文模式设置不同配色 | 功能或设计：待评估 | 未验证 |
| [#1316](https://github.com/osfans/trime/issues/1316) | 不写 `keyboard_height` 参数时液态键盘不显示 | 异常报告：待复现 | 有自然高度候选修复；单元测试通过，设备回归待验证 |
| [#1299](https://github.com/osfans/trime/issues/1299) | 允许用户调整已输入的字符 | 功能或设计：待评估 | 未验证 |
| [#1269](https://github.com/osfans/trime/issues/1269) | [docs] Any document about liquid_keyboard | 求助或讨论：待研判 | 未验证 |
| [#1267](https://github.com/osfans/trime/issues/1267) | 添加针对多重剪贴板的新命令 | 选择后重复粘贴与可选快捷键 | 已实现默认关闭的可选重复粘贴及 Meta+V 入口；Android 15 真编辑器覆盖历史顺序、Ctrl+V、条目更改、隐私及输入框切换，受控将 Meta+V 交给服务时历史页打开。但该模拟器的真实输入路径中 Meta+V 未到达输入法或编辑器，整体设备探针仍失败（`D:\xrime-tools\instrument-api35-repeat-v17.log`）；普通 V 和 Alt+V 的内核输入正对照可以到达输入法。中、繁、英文设置说明已标明依赖系统投递。不能确定所有系统的拦截行为，更多设备及实际 Meta+V 派发仍待验证 |
| [#1251](https://github.com/osfans/trime/issues/1251) | 在设置界面增加键盘/候选字体微调选项 | 功能或设计：待评估 | 未验证 |
| [#1200](https://github.com/osfans/trime/issues/1200) | 自定义按键形状和按键触摸区域，如 typewise 式的蜂窝按键 | 功能或设计：待评估 | 未验证 |
| [#1148](https://github.com/osfans/trime/issues/1148) | 改善 UI 跟其他輸入法看齊 | 功能或设计：待评估 | 未验证 |
| [#1106](https://github.com/osfans/trime/issues/1106) | 滑動手指以輸入文字 | 功能或设计：待评估 | 未验证 |
| [#1082](https://github.com/osfans/trime/issues/1082) | 一键两功能 | 功能或设计：待评估 | 未验证 |
| [#1040](https://github.com/osfans/trime/issues/1040) | liquid keyboard window 支持「钉住」按钮 | 功能或设计：待评估 | 未验证 |
| [#1033](https://github.com/osfans/trime/issues/1033) | 支持打字有编码时，上滑键变化 | 功能或设计：待评估 | 未验证 |
| [#1020](https://github.com/osfans/trime/issues/1020) | 关于.custom.yaml配置补丁中对按键功能的配置格式问题 | 异常报告：待复现 | 未验证 |
| [#1008](https://github.com/osfans/trime/issues/1008) | 支持 Gboard 那样的悬浮键盘 | 功能或设计：待评估 | 未验证 |
| [#1005](https://github.com/osfans/trime/issues/1005) | 无法使用luna九宫格输入法 | 第三方九宫格配置 | 在独立 Android 13 上使用报告前的两个仓库版本，通过设置启用第三方方案后，两套原主题均完成 64→“你”和中英文往返；固定配置未复现只输出英文。原报告具体修改和设备未知，不能推断全部场景已解决；证据位于 D:\xrime-tools\issue-1005-2023\device-results-v15.md |
| [#997](https://github.com/osfans/trime/issues/997) | 關於 Google Play 版本更新 | 求助或讨论：待研判 | 未验证 |
| [#954](https://github.com/osfans/trime/issues/954) | 您好，新升级了3.2.11后配置文件tongwenfeng.trime.ymal的兼容性问题 | 多个兼容问题：待分别核实 | 报告者在评论中称最初的候选缺失通过更新解决，但随后还报告了简繁转换和键盘高度问题；原 MIUI 14 设备及其自定义配置未复现，不能据此判定全部解决 |
| [#953](https://github.com/osfans/trime/issues/953) | [Dev] Use prebuilt dependencies | 功能或设计：待评估 | 未验证 |
| [#938](https://github.com/osfans/trime/issues/938) | [Feature Request] Add Support for Incognito Mode | 手动与编辑器触发的隐私模式 | 既有隐私模式在 Android 15/17 模拟器覆盖手动开关、密码框和不学习标志，并检查词库、剪贴板等记录边界；不同应用的无痕信号仍需单独验证 |
| [#924](https://github.com/osfans/trime/issues/924) | 液态键盘有些地方是否应该重新设计？ | 功能或设计：待评估 | 未验证 |
| [#882](https://github.com/osfans/trime/issues/882) | 无法直接设置大写字母作为按键？ | 自定义大写按键显示 | Android 14 模拟器以原报告的 A 至 E 创建实际 Key 模型，使用 Android 虚拟键盘字符映射检查标签均通过（`D:\xrime-tools\instrument-api34-t05-uppercase-v18.log`）；原始自定义主题的完整显示和点击、其他设备及评论中的签名升级问题未验证 |
| [#853](https://github.com/osfans/trime/issues/853) | 绑定实体键盘快捷键 | 求助或讨论：待研判 | 未验证 |
| [#850](https://github.com/osfans/trime/issues/850) | unicode模式:接受任何字符作为编码手段 | 功能或设计：待评估 | 未验证 |
| [#846](https://github.com/osfans/trime/issues/846) | 草稿箱建议设置白名单模式 | 功能或设计：待评估 | 未验证 |
| [#827](https://github.com/osfans/trime/issues/827) | 剥离方案、键盘配列和键盘label的对应关系 | 功能或设计：待评估 | 未验证 |
| [#814](https://github.com/osfans/trime/issues/814) | 增强开箱即用性：重定义主键盘⌘键以快速切换输入法 | 功能或设计：待评估 | 未验证 |
| [#779](https://github.com/osfans/trime/issues/779) | Add crash report UI | 功能或设计：待评估 | 未验证 |
| [#763](https://github.com/osfans/trime/issues/763) | trime.yaml中的英文鍵盤Bug | 异常报告：待复现 | 未验证 |
| [#753](https://github.com/osfans/trime/issues/753) | Make settings modification more user-friendly | 功能或设计：待评估 | 未验证 |
| [#743](https://github.com/osfans/trime/issues/743) | 使用输入法辅助发图的功能 | 功能或设计：待评估 | 未验证 |
| [#738](https://github.com/osfans/trime/issues/738) | 同文内置多语言键盘label | 功能或设计：待评估 | 未验证 |
| [#734](https://github.com/osfans/trime/issues/734) | 为按键增加动画效果 | 功能或设计：待评估 | 未验证 |
| [#708](https://github.com/osfans/trime/issues/708) | 更改同文風主題到36鍵 | 功能或设计：待评估 | 未验证 |
| [#674](https://github.com/osfans/trime/issues/674) | 设计：区分键盘按键的状态 | 功能或设计：待评估 | 未验证 |
| [#667](https://github.com/osfans/trime/issues/667) | 在配置文件中针对用户指定App进行优化 | 功能或设计：待评估 | 未验证 |
| [#578](https://github.com/osfans/trime/issues/578) | Custom the key select mapping for Shift or Alt when English mode. | 功能或设计：待评估 | 未验证 |
| [#464](https://github.com/osfans/trime/issues/464) | 增加悬浮窗:输入和剪贴板历史/文本编辑/可滚动易编辑的符号键盘 | 功能或设计：待评估 | 未验证 |
