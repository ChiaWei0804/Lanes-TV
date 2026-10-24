package com.mytvb.core.common.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * [btr-hardening] 更新的網路部分：查詢 GitHub Releases、下載 update.json 和安裝檔。
 *
 * - 獨立的 OkHttp client（不帶 App 的 cookie 或攔截器），不自動轉址
 * - 每一跳（含轉址）都必須是 https + 白名單主機（[urlAllowed]）
 * - 每個回應都有大小上限；下載時邊寫邊算 SHA-256
 * - 協程取消時呼叫 [okhttp3.Call.cancel]，卡住的讀取會立刻中斷
 *
 * 不碰 Android API，方便用 MockWebServer 測試；簽章驗證在 [ApkUpdater]。
 */
class UpdateClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val releasesUrl: String = UpdateConfig.RELEASES_API_URL,
    private val urlAllowed: (String) -> Boolean = UpdatePolicy::isAllowedUrl,
    private val retryDelayMs: Long = 1_000L,
) {

    /** 確認要更新後，對話框和下載都只使用這個不可變的物件。 */
    data class Candidate(
        val release: UpdatePolicy.ReleaseEntry,
        val metadata: UpdatePolicy.UpdateMetadata,
        val apkUrl: String,
    ) {
        val versionName: String get() = metadata.versionName
        val versionCode: Long get() = metadata.versionCode
        val notes: String get() = release.notes
    }

    sealed class CheckResult {
        /** [latestVersionName] 為 GitHub 上最新的版本（可能比已安裝的舊）。 */
        data class UpToDate(val latestVersionName: String) : CheckResult()
        data class Available(val candidate: Candidate) : CheckResult()
    }

    sealed class Progress {
        data object Connecting : Progress()

        data class Downloading(
            val downloadedBytes: Long,
            val totalBytes: Long,
            val bytesPerSecond: Long,
        ) : Progress() {
            val percent: Int =
                if (totalBytes <= 0) 0
                else ((downloadedBytes.toDouble() / totalBytes) * 100.0).roundToInt().coerceIn(0, 100)
        }

        data class Retrying(val attempt: Int, val maxAttempts: Int) : Progress()
    }

    /** 查詢最新的正式版。分頁會一直讀到最後一頁；超過上限回報 INCOMPLETE，不會誤判成已是最新。 */
    suspend fun check(expectedPackage: String, installedVersionCode: Long, deviceSdk: Int): CheckResult {
        val releases = ArrayList<UpdatePolicy.ReleaseEntry>()
        var url: String? = "$releasesUrl?per_page=${UpdateConfig.RELEASES_PER_PAGE}"
        var pages = 0
        while (url != null) {
            if (pages >= UpdateConfig.MAX_RELEASE_PAGES) {
                throw UpdateException(UpdateException.Reason.INCOMPLETE, "發佈清單超過 ${UpdateConfig.MAX_RELEASE_PAGES} 頁")
            }
            val page = fetchText(url, UpdateConfig.MAX_API_PAGE_BYTES, ACCEPT_GITHUB_JSON)
            releases += UpdatePolicy.parseReleasePage(page.body)
            url = UpdatePolicy.nextPageUrl(page.link)
            pages++
        }
        val latest = UpdatePolicy.selectHighest(releases)
            ?: throw UpdateException(UpdateException.Reason.NO_RELEASE, "沒有可用的正式版")

        val metaAsset = latest.assets[UpdateConfig.METADATA_ASSET_NAME]
            ?: throw UpdateException(UpdateException.Reason.BAD_METADATA, "${latest.tag} 缺少 ${UpdateConfig.METADATA_ASSET_NAME}")
        if (metaAsset.size > UpdateConfig.MAX_METADATA_BYTES) {
            throw UpdateException(UpdateException.Reason.TOO_LARGE, "${UpdateConfig.METADATA_ASSET_NAME} 太大")
        }
        val metadata = UpdatePolicy.parseMetadata(
            fetchText(metaAsset.url, UpdateConfig.MAX_METADATA_BYTES, ACCEPT_OCTET).body,
            latest,
        )
        if (metadata.packageName != expectedPackage) {
            throw UpdateException(
                UpdateException.Reason.REJECTED, "套件名稱不符：${metadata.packageName}",
                rejection = UpdatePolicy.Rejection.WRONG_PACKAGE,
            )
        }
        when (val rejection = UpdatePolicy.preDownloadCheck(metadata, installedVersionCode, deviceSdk)) {
            null -> {}
            UpdatePolicy.Rejection.NOT_NEWER -> return CheckResult.UpToDate(metadata.versionName)
            else -> throw UpdateException(UpdateException.Reason.REJECTED, "此裝置無法安裝 ${metadata.versionName}", rejection = rejection)
        }
        val apk = latest.assets.getValue(metadata.apkName) // parseMetadata 已確認存在
        return CheckResult.Available(Candidate(latest, metadata, apk.url))
    }

    /**
     * 把安裝檔下載到 [target]，大小和 SHA-256 都必須與 update.json 相同，否則刪檔並丟出 REJECTED。
     * 網路錯誤最多重試 3 次（每次從頭下載）。
     */
    suspend fun download(candidate: Candidate, target: File, onProgress: (Progress) -> Unit) {
        onProgress(Progress.Connecting)
        retrying(onRetry = { attempt, max -> onProgress(Progress.Retrying(attempt, max)) }) {
            try {
                downloadOnce(candidate, target, onProgress)
            } catch (t: Throwable) {
                target.delete()
                throw t
            }
        }
    }

    private suspend fun downloadOnce(candidate: Candidate, target: File, onProgress: (Progress) -> Unit) {
        val meta = candidate.metadata
        open(candidate.apkUrl, ACCEPT_OCTET) { response ->
            val declared = response.body?.contentLength() ?: -1L
            if (declared >= 0 && declared != meta.size) throw rejected(UpdatePolicy.Rejection.SIZE_MISMATCH, "大小不符")
            val md = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            target.parentFile?.mkdirs()
            withContext(Dispatchers.IO) {
                response.body!!.byteStream().use { input ->
                    storage { FileOutputStream(target) }.use { output ->
                        val buf = ByteArray(64 * 1024)
                        var lastEmitAt = 0L
                        var speedAt = System.currentTimeMillis()
                        var speedBytes = 0L
                        var bytesPerSecond = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            downloaded += n
                            if (downloaded > meta.size) throw rejected(UpdatePolicy.Rejection.SIZE_MISMATCH, "下載超過預期大小")
                            storage { output.write(buf, 0, n) }
                            md.update(buf, 0, n)
                            speedBytes += n
                            val now = System.currentTimeMillis()
                            if (now - speedAt >= 1_000) {
                                bytesPerSecond = speedBytes * 1_000 / (now - speedAt)
                                speedBytes = 0
                                speedAt = now
                            }
                            if (now - lastEmitAt >= 200) {
                                lastEmitAt = now
                                onProgress(Progress.Downloading(downloaded, meta.size, bytesPerSecond))
                            }
                        }
                        storage { output.fd.sync() }
                    }
                }
            }
            if (downloaded != meta.size) throw rejected(UpdatePolicy.Rejection.SIZE_MISMATCH, "下載不完整")
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != meta.sha256) throw rejected(UpdatePolicy.Rejection.HASH_MISMATCH, "SHA-256 不符")
            onProgress(Progress.Downloading(downloaded, meta.size, 0))
        }
    }

    /** 寫入本機檔案的錯誤（空間不足等）回報為 STORAGE，不當成網路錯誤重試。 */
    private inline fun <T> storage(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw UpdateException(UpdateException.Reason.STORAGE, "無法寫入安裝檔：${e.message}", e)
    }

    private class Page(val body: String, val link: String?)

    private suspend fun fetchText(url: String, maxBytes: Long, accept: String): Page = retrying {
        open(url, accept) { response ->
            val declared = response.body?.contentLength() ?: -1L
            if (declared > maxBytes) throw UpdateException(UpdateException.Reason.TOO_LARGE, "回應太大")
            val bytes = withContext(Dispatchers.IO) { response.body!!.byteStream().use { readCapped(it, maxBytes) } }
            Page(String(bytes, Charsets.UTF_8), response.header("Link"))
        }
    }

    private fun readCapped(input: InputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw UpdateException(UpdateException.Reason.TOO_LARGE, "回應太大")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private sealed class Hop<out T> {
        class Redirect(val url: String) : Hop<Nothing>()
        class Done<T>(val value: T) : Hop<T>()
    }

    /**
     * 自己處理轉址：每一跳都檢查網址，最多 [UpdateConfig.MAX_REDIRECTS] 次。
     * 成功（2xx）時把回應交給 [block]，用完自動關閉；協程取消會中斷連線。
     */
    private suspend fun <T> open(startUrl: String, accept: String, block: suspend (Response) -> T): T {
        var url = startUrl
        var redirects = 0
        while (true) {
            if (!urlAllowed(url)) throw UpdateException(UpdateException.Reason.DISALLOWED_URL, "不允許的網址：${hostOf(url)}")
            val request = Request.Builder()
                .url(url)
                .header("Accept", accept)
                .header("User-Agent", USER_AGENT)
                .apply { if (accept == ACCEPT_GITHUB_JSON) header("X-GitHub-Api-Version", GITHUB_API_VERSION) }
                .get()
                .build()
            val call = http.newCall(request)
            val hop: Hop<T> = coroutineScope {
                val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
                try {
                    val response = withContext(Dispatchers.IO) {
                        try {
                            call.execute()
                        } catch (e: IOException) {
                            ensureActive()
                            throw UpdateException(UpdateException.Reason.NETWORK, "連線失敗：${e.message}", e)
                        }
                    }
                    response.use { r ->
                        if (r.code in REDIRECT_CODES) {
                            val location = r.header("Location")
                                ?: throw UpdateException(UpdateException.Reason.BAD_RESPONSE, "轉址缺少 Location")
                            Hop.Redirect(
                                r.request.url.resolve(location)?.toString()
                                    ?: throw UpdateException(UpdateException.Reason.DISALLOWED_URL, "轉址網址無效"),
                            )
                        } else {
                            checkStatus(r)
                            try {
                                Hop.Done(block(r))
                            } catch (e: IOException) {
                                // 讀取本文時斷線，或取消後 call.cancel() 造成的 SocketException
                                ensureActive()
                                throw UpdateException(UpdateException.Reason.NETWORK, "讀取中斷：${e.message}", e)
                            }
                        }
                    }
                } finally {
                    watcher.cancel()
                }
            }
            when (hop) {
                is Hop.Done -> return hop.value
                is Hop.Redirect -> {
                    if (++redirects > UpdateConfig.MAX_REDIRECTS) {
                        throw UpdateException(UpdateException.Reason.BAD_RESPONSE, "轉址次數過多")
                    }
                    url = hop.url
                }
            }
        }
    }

    private fun checkStatus(r: Response) {
        if (r.isSuccessful) return
        val rateLimited = r.code == 429 || (r.code == 403 && r.header("X-RateLimit-Remaining") == "0")
        when {
            rateLimited -> throw UpdateException(UpdateException.Reason.RATE_LIMITED, "GitHub 暫時限制查詢")
            r.code == 404 -> throw UpdateException(UpdateException.Reason.NOT_FOUND, "找不到（HTTP 404）")
            r.code >= 500 -> throw UpdateException(UpdateException.Reason.NETWORK, "伺服器錯誤（HTTP ${r.code}）")
            else -> throw UpdateException(UpdateException.Reason.BAD_RESPONSE, "HTTP ${r.code}")
        }
    }

    /** 只重試網路錯誤和 5xx；限流、404、驗證失敗都不重試。 */
    private suspend fun <T> retrying(
        maxAttempts: Int = 3,
        onRetry: (attempt: Int, max: Int) -> Unit = { _, _ -> },
        block: suspend () -> T,
    ): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: UpdateException) {
                if (e.reason != UpdateException.Reason.NETWORK || attempt >= maxAttempts) throw e
                onRetry(attempt, maxAttempts)
                delay(retryDelayMs * attempt)
                attempt++
            }
        }
    }

    private fun rejected(rejection: UpdatePolicy.Rejection, message: String) =
        UpdateException(UpdateException.Reason.REJECTED, message, rejection = rejection)

    private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: "?"

    companion object {
        private const val ACCEPT_GITHUB_JSON = "application/vnd.github+json"
        private const val ACCEPT_OCTET = "application/octet-stream"
        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val USER_AGENT = "Lanes-TV-Updater"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** 先試 IPv4（部分網路的 IPv6 到 GitHub 不通），IPv6 留作後備。 */
        private val Ipv4FirstDns = object : Dns {
            override fun lookup(hostname: String) =
                Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(Ipv4FirstDns)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
