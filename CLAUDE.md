# Lanes-TV（給 Claude Code 的專案說明）

這是 **ChiaWei0804/Lanes-TV**：Android TV 上的第三方 Bilibili 客戶端，加上 BTR 多 CDN 加速。
從 [nitansde/MyTVB-Speed-Boost](https://github.com/nitansde/MyTVB-Speed-Boost) fork 出來，並做了安全強化。
使用者用繁體中文溝通。

- 套件名稱：`com.mytvb.btr`（與上游 `com.mytvb` 不同，可並存）
- git remote：`origin` = ChiaWei0804/Lanes-TV，`upstream` = nitansde/MyTVB-Speed-Boost
- 上游自己的規則在 `AGENTS.md`，下面「與 AGENTS.md 的關係」說明哪些照用、哪些不適用
- 強化內容的完整清單在 `HARDENING.md`；程式碼裡所有改動都標了 `[btr-hardening]`

## 與 AGENTS.md 的關係

照用：
- **commit / push 只在使用者要求時才做**
- 定位問題不要瞎猜，先加日誌、抓 log 再改
- 版本升級流程（versionCode / versionName / CHANGELOG）

不適用：
- 「終端統一用 Git Bash」：`build.ps1` 是 PowerShell 腳本，要用 PowerShell 執行。adb 和 gradlew 在兩種終端都能用。

## 環境（Windows）

| 項目 | 位置 / 版本 |
|---|---|
| JDK | `C:\Program Files\Android\Android Studio\jbr`（JDK 17）。Gradle 8.14.3 只支援 JDK 17–24，不要用 25 以上 |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`（platform 35、build-tools 35.0.0） |
| adb | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`（不在 PATH 裡，要用完整路徑） |

PowerShell 裡建議先設：
```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
```

## 編譯

- **正式版（要裝到電視的）**：`powershell -ExecutionPolicy Bypass -File .\build.ps1`
  - 腳本會互動式詢問簽章密碼，**請讓使用者自己輸入**，不要要求、記錄或寫進任何檔案
  - 輸出：`output\MyTVB-BTR-hardened.apk`
  - 如果使用者已在環境變數設好 `RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD`，也可以直接 `.\gradlew.bat :app:assembleRelease --no-daemon`
- **單元測試**：`.\gradlew.bat :app:testDebugUnitTest --no-daemon`（每次改 BTR 或播放器後先跑）
- 第一次編譯約 6 分鐘，之後約 1–2 分鐘
- 每次要裝到電視的版本都要遞增 `app/build.gradle.kts` 的 `versionCode`，`versionName` 用 `2.0.9-btr.11-hardened.N`

### 簽章金鑰（絕對不能弄錯）

- `keystore\mytvb-release.p12`，已在 `.gitignore`，**不要 commit、不要移動、不要刪除**
- **不要把 debug 版裝到電視上**：debug 版簽章不同，覆蓋會失敗；如果因此解除安裝 `com.mytvb.btr`，使用者的登入和設定會全部消失
- 任何會解除安裝 App 或清除資料的動作（`adb uninstall`、`pm clear`）都要先問使用者

## 電視（adb）

- 電視 IP：`192.168.7.52`（Wi-Fi）。無線偵錯的**連接埠每次重開都會變**，要問使用者電視「無線偵錯」畫面上的 IP:埠
- 斷線後重連：`& $adb connect 192.168.7.52:<埠>`；如果顯示需要配對，請使用者在電視上開「使用配對碼配對」，再執行 `& $adb pair 192.168.7.52:<配對埠> <配對碼>`
- **一律加 `-s 192.168.7.52:<埠>`**：這台電腦上還有 `emulator-55xx` 之類的其他模擬器，不加會出現 `more than one device`
- 安裝：`& $adb -s <裝置> install -r output\MyTVB-BTR-hardened.apk`
- 啟動 App：`& $adb -s <裝置> shell monkey -p com.mytvb.btr -c android.intent.category.LEANBACK_LAUNCHER 1`
- 模擬遙控器：`& $adb -s <裝置> shell input keyevent <鍵>`，常用 `DPAD_UP / DPAD_DOWN / DPAD_LEFT / DPAD_RIGHT / DPAD_CENTER / BACK / HOME / MENU`

### 截圖

PowerShell 的 `>` 會把二進位檔當文字轉碼，截圖會壞掉。要先存在電視上再 pull：
```powershell
& $adb -s <裝置> shell screencap -p /sdcard/shot.png
& $adb -s <裝置> pull /sdcard/shot.png .\shot.png
```

### 抓 log

1. App 內要先開「設定 → 關於 → 診斷 → 日誌記錄」。release 版只有開了這個才會輸出到 logcat（`AppLog` 用 `Log.println`，不會被 R8 移除）
2. `& $adb -s <裝置> logcat -c` 清空 → 在電視上重現問題 → `& $adb -s <裝置> logcat -d -s <TAG>:* > .\xxx.log`
3. 常用 TAG：`UiScale`、`ImageLoader`、`DynamicFrag`、`PlaybackPerf`、`VideoPlayerViewModel`、`SeamlessQuality`、`AndroidRuntime`（閃退）
4. PowerShell 存出來的 log 是 UTF-16 編碼
5. BTR 本身不寫 logcat；要看節點與速度，在 App 設定 → CDN 加速打開 Debug，看播放畫面右上角的資訊
6. 查完提醒使用者把「日誌記錄」關掉

## 這個 fork 改過什麼（重點）

- **電視直播 / CCTV（Marmot 模組）已停用**：入口隱藏、Activity 從 Manifest 移除、雲端腳本更新停用。原因是 JS 橋能讀到登入 cookie，雲端更新又走明文 HTTP、沒有簽章。**不要重新啟用**，除非先正式修好 JS 橋和雲端更新
- B 站本身的直播（設定「直播入口」，預設關）和番劇（「分類」頁）都保留
- 移除設定頁的「檢查更新」（原本指向上游 repo）和「X5 內核」
- BTR：新增「自動」CDN 模式，並設為預設（海外 + 大陸節點一起測速，同 ChiaWei0804/Lanes 的 Auto）；手動執行緒上限 32
- 關閉 App 備份，備份排除規則改為實際的 cookie 檔
- WebView 遠端除錯只在 debug 版開啟

## 已知問題與歷史

- **退出影片後介面放大**（已修）：播放器關閉約 3 秒後，系統會把 density 從 1.0 重設成電視原生的 2.0，而且**不會**觸發 `onConfigurationChanged`。修法是 `UiScale.ensure()`，在 `UiTextScaleFactory` 建立每個 View 前，以及 `BaseFragment.onCreateView` 時檢查並修正。log 裡的 `density drift at ...` 就是在記錄這件事
- **動態頁 UP 頭像**（已修）：原本要等右側影片列表首幀才載入頭像，現在加了 1.5 秒保底
- **待查**：曾觀察到一次影片要約 50 秒才開始播放（`PlaybackPerf` 顯示同一位置 IDLE/BUFFERING 重試 3 次），當時還沒有「自動」模式。若再出現，先開 BTR Debug 看節點，再抓 `PlaybackPerf` 和 `VideoPlayerViewModel` 的 log

## commit 慣例

- GitHub 擋私人 email，commit 作者用 `ChiaWei0804 <ChiaWei0804@users.noreply.github.com>`
- 推到 `origin main`；**不要推到 `upstream`**
