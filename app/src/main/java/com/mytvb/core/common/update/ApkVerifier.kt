package com.mytvb.core.common.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/**
 * [btr-hardening] 讀取安裝檔與已安裝 App 的簽章資訊。
 *
 * 一定要帶 GET_SIGNATURES：Android 9 的 getPackageArchiveInfo 只有在這個旗標下才會
 * 實際驗證安裝檔的簽章（collectCertificates 不跳過驗證）；API 28+ 另外帶
 * GET_SIGNING_CERTIFICATES 取得 SigningInfo（多簽章者、金鑰輪替）。
 * 驗證失敗或沒有簽章時回傳 null。
 */
object ApkVerifier {

    @Suppress("DEPRECATION")
    private val SIGNATURE_FLAGS: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    fun readArchiveFacts(context: Context, apk: File): UpdatePolicy.ApkFacts? {
        val info = try {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, SIGNATURE_FLAGS)
        } catch (_: Exception) {
            null
        } ?: return null
        val appInfo = info.applicationInfo ?: return null
        val signers = signerInfo(info) ?: return null
        return UpdatePolicy.ApkFacts(
            packageName = info.packageName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            debuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 0,
            signerSha256 = signers.first,
            hasPastSigners = signers.second,
        )
    }

    /** 目前安裝的 App 的簽章者（SHA-256）。讀不到時回傳空集合。 */
    fun installedSigners(context: Context): Set<String> {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, SIGNATURE_FLAGS)
        } catch (_: Exception) {
            return emptySet()
        }
        val signers = signerInfo(info) ?: return emptySet()
        // 已安裝的 App 若有輪替紀錄，也不視為正式版（正式版不輪替金鑰）
        if (signers.second) return emptySet()
        return signers.first.toSet()
    }

    fun installedVersionCode(context: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    } catch (_: Exception) {
        Long.MAX_VALUE // 讀不到就不允許任何更新
    }

    /** first = 目前簽章者憑證的 SHA-256；second = 是否有過去的簽章者（金鑰輪替）。 */
    @Suppress("DEPRECATION")
    private fun signerInfo(info: PackageInfo): Pair<List<String>, Boolean>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo
            if (si != null) {
                val current = si.apkContentsSigners.orEmpty()
                if (current.isEmpty()) return null
                val rotated = !si.hasMultipleSigners() && si.hasPastSigningCertificates()
                return current.map { sha256Hex(it) } to rotated
            }
        }
        val legacy = info.signatures.orEmpty()
        if (legacy.isEmpty()) return null
        return legacy.map { sha256Hex(it) } to false
    }

    private fun sha256Hex(sig: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).toHex()

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
