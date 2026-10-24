# Lanes-TV

> MyTVB + BTR 加速的個人修改版

我想在電視上看 Bilibili 時，也能用到 BTR 的多 CDN 加速。[MyTVB-Speed-Boost](https://github.com/nitansde/MyTVB-Speed-Boost) 已經在 MyTVB 的基礎上做到了這件事，所以 Lanes-TV 直接從它修改而來：我把用不到的功能拿掉（例如電視直播／CCTV），再針對自己的電視和投影機做了安全強化、一些修正，並加上應用內更新。

這是我**自己使用**的修改版，不是原作者的版本。程式主體來自下方列出的開源專案，功勞屬於原作者。

## 來源

Lanes-TV 建立在這些專案之上，依關係由遠到近：

| 專案 | 作者 | 關係 |
|---|---|---|
| [BBLL](https://github.com/xiaye13579/BBLL) | xiaye13579 | MyTVB 的絕大部分頁面和操作邏輯參考自 BBLL |
| [MyTVB](https://github.com/qianxuntudou-ops/MyTVB) | qianxuntudou-ops | Android TV 的第三方 Bilibili 用戶端，也就是程式主體。原專案沒有附上獨立的 LICENSE 檔案 |
| [MyTVB-Speed-Boost](https://github.com/nitansde/MyTVB-Speed-Boost) | nitansde | 在 MyTVB 上加入 BTR 多 CDN 加速。Lanes-TV 從它的 `v2.0.9-btr.11`（`18f99cb`）fork 出來 |
| [Bilibili-thread-ripper](https://github.com/MrTangLuyao/Bilibili-thread-ripper)、[Bilibili-thread-ripper-desktop](https://github.com/MrTangLuyao/Bilibili-thread-ripper-desktop) | MrTangLuyao | BTR 加速的參考來源，MIT 授權；授權全文在 [BTR-MIT.txt](app/src/main/assets/licenses/BTR-MIT.txt) 和 [BTR-Desktop-MIT.txt](app/src/main/assets/licenses/BTR-Desktop-MIT.txt)，也隨 APK 一起發佈 |

MyTVB 的感謝名單和免責聲明請見[原專案的 README](https://github.com/qianxuntudou-ops/MyTVB)，同樣適用於 Lanes-TV。如果任何原作者不希望這個修改版公開，請開 issue 告訴我，我會下架。

## 和上游的差異

- **改名**：App 名稱改為 Lanes-TV，圖示和電視橫幅沿用 MyTVB 原本的圖。套件名稱維持 `com.mytvb.btr`，已安裝的裝置可以直接覆蓋更新，登入和設定都會保留。
- **應用內更新**：設定 → 關於 →「檢查更新」只查本 repo 的 [Releases](https://github.com/ChiaWei0804/Lanes-TV/releases)。下載後會檢查大小、SHA-256，並由系統驗證安裝檔簽章，必須和目前安裝的 App 是同一把金鑰，否則拒絕安裝。只在按下按鈕時才連網。
- **移除與安全強化**：停用電視直播／CCTV 模組（它的 JS 橋能讀到登入 cookie）、只有它會用到的「X5 內核」設定，以及明文下載的雲端腳本更新；直播頁遇到 SSL 錯誤改為拒絕；WebView 遠端除錯只在 debug 版開啟；關閉 App 備份，避免登入 cookie 被備份帶走。
- **BTR 加速**：新增「自動」CDN 模式並設為預設；手動執行緒上限改為 32；修正節點排序造成的播放中斷與緩衝歸零；點播緩衝目標改為 120 秒。
- **電視介面修正**：修正退出影片後介面和影片卡片字體被放大兩倍的問題。

每一項的細節和對應檔案記錄在 [HARDENING.md](HARDENING.md)，程式碼裡的修改處都標了 `[btr-hardening]`。

## 安裝與更新

- 第一次安裝：從 [Releases](https://github.com/ChiaWei0804/Lanes-TV/releases) 下載 `Lanes-TV-vX.Y.Z.apk`，用 adb 或隨身碟裝到電視。
- 之後的更新：在 App 裡按「設定 → 關於 → 檢查更新」。Android 8 以上第一次更新時，系統會要求允許本 App「安裝不明應用程式」。
- 發佈流程（給我自己看的）：[RELEASING.md](RELEASING.md)。

## 注意

- 這是個人使用的版本，不提供支援，也不保證功能可用或持續更新。
- 安裝檔用我自己的金鑰簽章，無法覆蓋原版 MyTVB 或 MyTVB-Speed-Boost，但可以和它們同時安裝。
