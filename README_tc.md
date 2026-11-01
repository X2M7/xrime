<!--
SPDX-FileCopyrightText: 2015 - 2024 Rime community
SPDX-License-Identifier: GPL-3.0-or-later
-->

# xrime

xrime 是基於 [Trime](https://github.com/osfans/trime)、[X2M7/trime](https://github.com/X2M7/trime) 與 [Rime](https://rime.im/) 輸入法引擎的獨立 Android 輸入法。應用程式 ID 為 `com.x2m7.xrime`，可與原 Trime 同時安裝；舊應用程式的私有資料不會自動遷移。

[English](README.md) · [简体中文](README_sc.md) · [專案首頁](https://x2m7.github.io/xrime/) · [下載](https://github.com/X2M7/xrime/releases)

## 下載與狀態

目前版本為 **[v0.1.0-alpha.1](https://github.com/X2M7/xrime/releases/tag/v0.1.0-alpha.1)**，是支援 Android 5.0 及以上版本的 Alpha 預發布版。相容的手機請選擇 ARM64 APK；x86_64 APK 適用於對應裝置或模擬器。測試前請將詞典與設定備份到獨立位置。

部分上游問題仍待處理；完整 lint 仍有建置外掛升級提示；音效包目錄掃描在特定檔名與 Android 執行環境下存在當機風險。ARM64 安裝包尚未在實體裝置上驗證。已測範圍與未解決問題詳見[驗證紀錄](docs/xrime-verification.md)及[上游問題清單](docs/xrime-upstream-issues.md)。

## 取得原始碼與建置

遞迴複製以取得原生依賴：

```sh
git -c core.autocrlf=false -c core.eol=lf clone --recurse-submodules https://github.com/X2M7/xrime.git xrime
```

固定原始碼及本工作區 Windows 建置環境見[原始碼取得與建置說明](docs/source-checkout.md)。應用程式 ID、簽章與歷史工具限制見[身分說明](docs/xrime-identity.md)，資料處理方式見[隱私說明](PRIVACY.md)。

## 參與貢獻與授權

歡迎透過 [xrime Issues](https://github.com/X2M7/xrime/issues) 回報，並參閱[貢獻指南](CONTRIBUTING.md)。專案採用 [GPL-3.0-or-later](LICENSE) 授權。感謝 Rime、Trime 社群及 [AUTHORS](AUTHORS) 中列出的貢獻者；第三方聲明隨原始碼保留。
