package com.mytvb.core.common.update

/**
 * [btr-hardening] 應用內更新的固定設定。
 *
 * 只信任這個 repo 的 GitHub Releases，而且安裝檔必須用 Lanes-TV 自己的正式金鑰簽章。
 * 金鑰指紋由 `apksigner verify --print-certs` 讀出（CN=MyTVB BTR Hardened）。
 * 換金鑰等於所有裝置都無法覆蓋更新，所以這裡只允許一個簽章者、不接受金鑰輪替。
 */
object UpdateConfig {
    const val OWNER = "ChiaWei0804"
    const val REPO = "Lanes-TV"

    const val RELEASES_API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases"
    const val RELEASES_PER_PAGE = 100
    /** 超過這麼多頁就回報「查詢不完整」，不會誤判成已是最新版。 */
    const val MAX_RELEASE_PAGES = 10

    const val METADATA_ASSET_NAME = "update.json"
    const val METADATA_SCHEMA = 1

    const val RELEASE_CERT_SHA256 =
        "60cf14f4207bd7654ac600f34f1e540110a35044610df7c6d576b84dd6418ffc"

    const val MAX_API_PAGE_BYTES = 2L * 1024 * 1024
    const val MAX_METADATA_BYTES = 64L * 1024
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    const val MAX_REDIRECTS = 5

    /** 下載（含所有轉址）只允許這些 https 主機。 */
    val ALLOWED_HOSTS: Set<String> = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )
}
