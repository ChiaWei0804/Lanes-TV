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

MyTVB 原始 README 裡的感謝名單（包含 BBLL 等專案）和免責聲明完整保留在本文件後半段，同樣適用於 Lanes-TV。如果任何原作者不希望這個修改版公開，請開 issue 告訴我，我會下架。

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
- 請勿用於商業用途，也請遵守下方原作者的免責聲明。

---

# MyTVB-Speed-Boost（上游說明，原文保留）

在 [MyTVB](https://github.com/qianxuntudou-ops/MyTVB) 的基础上，为电视端加入参考 [BTR](https://github.com/MrTangLuyao/Bilibili-thread-ripper) 的多 CDN 加速功能，尝试减少视频加载等待和播放卡顿。

## 我们改了什么

- **播放加速**：从多个视频服务器并发下载，加入测速、失败重试和断点续传。
- **设置更方便**：系统设置和播放器设置都有独立的“CDN 加速”入口，可选择地区、节点和线程数。
- **看得到工作状态**：开启 Debug 后，播放画面会显示下载速度、连接节点、缓冲和错误信息。
- **弹幕更好调**：新增弹幕密度设置，并调整顶部显示边距。

因为客户端技术架构不同，原版 BTR 的部分功能还没实现（如：直播加速）

## 怎么用

1. 到 [下载页面](https://github.com/nitansde/MyTVB-Speed-Boost/releases) 下载 APK，安装到电视。
2. 打开“设置 → CDN 加速”，开启加速并选择适合自己的地区。
3. 想查看效果，开启“Debug”，退出设置菜单后看播放画面右上角的信息。

本地测试速度跑得很快，冷门视频在海外开4K也不卡顿

## 来源与协议

- 基础客户端：[MyTVB](https://github.com/qianxuntudou-ops/MyTVB)。下面完整保留原始 README 和声明；当前基线未提供独立 LICENSE 文件。
- 加速参考：[BTR 网页版](https://github.com/MrTangLuyao/Bilibili-thread-ripper) 和 [BTR 桌面版](https://github.com/MrTangLuyao/Bilibili-thread-ripper-desktop)。两者均采用 MIT 协议，完整文本随项目和 APK 保留：[网页版协议](app/src/main/assets/licenses/BTR-MIT.txt)、[桌面版协议](app/src/main/assets/licenses/BTR-Desktop-MIT.txt)。

---

# MyTVB

📺 一个专为 Android TV 设计的第三方 Bilibili 客户端，像素级对齐 BLBL。

## ✨ 主要功能

- 🎬 **视频播放** - 番剧、电影、电视剧、UGC 视频
- 📡 **直播 & CCTV** - 直播弹幕；央视 1-17 套遥控器上下切台
- 💬 **弹幕引擎** - 轻量化引擎，支持人物区域智能防挡。
- ✈️ **空降助手** - 自动跳过恰饭/开场/片尾片段，进度条显示片段标记
- 🎮 **互动视频 / 抖音模式** - 互动分支选择；上下滑动切换推荐
- 👶 **青少年模式** - 家长控制的内容过滤
- 🖱️ **长按快捷操作** - 视频卡片长按唤起快捷菜单
- 📲 **扫码登录** - TV 端扫码快速登录

## 💡 小技巧

- ⏩ **播放倍速** - 播放中长按 OK 临时 2 倍速（在当前倍速上再翻倍，松手还原）；可开启"音乐区视频默认1倍速"让音乐不受全局倍速影响
- 🔄 **回到顶部 / 刷新** - 列表页按菜单键回到顶部并刷新当前分区
- 🎛️ **自定义控制区按键** - 设置 → 播放设置里可自定义默认播放倍速（最高 3.0x）、常驻显示播放倍率，并支持把播放速度等高频操作按键添加到播放控制区

## 🛠️ 技术栈

- **Kotlin 2.1.0** · MVVM + Koin 依赖注入
- **Coroutines + Flow + LiveData** 异步
- **Retrofit + OkHttp + Gson** 网络层
- **Media3 (ExoPlayer)** 视频播放（CCTV 走 WebView）
- **弹幕引擎**（Protobuf 协议解析）
- **DataStore Preferences** 数据存储
- **AndroidX** UI 组件
- **minSdk 23 (Android 6.0)** / **targetSdk 35 (Android 15)**

## 📷 APP 截图

主界面
<img width="1920" height="1080" alt="image" src="https://github.com/user-attachments/assets/b746a1dd-8243-4378-a1ea-c023460323b7" />

播放 & 弹幕
<img width="1920" height="1080" alt="image" src="https://github.com/user-attachments/assets/4a0199e0-64a0-4762-9d45-8ec2ff58524a" />

屏蔽功能
<img width="1920" height="1080" alt="image" src="https://github.com/user-attachments/assets/cae6be14-85cb-41e0-bfee-098214ffbb43" />

## 🙏 感谢

- [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect) - B 站 API 收集整理
- [BBLL](https://github.com/xiaye13579/BBLL) - 绝大部分页面和操作逻辑参考自 BBLL 🥰
- [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) / [BiliPai](https://github.com/jay3-yy/BiliPai) / [Blbl](https://github.com/cat3399/blbl) - 部分关键功能参考
- 其它开源第三方 B 站客户端

## ⚠️ 免责声明

- 📚 **用途**：本项目仅供学习交流与技术研究，不得用于任何非法活动或干扰 B 站正常运营
- ©️ **版权**：所有视频、弹幕、图片、文字等内容版权归 Bilibili 及原创作者所有，本项目不存储任何上述内容，仅作播放呈现
- ™️ **商标**：「Bilibili」「B 站」及相关 Logo、形象均为上海宽娱数码科技有限公司的商标，本项目与之无任何关联
- 🔒 **隐私**：本项目不收集、不上传任何用户数据；登录凭据保存在本地，不上传至任何第三方服务器
- 🚫 **无担保**：本项目「按现状」提供，不保证功能可用、稳定或持续更新，因使用本项目产生的任何直接或间接损失，作者不承担责任
- 🚫 **禁止宣传**：不得在 B 站、官方账号区域（含微博评论区）及微信公众号宣传本项目
- 🚫 **禁止牟利**：不得利用本项目牟利；本项目无任何盈利，第三方盈利与本项目无关
- 📮 **侵权联系**：若本项目侵犯了您的合法权益，请联系作者，确认后将在第一时间处理或下架

> 💡 代码由 Codex 和 [智谱 AI](https://bigmodel.cn/) 编写，如有问题请联系 [OpenAI](https://openai.com/) 或 [智谱](https://bigmodel.cn/) 😤
