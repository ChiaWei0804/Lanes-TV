# 發佈 Lanes-TV 新版本

電視和投影機上的「設定 → 關於 → 檢查更新」只看本 repo 的 GitHub Releases。照下面的步驟發佈，裝置就能自己更新。

## 每次發佈

1. 改 `app/build.gradle.kts`：
   - `versionCode` 加 1（一定要比上一版大，App 用它判斷新舊）
   - `versionName` 改成新的 `X.Y.Z`（修正加 Z、新功能加 Y）
2. 在 `CHANGELOG.md` 最上面寫這一版的內容。
3. 編譯正式版（會問簽章密碼）：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\build.ps1
   ```
4. commit 並 push 到 `origin main`（發佈腳本會檢查 HEAD 已經 push）。
5. 發佈：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\tools\publish-release.ps1 -Notes "這一版改了什麼"
   ```
   腳本會：
   - 確認 `output\Lanes-TV.apk` 是正式金鑰簽章、只有一個簽章者、不是 debug 版、versionName 是 `X.Y.Z`
   - 產生 `output\release-vX.Y.Z\Lanes-TV-vX.Y.Z.apk` 和 `update.json`
   - 用 git 已存的 GitHub 登入建立**草稿** release、上傳兩個檔案、確認大小後才正式發佈（權杖不寫入任何檔案）
   
   只想先產生檔案、不上傳：加 `-DryRun`。

發佈後在任一台裝置按「檢查更新」確認看得到新版。

## 規則（App 端會檢查，不符合就拒絕更新）

- tag 必須是 `vX.Y.Z`，不能是草稿或預先發佈
- 附件必須有 `Lanes-TV-vX.Y.Z.apk` 和 `update.json`，兩者由腳本一起產生，不要手動改
- 安裝檔必須用 `keystore\mytvb-release.p12` 簽章（憑證 SHA-256 `60cf14f4…6418ffc`，寫在 `UpdateConfig.kt`）
- versionCode 必須比裝置上的大

發錯了：到 GitHub 把那個 release 改回草稿或刪除，再發一個 versionCode 更大的修正版。不要重用已發佈過的 versionCode。

## 新裝置第一次安裝

App 內更新只能從 1.0.0（versionCode 104）以後的版本開始用。新裝置先用 adb 裝一次：

```powershell
& $adb -s <裝置> install -r output\Lanes-TV.apk
```

Android 8 以上第一次在 App 內更新時，系統會要求允許 Lanes-TV「安裝不明應用程式」；當貝等沒有這個設定頁的系統，會由它自己的安裝程式詢問。

## 金鑰

- `keystore\mytvb-release.p12` 和密碼一定要備份。遺失後所有裝置都無法更新，只能解除安裝重裝（登入和設定會消失）。
- 不要把金鑰放到 GitHub（Secrets 也不要）；發佈一律在這台電腦用腳本做。本 repo 沒有 GitHub Actions workflow（上游的兩個已移除）。
