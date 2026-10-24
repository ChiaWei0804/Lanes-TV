package com.mytvb.core.common.update

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.util.Locale

/**
 * [btr-hardening] 應用內更新的判斷規則（純 Kotlin，不碰網路和 Android API，方便單元測試）。
 *
 * 發佈慣例：tag 為 `vX.Y.Z`，附件為 `Lanes-TV-vX.Y.Z.apk` 和 `update.json`。
 * 新舊一律以 versionCode 判斷；versionName 只用來顯示和對照 tag。
 */
object UpdatePolicy {

    data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int =
            compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

        override fun toString(): String = "$major.$minor.$patch"
    }

    data class Asset(val name: String, val url: String, val size: Long)

    data class ReleaseEntry(
        val tag: String,
        val version: SemVer,
        val notes: String,
        val assets: Map<String, Asset>,
    )

    data class UpdateMetadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val apkName: String,
        val size: Long,
        val sha256: String,
        val minSdk: Int,
    )

    /** 從下載的安裝檔讀出來的資訊（由 [ApkVerifier] 產生）。 */
    data class ApkFacts(
        val packageName: String,
        val versionCode: Long,
        val debuggable: Boolean,
        /** 0 表示讀不到（API 23）。 */
        val minSdk: Int,
        /** 目前簽章者憑證的 SHA-256（小寫 hex）。 */
        val signerSha256: List<String>,
        /** 有金鑰輪替紀錄（v3 lineage）。 */
        val hasPastSigners: Boolean,
    )

    enum class Rejection {
        SIZE_MISMATCH,
        HASH_MISMATCH,
        UNREADABLE_OR_UNSIGNED,
        WRONG_PACKAGE,
        DEBUGGABLE,
        MULTIPLE_SIGNERS,
        ROTATED_SIGNER,
        WRONG_SIGNER,
        VERSION_MISMATCH,
        NOT_NEWER,
        SDK_TOO_HIGH,
    }

    // [0-9] 而不是 \d：Android 的 \d 也接受其他文字的數字（例如 ١）。
    // 不接受前導零（v01.2.3）：發佈腳本也禁止，否則它會被當成 1.2.3 參與比較
    private val TAG_REGEX = Regex("""^v(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})$""")
    private val SHA256_REGEX = Regex("""^[0-9a-f]{64}$""")

    fun parseTag(tag: String): SemVer? {
        val m = TAG_REGEX.matchEntire(tag) ?: return null
        val (a, b, c) = m.destructured
        return SemVer(a.toInt(), b.toInt(), c.toInt())
    }

    fun apkAssetName(versionName: String): String = "Lanes-TV-v$versionName.apk"

    /**
     * 解析 `GET /releases` 的一頁。草稿、預先發佈、不符合 `vX.Y.Z` 的 tag 都略過。
     * JSON 格式錯誤會丟出 [UpdateException]（[UpdateException.Reason.BAD_RESPONSE]）。
     */
    fun parseReleasePage(body: String): List<ReleaseEntry> {
        val root = parseJson(body)
        if (!root.isJsonArray) throw UpdateException(UpdateException.Reason.BAD_RESPONSE, "releases 不是陣列")
        val out = ArrayList<ReleaseEntry>()
        for (el in root.asJsonArray) {
            val obj = el.asObjectOrNull() ?: continue
            if (obj.bool("draft") || obj.bool("prerelease")) continue
            val tag = obj.str("tag_name") ?: continue
            val version = parseTag(tag) ?: continue
            val assets = LinkedHashMap<String, Asset>()
            obj.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { a ->
                val ao = a.asObjectOrNull() ?: return@forEach
                val name = ao.str("name") ?: return@forEach
                val url = ao.str("browser_download_url") ?: return@forEach
                val size = ao.long("size") ?: return@forEach
                assets[name] = Asset(name, url, size)
            }
            out += ReleaseEntry(tag, version, obj.str("body").orEmpty().trim(), assets)
        }
        return out
    }

    /** 從 GitHub 的 `Link` header 取出下一頁網址；沒有下一頁回傳 null。 */
    fun nextPageUrl(linkHeader: String?): String? {
        if (linkHeader.isNullOrBlank()) return null
        for (part in linkHeader.split(',')) {
            val segs = part.split(';').map { it.trim() }
            if (segs.size < 2) continue
            val isNext = segs.drop(1).any { it.replace(" ", "") == "rel=\"next\"" }
            if (!isNext) continue
            val target = segs[0]
            if (target.startsWith("<") && target.endsWith(">")) return target.substring(1, target.length - 1)
        }
        return null
    }

    fun selectHighest(entries: List<ReleaseEntry>): ReleaseEntry? = entries.maxByOrNull { it.version }

    /**
     * 解析並檢查 `update.json`：必須和 tag、附件清單一致。
     * 任何不一致都丟出 [UpdateException.Reason.BAD_METADATA]，不會退回舊版本。
     */
    fun parseMetadata(body: String, release: ReleaseEntry): UpdateMetadata {
        fun bad(msg: String): Nothing = throw UpdateException(UpdateException.Reason.BAD_METADATA, msg)
        val root = try { parseJson(body) } catch (e: UpdateException) { bad("update.json 格式錯誤") }
        val obj = root.asObjectOrNull() ?: bad("update.json 不是物件")
        if (obj.long("schema") != UpdateConfig.METADATA_SCHEMA.toLong()) bad("update.json schema 不支援")
        val packageName = obj.str("packageName")?.takeIf { it.isNotBlank() } ?: bad("缺少 packageName")
        val versionCode = obj.long("versionCode")?.takeIf { it > 0 } ?: bad("versionCode 不正確")
        val versionName = obj.str("versionName") ?: bad("缺少 versionName")
        if (versionName != release.version.toString()) bad("versionName 與 tag 不符")
        val apkName = obj.str("apkName") ?: bad("缺少 apkName")
        if (apkName != apkAssetName(versionName)) bad("apkName 不符合命名規則")
        val size = obj.long("size")?.takeIf { it in 1..UpdateConfig.MAX_APK_BYTES } ?: bad("size 不正確")
        val sha256 = obj.str("sha256")?.lowercase(Locale.ROOT)?.takeIf { SHA256_REGEX.matches(it) }
            ?: bad("sha256 不正確")
        val minSdk = obj.long("minSdk")?.takeIf { it in 1..1000 }?.toInt() ?: bad("minSdk 不正確")
        val asset = release.assets[apkName] ?: bad("發佈裡沒有 $apkName")
        if (asset.size != size) bad("$apkName 大小與 update.json 不符")
        return UpdateMetadata(packageName, versionCode, versionName, apkName, size, sha256, minSdk)
    }

    /** 只允許 https、預設連接埠、白名單主機，且不帶帳密。 */
    fun isAllowedUrl(url: String): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host in UpdateConfig.ALLOWED_HOSTS
    }

    /**
     * 只有已安裝的 App 本身正是用正式金鑰簽章時，才接受同一把金鑰的更新；
     * debug 版或其他金鑰的安裝一律回傳空集合（任何更新都會被拒絕）。
     */
    fun expectedSigners(installedSigners: Set<String>, pin: String = UpdateConfig.RELEASE_CERT_SHA256): Set<String> {
        val normalized = installedSigners.map { it.lowercase(Locale.ROOT) }.toSet()
        return if (normalized == setOf(pin.lowercase(Locale.ROOT))) normalized else emptySet()
    }

    /** 下載前就能判斷的條件：版本要更新、裝置 SDK 夠新。 */
    fun preDownloadCheck(metadata: UpdateMetadata, installedVersionCode: Long, deviceSdk: Int): Rejection? = when {
        metadata.versionCode <= installedVersionCode -> Rejection.NOT_NEWER
        metadata.minSdk > deviceSdk -> Rejection.SDK_TOO_HIGH
        else -> null
    }

    /** 下載並驗過雜湊後，對安裝檔本身的最終判斷。回傳 null 代表可以交給安裝程式。 */
    fun evaluateApk(
        facts: ApkFacts?,
        metadata: UpdateMetadata,
        expectedPackage: String,
        installedVersionCode: Long,
        expectedSigners: Set<String>,
        deviceSdk: Int,
    ): Rejection? {
        if (facts == null || facts.signerSha256.isEmpty()) return Rejection.UNREADABLE_OR_UNSIGNED
        if (facts.packageName != expectedPackage || metadata.packageName != expectedPackage) return Rejection.WRONG_PACKAGE
        if (facts.debuggable) return Rejection.DEBUGGABLE
        if (facts.signerSha256.size != 1) return Rejection.MULTIPLE_SIGNERS
        if (facts.hasPastSigners) return Rejection.ROTATED_SIGNER
        if (expectedSigners.isEmpty() || facts.signerSha256.first().lowercase(Locale.ROOT) !in expectedSigners) {
            return Rejection.WRONG_SIGNER
        }
        if (facts.versionCode != metadata.versionCode) return Rejection.VERSION_MISMATCH
        if (facts.versionCode <= installedVersionCode) return Rejection.NOT_NEWER
        if (facts.minSdk > deviceSdk || metadata.minSdk > deviceSdk) return Rejection.SDK_TOO_HIGH
        return null
    }

    private fun parseJson(body: String): JsonElement = try {
        JsonParser.parseString(body)
    } catch (e: Exception) {
        throw UpdateException(UpdateException.Reason.BAD_RESPONSE, "JSON 格式錯誤", e)
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.bool(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false

    private fun JsonObject.long(key: String): Long? {
        val p = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return null
        // 1e999999999999 之類的值：asBigDecimal 丟 NumberFormatException，longValueExact 丟 ArithmeticException
        return try {
            p.asBigDecimal.longValueExact()
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }
}

/** [btr-hardening] 更新流程的錯誤，[reason] 決定顯示給使用者的訊息。 */
class UpdateException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
    val rejection: UpdatePolicy.Rejection? = null,
) : Exception(message, cause) {
    enum class Reason {
        NETWORK,
        RATE_LIMITED,
        NOT_FOUND,
        NO_RELEASE,
        INCOMPLETE,
        BAD_RESPONSE,
        BAD_METADATA,
        DISALLOWED_URL,
        TOO_LARGE,
        REJECTED,
        STORAGE,
    }
}
