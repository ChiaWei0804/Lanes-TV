package com.mytvb.core.common.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [btr-hardening] 在真機上確認平台的安裝檔簽章驗證符合更新器的假設（特別是 Android 9 的
 * getPackageArchiveInfo 會不會擋下內容被竄改的安裝檔）。測試檔由
 * tools/updater-fixtures/make-fixtures.ps1 產生，用一次性的測試金鑰 A 當作「正式金鑰」。
 *
 * 這裡不會啟動安裝程式：只檢查安裝 Intent 能否解析、FileProvider 能否讀到檔案。
 * 要用 -PapplicationId=com.mytvb.btr.updaterprobe 執行，才不會碰到已安裝的 com.mytvb.btr。
 */
@RunWith(AndroidJUnit4::class)
class ApkArchiveProbeTest {

    private lateinit var target: Context
    private lateinit var readyDir: File
    private lateinit var signerA: String

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        target = instrumentation.targetContext
        readyDir = File(target.filesDir, "update/ready").apply { deleteRecursively(); mkdirs() }
        val assets = instrumentation.context.assets
        val json = assets.open("updater/fixtures.json").bufferedReader().use { it.readText() }
        signerA = JsonParser.parseString(json).asJsonObject.get("signerA").asString
        for (name in FIXTURES.keys) {
            assets.open("updater/$name.apk").use { input ->
                File(readyDir, "$name.apk").outputStream().use { input.copyTo(it) }
            }
        }
    }

    @Test
    fun archiveVerdictsMatchExpectations() {
        val validFacts = ApkVerifier.readArchiveFacts(target, File(readyDir, "valid.apk"))
        assertNotNull("valid.apk 讀不到簽章資訊", validFacts)
        val metadata = UpdatePolicy.UpdateMetadata(
            packageName = EXPECTED_PACKAGE,
            versionCode = validFacts!!.versionCode,
            versionName = "0.0.0",
            apkName = "valid.apk",
            size = 0,
            sha256 = "",
            minSdk = 23,
        )
        val expectedSigners = UpdatePolicy.expectedSigners(setOf(signerA), pin = signerA)
        assertEquals(setOf(signerA), expectedSigners)

        val failures = ArrayList<String>()
        for ((name, expected) in FIXTURES) {
            val facts = ApkVerifier.readArchiveFacts(target, File(readyDir, "$name.apk"))
            val verdict = UpdatePolicy.evaluateApk(
                facts = facts,
                metadata = metadata,
                expectedPackage = EXPECTED_PACKAGE,
                installedVersionCode = validFacts.versionCode - 1,
                expectedSigners = expectedSigners,
                deviceSdk = Build.VERSION.SDK_INT,
            )
            Log.i(TAG, "sdk=${Build.VERSION.SDK_INT} $name verdict=$verdict expected=$expected facts=$facts")
            if (verdict != expected) failures += "$name: got $verdict, expected $expected"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun installedSignerOfProbeIsNotTheReleasePin() {
        val installed = ApkVerifier.installedSigners(target)
        Log.i(TAG, "probe installed signers=$installed versionCode=${ApkVerifier.installedVersionCode(target)}")
        assertTrue(installed.isNotEmpty())
        // debug 簽章的安裝不可能等於正式金鑰，所以任何更新都會被拒絕
        assertEquals(emptySet<String>(), UpdatePolicy.expectedSigners(installed))
    }

    @Test
    fun installIntentResolvesAndProviderServesReadyFile() {
        val apk = File(readyDir, "valid.apk")
        val uri = FileProvider.getUriForFile(target, "${target.packageName}.fileprovider", apk)
        val read = target.contentResolver.openInputStream(uri)!!.use { it.readBytes().size.toLong() }
        assertEquals(apk.length(), read)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val handlers = target.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            target.packageManager.canRequestPackageInstalls().toString()
        } else {
            "n/a"
        }
        val settingsHandler = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .resolveActivity(target.packageManager)?.flattenToShortString()
        } else {
            null
        }
        Log.i(TAG, "uri=$uri installHandlers=$handlers canRequestPackageInstalls=$canInstall unknownSourcesSettings=$settingsHandler")
    }

    private companion object {
        const val TAG = "UpdaterProbe"
        const val EXPECTED_PACKAGE = "com.mytvb.btr"

        val FIXTURES: Map<String, UpdatePolicy.Rejection?> = linkedMapOf(
            "valid" to null,
            "wrongkey" to UpdatePolicy.Rejection.WRONG_SIGNER,
            "multi" to UpdatePolicy.Rejection.MULTIPLE_SIGNERS,
            // 輪替從 API 28 才生效；更舊的系統只看得到 v1/v2 的舊簽章者 A，平台本身也會當成 A 安裝
            "rotated" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) UpdatePolicy.Rejection.ROTATED_SIGNER else null,
            "tampered" to UpdatePolicy.Rejection.UNREADABLE_OR_UNSIGNED,
            "unsigned" to UpdatePolicy.Rejection.UNREADABLE_OR_UNSIGNED,
        )
    }
}
