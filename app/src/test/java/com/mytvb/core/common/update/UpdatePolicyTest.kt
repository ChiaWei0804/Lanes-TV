package com.mytvb.core.common.update

import com.mytvb.core.common.update.UpdatePolicy.Rejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [btr-hardening] 應用內更新的判斷規則。 */
class UpdatePolicyTest {

    private val pin = UpdateConfig.RELEASE_CERT_SHA256
    private val sha = "ab".repeat(32)

    private fun release(tag: String, vararg assets: Pair<String, Long>, draft: Boolean = false, pre: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"body":"notes $tag","assets":[${
            assets.joinToString(",") { (n, s) ->
                """{"name":"$n","size":$s,"browser_download_url":"https://github.com/o/r/releases/download/$tag/$n"}"""
            }
        }]}"""

    private fun entry(version: String, apkSize: Long = 1000): UpdatePolicy.ReleaseEntry =
        UpdatePolicy.parseReleasePage("[${release("v$version", "Lanes-TV-v$version.apk" to apkSize, "update.json" to 300)}]").single()

    private fun metadataJson(
        version: String = "1.0.1",
        versionCode: Any = 105,
        packageName: String = "com.mytvb.btr",
        apkName: String = "Lanes-TV-v$version.apk",
        size: Any = 1000,
        sha256: String = sha,
        minSdk: Any = 23,
        schema: Any = 1,
    ) = """{"schema":$schema,"packageName":"$packageName","versionCode":$versionCode,"versionName":"$version",
        |"apkName":"$apkName","size":$size,"sha256":"$sha256","minSdk":$minSdk}""".trimMargin()

    private fun assertBadMetadata(body: String, release: UpdatePolicy.ReleaseEntry = entry("1.0.1")) {
        try {
            UpdatePolicy.parseMetadata(body, release)
            fail("expected BAD_METADATA for $body")
        } catch (e: UpdateException) {
            assertEquals(UpdateException.Reason.BAD_METADATA, e.reason)
        }
    }

    @Test
    fun tagsMustBeStrictSemver() {
        assertEquals(UpdatePolicy.SemVer(1, 0, 0), UpdatePolicy.parseTag("v1.0.0"))
        assertEquals(UpdatePolicy.SemVer(1, 10, 2), UpdatePolicy.parseTag("v1.10.2"))
        for (bad in listOf("1.0.0", "v1.0", "v1.0.0-beta", "v1.0.0.1", "V1.0.0", " v1.0.0", "v12345.0.0", "vx.y.z", "v1.0.١", "v01.2.3", "v1.02.0", "v1.0.00", "")) {
            assertNull(bad, UpdatePolicy.parseTag(bad))
        }
    }

    @Test
    fun semverOrdersNumericallyNotLexically() {
        assertTrue(UpdatePolicy.parseTag("v1.10.0")!! > UpdatePolicy.parseTag("v1.9.9")!!)
        assertTrue(UpdatePolicy.parseTag("v2.0.0")!! > UpdatePolicy.parseTag("v1.99.99")!!)
    }

    @Test
    fun releasePageSkipsDraftsPrereleasesAndForeignTags() {
        val body = "[" + listOf(
            release("v1.0.0", "Lanes-TV-v1.0.0.apk" to 10),
            release("v9.0.0", draft = true),
            release("v8.0.0", pre = true),
            release("2.0.9-btr.11"),
            release("v1.2.0-rc1"),
            """{"tag_name":42}""",
            """"not an object"""",
            release("v1.1.0", "Lanes-TV-v1.1.0.apk" to 20, "update.json" to 5),
        ).joinToString(",") + "]"
        val entries = UpdatePolicy.parseReleasePage(body)
        assertEquals(listOf("v1.0.0", "v1.1.0"), entries.map { it.tag })
        assertEquals(20L, entries[1].assets["Lanes-TV-v1.1.0.apk"]!!.size)
        assertEquals("notes v1.1.0", entries[1].notes)
    }

    @Test
    fun hugeExponentNumbersAreTreatedAsMissing() {
        val body = """[{"tag_name":"v1.0.0","assets":[
            {"name":"Lanes-TV-v1.0.0.apk","size":1e999999999999,"browser_download_url":"https://github.com/a"},
            {"name":"update.json","size":12,"browser_download_url":"https://github.com/b"}]}]"""
        val entry = UpdatePolicy.parseReleasePage(body).single()
        assertEquals(setOf("update.json"), entry.assets.keys)
        assertBadMetadata(metadataJson(version = "1.0.0", versionCode = "1e999999999999"), entry)
    }

    @Test
    fun highestVersionWinsRegardlessOfOrder() {
        val versions = listOf("1.0.0", "1.10.0", "1.2.0", "1.9.3", "0.9.0")
        val shuffled = versions.shuffled(java.util.Random(7)).map { entry(it) }
        assertEquals("1.10.0", UpdatePolicy.selectHighest(shuffled)!!.version.toString())
        assertNull(UpdatePolicy.selectHighest(emptyList()))
    }

    @Test
    fun malformedReleasePageIsBadResponse() {
        for (body in listOf("", "{", "{\"message\":\"Not Found\"}", "null")) {
            try {
                UpdatePolicy.parseReleasePage(body)
                fail("expected BAD_RESPONSE for '$body'")
            } catch (e: UpdateException) {
                assertEquals(UpdateException.Reason.BAD_RESPONSE, e.reason)
            }
        }
    }

    @Test
    fun linkHeaderNextPage() {
        val header = "<https://api.github.com/repositories/1/releases?per_page=100&page=2>; rel=\"next\", " +
            "<https://api.github.com/repositories/1/releases?per_page=100&page=5>; rel=\"last\""
        assertEquals("https://api.github.com/repositories/1/releases?per_page=100&page=2", UpdatePolicy.nextPageUrl(header))
        val lastPage = "<https://api.github.com/x?page=1>; rel=\"first\", <https://api.github.com/x?page=4>; rel=\"prev\""
        assertNull(UpdatePolicy.nextPageUrl(lastPage))
        assertNull(UpdatePolicy.nextPageUrl(null))
        assertNull(UpdatePolicy.nextPageUrl(""))
        assertNull(UpdatePolicy.nextPageUrl("garbage"))
    }

    @Test
    fun metadataHappyPath() {
        val m = UpdatePolicy.parseMetadata(metadataJson(sha256 = sha.uppercase()), entry("1.0.1"))
        assertEquals(105L, m.versionCode)
        assertEquals("Lanes-TV-v1.0.1.apk", m.apkName)
        assertEquals(sha, m.sha256)
        assertEquals(23, m.minSdk)
    }

    @Test
    fun metadataMustMatchTagAssetsAndSchema() {
        assertBadMetadata("not json")
        assertBadMetadata("[]")
        assertBadMetadata(metadataJson(schema = 2))
        assertBadMetadata(metadataJson(version = "1.0.2"))                         // versionName 與 tag 不符
        assertBadMetadata(metadataJson(apkName = "evil.apk"))
        assertBadMetadata(metadataJson(size = 999))                                // 與附件大小不符
        assertBadMetadata(metadataJson(size = UpdateConfig.MAX_APK_BYTES + 1))
        assertBadMetadata(metadataJson(sha256 = "xyz"))
        assertBadMetadata(metadataJson(versionCode = 0))
        assertBadMetadata(metadataJson(versionCode = "\"105\""))
        assertBadMetadata(metadataJson(versionCode = 105.5))
        assertBadMetadata(metadataJson(minSdk = 0))
        assertBadMetadata(metadataJson(packageName = ""))
        // 發佈裡沒有對應的安裝檔
        val noApk = UpdatePolicy.parseReleasePage("[${release("v1.0.1", "update.json" to 300)}]").single()
        assertBadMetadata(metadataJson(), noApk)
    }

    @Test
    fun urlAllowlist() {
        assertTrue(UpdatePolicy.isAllowedUrl("https://api.github.com/repos/o/r/releases"))
        assertTrue(UpdatePolicy.isAllowedUrl("https://github.com/o/r/releases/download/v1.0.0/a.apk"))
        assertTrue(UpdatePolicy.isAllowedUrl("https://release-assets.githubusercontent.com/x?sig=1"))
        assertTrue(UpdatePolicy.isAllowedUrl("HTTPS://GitHub.com:443/o"))
        for (bad in listOf(
            "http://github.com/o",
            "https://github.com.evil.com/o",
            "https://evil.com/github.com",
            "https://user:pw@github.com/o",
            "https://github.com:8443/o",
            "file:///sdcard/a.apk",
            "content://x/a.apk",
            "https://raw.githubusercontent.com/o/r/main/a.apk",
            "not a url",
        )) {
            assertFalse(bad, UpdatePolicy.isAllowedUrl(bad))
        }
    }

    @Test
    fun expectedSignersOnlyForPinnedInstall() {
        assertEquals(setOf(pin), UpdatePolicy.expectedSigners(setOf(pin)))
        assertEquals(setOf(pin), UpdatePolicy.expectedSigners(setOf(pin.uppercase())))
        assertEquals(emptySet<String>(), UpdatePolicy.expectedSigners(setOf(sha)))           // debug 或其他金鑰
        assertEquals(emptySet<String>(), UpdatePolicy.expectedSigners(setOf(pin, sha)))      // 多簽章者
        assertEquals(emptySet<String>(), UpdatePolicy.expectedSigners(emptySet()))
    }

    @Test
    fun preDownloadCheck() {
        val m = UpdatePolicy.parseMetadata(metadataJson(versionCode = 105, minSdk = 26), entry("1.0.1"))
        assertNull(UpdatePolicy.preDownloadCheck(m, installedVersionCode = 104, deviceSdk = 28))
        assertEquals(Rejection.NOT_NEWER, UpdatePolicy.preDownloadCheck(m, installedVersionCode = 105, deviceSdk = 28))
        assertEquals(Rejection.NOT_NEWER, UpdatePolicy.preDownloadCheck(m, installedVersionCode = Long.MAX_VALUE, deviceSdk = 28))
        assertEquals(Rejection.SDK_TOO_HIGH, UpdatePolicy.preDownloadCheck(m, installedVersionCode = 104, deviceSdk = 25))
    }

    @Test
    fun evaluateApkRejections() {
        val m = UpdatePolicy.parseMetadata(metadataJson(versionCode = 105), entry("1.0.1"))
        val good = UpdatePolicy.ApkFacts("com.mytvb.btr", 105, false, 23, listOf(pin), false)
        fun eval(facts: UpdatePolicy.ApkFacts?, installed: Long = 104, signers: Set<String> = setOf(pin), sdk: Int = 34) =
            UpdatePolicy.evaluateApk(facts, m, "com.mytvb.btr", installed, signers, sdk)

        assertNull(eval(good))
        assertEquals(Rejection.UNREADABLE_OR_UNSIGNED, eval(null))
        assertEquals(Rejection.UNREADABLE_OR_UNSIGNED, eval(good.copy(signerSha256 = emptyList())))
        assertEquals(Rejection.WRONG_PACKAGE, eval(good.copy(packageName = "com.mytvb")))
        assertEquals(Rejection.DEBUGGABLE, eval(good.copy(debuggable = true)))
        assertEquals(Rejection.MULTIPLE_SIGNERS, eval(good.copy(signerSha256 = listOf(pin, sha))))
        assertEquals(Rejection.ROTATED_SIGNER, eval(good.copy(hasPastSigners = true)))
        assertEquals(Rejection.WRONG_SIGNER, eval(good.copy(signerSha256 = listOf(sha))))
        assertEquals(Rejection.WRONG_SIGNER, eval(good, signers = emptySet()))
        assertEquals(Rejection.VERSION_MISMATCH, eval(good.copy(versionCode = 106)))
        assertEquals(Rejection.NOT_NEWER, eval(good, installed = 105))
        assertEquals(Rejection.SDK_TOO_HIGH, eval(good.copy(minSdk = 35)))
        // 已安裝 103（目前的 hardened.7）可以升到 104 以上
        assertNull(eval(good, installed = 103))
        // metadata 的套件名稱不對也拒絕
        val wrongMeta = UpdatePolicy.parseMetadata(metadataJson(packageName = "com.evil"), entry("1.0.1"))
        assertEquals(Rejection.WRONG_PACKAGE, UpdatePolicy.evaluateApk(good, wrongMeta, "com.mytvb.btr", 104, setOf(pin), 34))
    }
}
