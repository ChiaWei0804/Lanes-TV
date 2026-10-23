# btr-hardening 修改說明

基底：nitansde/MyTVB-Speed-Boost `18f99cb`（v2.0.9-btr.11）
第一版：`2.0.9-btr.11-hardened.1`（之後的版本見文末「後續修改」），套件名稱 `com.mytvb.btr`（可與上游 MyTVB 並存）

完整 diff：`0001-btr-hardening-CCTV-JS-BTR.patch`，或在本資料夾執行 `git show HEAD`。
程式碼中所有修改處都標了 `[btr-hardening]`，可直接搜尋。

| 風險 | 處理方式 | 檔案 |
|---|---|---|
| #1 直播 JS 橋可讀取登入 cookie | 電視直播 / CCTV 入口永久隱藏；兩個 Activity 從 Manifest 移除（無法被啟動）；讀檔加路徑穿越防護 | MainActivity.kt、SettingsFragment.kt、AndroidManifest.xml、MarmotLiveData.kt |
| #2 雲端腳本明文下載 + Zip Slip | 雲端更新停用；解壓縮加路徑檢查 | MarmotCloudUpdate.kt |
| #3 WebView 遠端除錯 | 只在 debug 版開啟 | WebEngine.kt |
| — 直播頁忽略 SSL 錯誤 | 改為拒絕 | MarmotWebViewClient.kt、MarmotX5WebViewClient.kt |
| #4 更新器指向上游 | 移除「檢查更新」設定項 | SettingsFragment.kt |
| #5 套件名稱衝突 | `com.mytvb` → `com.mytvb.btr` | app/build.gradle.kts |
| #6 BTR 執行緒上限 128 | 手動上限改為 32（自動模式本來就封頂 32，且遇到伺服器拒絕會自動降速）；BTR 仍預設開啟 | BtrSettingsStore.kt、SettingsFragment.kt、MyPlayerSettingView.kt |
| #7 備份會帶走登入 cookie | 關閉 allowBackup；修正排除規則為實際的 cookie 檔與 datastore | AndroidManifest.xml、backup_rules.xml、data_extraction_rules.xml |

沒有更動的部分：
- B 站播放、登入、彈幕、BTR 加速邏輯本身。
- 直播模組的程式碼仍在 APK 裡（只是無法進入），X5 SDK 依賴也還在。完全刪除要改動較多檔案，等這版確認能編譯、能用之後再做。
- 全域允許明文 HTTP（B 站部分圖片仍走 http，貿然關閉可能造成破圖）。

## 編譯

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

輸出：`output\MyTVB-BTR-hardened.apk`
金鑰：`keystore\mytvb-release.p12`（請備份，連同密碼）

## 後續修改

| 版本 | 內容 |
|---|---|
| hardened.2 | BTR 新增「自動」CDN 模式並設為預設（海外 + 大陸節點一起測速）；動態頁 UP 頭像載入保底；開啟「日誌記錄」時 release 版也輸出到 logcat |
| hardened.3 | 修正退出影片後設定頁放大：頁面建立前檢查並修正 density，記錄偏移來源 |
| hardened.4 | density 檢查擴大到每個 View 建立前（列表卡片也涵蓋） |
| hardened.5 | 修正 BTR 節點排序的 Long/Int 型別錯誤（ClassCastException → 片段下載失敗、緩衝歸零、Source error、自動降畫質）；移除已不存在的 `upos-sz-mirror08c` 節點；程式例外也顯示在 Debug；點播緩衝目標改為 120 秒、低於 60 秒才恢復下載，位元組上限為 heap 的 45%（防 4K OOM） |
| hardened.6 | BTR Debug 的緩衝目標改為顯示實際設定（120 秒，低於 60 秒補充），不再寫死 45 秒 |

給 Claude Code 的專案說明（編譯、adb、抓 log 的方式）在 `CLAUDE.md`。
