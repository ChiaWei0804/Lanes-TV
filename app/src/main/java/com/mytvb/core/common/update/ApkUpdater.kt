package com.mytvb.core.common.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mytvb.core.common.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [btr-hardening] 應用內更新（Android 端）：查詢、下載、驗證簽章、交給系統安裝程式。
 *
 * 只信任這個 fork 自己的 GitHub Releases（[UpdateConfig]），而且安裝檔必須和目前安裝的 App
 * 用同一把正式金鑰簽章（[UpdatePolicy.expectedSigners]）。網路部分在 [UpdateClient]。
 *
 * 檔案位置（都在 filesDir，不會被「清除快取」或系統回收）：
 * - `update/staging/`：下載中、尚未驗證
 * - `update/ready/`：驗證通過、等待安裝（FileProvider 只分享這個資料夾）
 */
object ApkUpdater {

    private const val TAG = "ApkUpdater"
    private const val COOLDOWN_MS = 5_000L
    private const val UPDATE_DIR = "update"
    private const val READY_KEEP_MS = 24L * 60 * 60 * 1000

    @Volatile
    private var lastStartedAtMs: Long = 0L

    private val client: UpdateClient by lazy { UpdateClient() }

    fun markStarted(nowMs: Long = System.currentTimeMillis()) {
        lastStartedAtMs = nowMs
    }

    fun cooldownLeftMs(nowMs: Long = System.currentTimeMillis()): Long =
        ((lastStartedAtMs + COOLDOWN_MS) - nowMs).coerceAtLeast(0)

    suspend fun check(context: Context): UpdateClient.CheckResult {
        val app = context.applicationContext
        return client.check(
            expectedPackage = app.packageName,
            installedVersionCode = ApkVerifier.installedVersionCode(app),
            deviceSdk = Build.VERSION.SDK_INT,
        )
    }

    /**
     * 下載並驗證；成功時回傳 `update/ready/` 裡可以直接安裝的檔案。
     * 驗證失敗丟出 [UpdateException]（[UpdateException.Reason.REJECTED] 帶有 [UpdatePolicy.Rejection]），
     * 不會留下任何可安裝的檔案。
     */
    suspend fun downloadAndVerify(
        context: Context,
        candidate: UpdateClient.Candidate,
        onProgress: (UpdateClient.Progress) -> Unit,
    ): File {
        val app = context.applicationContext
        val root = File(app.filesDir, UPDATE_DIR)
        val staging = File(root, "staging")
        val ready = File(root, "ready")
        withContext(Dispatchers.IO) {
            staging.deleteRecursively()
            if (!staging.mkdirs() && !staging.isDirectory) storageError("無法建立 $staging")
        }
        val part = File(staging, "${candidate.versionCode}.apk")
        client.download(candidate, part, onProgress)

        return withContext(Dispatchers.IO) {
            val facts = ApkVerifier.readArchiveFacts(app, part)
            val rejection = UpdatePolicy.evaluateApk(
                facts = facts,
                metadata = candidate.metadata,
                expectedPackage = app.packageName,
                installedVersionCode = ApkVerifier.installedVersionCode(app),
                expectedSigners = UpdatePolicy.expectedSigners(ApkVerifier.installedSigners(app)),
                deviceSdk = Build.VERSION.SDK_INT,
            )
            if (rejection != null) {
                part.delete()
                AppLog.w(TAG, "rejected ${candidate.versionName}: $rejection facts=$facts")
                throw UpdateException(UpdateException.Reason.REJECTED, "安裝檔驗證失敗：$rejection", rejection = rejection)
            }
            ready.deleteRecursively()
            if (!ready.mkdirs() && !ready.isDirectory) {
                part.delete()
                storageError("無法建立 $ready")
            }
            val target = File(ready, "${candidate.versionCode}-${candidate.metadata.sha256.take(8)}.apk")
            if (!part.renameTo(target)) {
                part.delete()
                target.delete()
                storageError("無法移動安裝檔")
            }
            AppLog.i(TAG, "verified ${candidate.versionName} (${candidate.versionCode}) -> ${target.name}")
            target
        }
    }

    /** API 26 起需要「安裝不明應用程式」權限；API 23–25 由系統安裝程式自己詢問。 */
    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** 開啟本 App 的「安裝不明應用程式」設定頁；裝置沒有這個頁面（例如當貝）時回傳 null。 */
    fun unknownSourcesSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri(apkFile.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 啟動時清理：刪掉下載到一半的檔案、舊版留在 cacheDir 的安裝檔；
     * ready/ 只保留 24 小時內的（安裝程式可能還在讀）。不連網。
     */
    fun cleanupStaleDownload(context: Context) {
        val app = context.applicationContext
        Thread {
            runCatching {
                File(app.cacheDir, UPDATE_DIR).takeIf { it.exists() }?.deleteRecursively()
                val root = File(app.filesDir, UPDATE_DIR)
                File(root, "staging").takeIf { it.exists() }?.deleteRecursively()
                val now = System.currentTimeMillis()
                File(root, "ready").listFiles()?.forEach { f ->
                    if (now - f.lastModified() > READY_KEEP_MS) f.delete()
                }
            }.onFailure { AppLog.w(TAG, "cleanupStaleDownload failed: $it") }
        }.apply { isDaemon = true }.start()
    }

    private fun storageError(message: String): Nothing =
        throw UpdateException(UpdateException.Reason.STORAGE, message)
}
