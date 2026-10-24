package com.mytvb.core.common.update

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** [btr-hardening] UpdateClient 的網路行為（分頁、轉址、大小上限、限流、雜湊、取消）。 */
class UpdateClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val routes = HashMap<String, () -> MockResponse>()

    private val apkBytes = ByteArray(200_000) { (it * 31).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.requestUrl!!.encodedPath]?.invoke() ?: MockResponse().setResponseCode(404)
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(allowed: (String) -> Boolean = { !it.contains("/evil") }) = UpdateClient(
        http = UpdateClient.defaultHttpClient().newBuilder().readTimeout(5, TimeUnit.SECONDS).build(),
        releasesUrl = server.url("/releases").toString(),
        urlAllowed = allowed,
        retryDelayMs = 0,
    )

    private fun url(path: String) = server.url(path).toString()

    private fun releaseJson(version: String, apkSize: Long = apkBytes.size.toLong()) =
        """{"tag_name":"v$version","draft":false,"prerelease":false,"body":"notes","assets":[
           {"name":"Lanes-TV-v$version.apk","size":$apkSize,"browser_download_url":"${url("/dl/v$version/apk")}"},
           {"name":"update.json","size":300,"browser_download_url":"${url("/dl/v$version/meta")}"}]}"""

    private fun metaJson(version: String, versionCode: Long, sha: String = apkSha, size: Long = apkBytes.size.toLong()) =
        """{"schema":1,"packageName":"com.mytvb.btr","versionCode":$versionCode,"versionName":"$version",
           "apkName":"Lanes-TV-v$version.apk","size":$size,"sha256":"$sha","minSdk":23}"""

    private fun json(body: String) = MockResponse().setBody(body)

    private fun publish(version: String, versionCode: Long, sha: String = apkSha, apk: ByteArray = apkBytes) {
        routes["/dl/v$version/meta"] = { json(metaJson(version, versionCode, sha)) }
        routes["/dl/v$version/apk"] = { MockResponse().setBody(Buffer().write(apk)) }
    }

    private fun expectFailure(reason: UpdateException.Reason, block: suspend () -> Unit): UpdateException {
        try {
            runBlocking { block() }
        } catch (e: UpdateException) {
            assertEquals(e.message, reason, e.reason)
            return e
        }
        fail("expected $reason")
        throw AssertionError()
    }

    @Test
    fun followsLinkPaginationAndPicksHighest() {
        routes["/releases"] = {
            json("[${releaseJson("1.0.0")}]").addHeader("Link", "<${url("/releases/p2")}>; rel=\"next\"")
        }
        routes["/releases/p2"] = { json("[${releaseJson("1.2.0")},${releaseJson("1.1.0")}]") }
        publish("1.2.0", 106)

        val result = runBlocking { client().check("com.mytvb.btr", installedVersionCode = 104, deviceSdk = 34) }
        val candidate = (result as UpdateClient.CheckResult.Available).candidate
        assertEquals("1.2.0", candidate.versionName)
        assertEquals(106L, candidate.versionCode)
        assertEquals(url("/dl/v1.2.0/apk"), candidate.apkUrl)
        val first = server.takeRequest()
        assertEquals("100", first.requestUrl!!.queryParameter("per_page"))
        assertEquals("application/vnd.github+json", first.getHeader("Accept"))
    }

    @Test
    fun upToDateWhenVersionCodeNotNewer() {
        routes["/releases"] = { json("[${releaseJson("1.0.0")}]") }
        publish("1.0.0", 104)
        val result = runBlocking { client().check("com.mytvb.btr", installedVersionCode = 104, deviceSdk = 34) }
        assertEquals(UpdateClient.CheckResult.UpToDate("1.0.0"), result)
        assertEquals(2, server.requestCount) // 清單 + update.json，沒有下載安裝檔
    }

    @Test
    fun endlessPaginationIsIncompleteNotLatest() {
        routes["/releases"] = { json("[]").addHeader("Link", "<${url("/releases")}>; rel=\"next\"") }
        expectFailure(UpdateException.Reason.INCOMPLETE) { client().check("com.mytvb.btr", 104, 34) }
        assertEquals(UpdateConfig.MAX_RELEASE_PAGES, server.requestCount)
    }

    @Test
    fun noStableReleaseIsReported() {
        routes["/releases"] = { json("[]") }
        expectFailure(UpdateException.Reason.NO_RELEASE) { client().check("com.mytvb.btr", 104, 34) }
    }

    @Test
    fun rateLimitIsNotRetried() {
        routes["/releases"] = { MockResponse().setResponseCode(403).addHeader("X-RateLimit-Remaining", "0") }
        expectFailure(UpdateException.Reason.RATE_LIMITED) { client().check("com.mytvb.btr", 104, 34) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun serverErrorIsRetriedThenSucceeds() {
        var calls = 0
        routes["/releases"] = { if (++calls < 3) MockResponse().setResponseCode(502) else json("[${releaseJson("1.0.0")}]") }
        publish("1.0.0", 104)
        val result = runBlocking { client().check("com.mytvb.btr", 104, 34) }
        assertTrue(result is UpdateClient.CheckResult.UpToDate)
        assertEquals(3, calls)
    }

    @Test
    fun redirectToDisallowedUrlIsRefusedBeforeRequest() {
        routes["/releases"] = { MockResponse().setResponseCode(302).addHeader("Location", "/evil/releases") }
        routes["/evil/releases"] = { json("[]") }
        expectFailure(UpdateException.Reason.DISALLOWED_URL) { client().check("com.mytvb.btr", 104, 34) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun allowedRedirectIsFollowed() {
        routes["/releases"] = { MockResponse().setResponseCode(301).addHeader("Location", "/moved") }
        routes["/moved"] = { json("[${releaseJson("1.0.0")}]") }
        publish("1.0.0", 104)
        assertTrue(runBlocking { client().check("com.mytvb.btr", 104, 34) } is UpdateClient.CheckResult.UpToDate)
    }

    @Test
    fun redirectLoopIsCapped() {
        routes["/releases"] = { MockResponse().setResponseCode(302).addHeader("Location", "/releases") }
        expectFailure(UpdateException.Reason.BAD_RESPONSE) { client().check("com.mytvb.btr", 104, 34) }
        assertEquals(UpdateConfig.MAX_REDIRECTS + 1, server.requestCount)
    }

    @Test
    fun startUrlIsCheckedToo() {
        routes["/releases"] = { json("[]") }
        expectFailure(UpdateException.Reason.DISALLOWED_URL) { client(allowed = { false }).check("com.mytvb.btr", 104, 34) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun oversizedApiPageIsRejected() {
        val big = "[" + " ".repeat(UpdateConfig.MAX_API_PAGE_BYTES.toInt()) + "]"
        routes["/releases"] = { json(big) }
        expectFailure(UpdateException.Reason.TOO_LARGE) { client().check("com.mytvb.btr", 104, 34) }
    }

    @Test
    fun wrongPackageInMetadataIsRejected() {
        routes["/releases"] = { json("[${releaseJson("1.0.1")}]") }
        routes["/dl/v1.0.1/meta"] = { json(metaJson("1.0.1", 105).replace("com.mytvb.btr", "com.evil")) }
        val e = expectFailure(UpdateException.Reason.REJECTED) { client().check("com.mytvb.btr", 104, 34) }
        assertEquals(UpdatePolicy.Rejection.WRONG_PACKAGE, e.rejection)
    }

    private fun candidate(version: String = "1.0.1", versionCode: Long = 105): UpdateClient.Candidate {
        routes["/releases"] = { json("[${releaseJson(version)}]") }
        val r = runBlocking { client().check("com.mytvb.btr", 104, 34) }
        return (r as UpdateClient.CheckResult.Available).candidate
    }

    @Test
    fun downloadVerifiesSizeAndHash() {
        publish("1.0.1", 105)
        val c = candidate()
        val target = tmp.newFile("a.apk")
        val events = ArrayList<UpdateClient.Progress>()
        runBlocking { client().download(c, target) { events += it } }
        assertArrayEquals(apkBytes, target.readBytes())
        assertEquals(UpdateClient.Progress.Connecting, events.first())
        assertEquals(100, (events.last() as UpdateClient.Progress.Downloading).percent)
    }

    @Test
    fun hashMismatchDeletesFile() {
        publish("1.0.1", 105, sha = "0".repeat(64))
        val c = candidate()
        val target = tmp.newFile("a.apk")
        val e = expectFailure(UpdateException.Reason.REJECTED) { client().download(c, target) {} }
        assertEquals(UpdatePolicy.Rejection.HASH_MISMATCH, e.rejection)
        assertFalse(target.exists())
    }

    @Test
    fun truncatedOrOversizedDownloadIsRejected() {
        for (body in listOf(apkBytes.copyOf(apkBytes.size - 1), apkBytes + byteArrayOf(1))) {
            publish("1.0.1", 105, apk = body)
            val c = candidate()
            val target = tmp.root.resolve("b.apk")
            val e = expectFailure(UpdateException.Reason.REJECTED) { client().download(c, target) {} }
            assertEquals(UpdatePolicy.Rejection.SIZE_MISMATCH, e.rejection)
            assertFalse(target.exists())
        }
    }

    @Test
    fun localWriteFailureIsStorageNotNetwork() {
        publish("1.0.1", 105)
        val c = candidate()
        var apkRequests = 0
        routes["/dl/v1.0.1/apk"] = { apkRequests++; MockResponse().setBody(Buffer().write(apkBytes)) }
        val target = tmp.newFolder("is-a-directory") // FileOutputStream 會失敗
        expectFailure(UpdateException.Reason.STORAGE) { client().download(c, target) {} }
        assertEquals(1, apkRequests) // 寫入失敗不重試
    }

    @Test
    fun cancellationAbortsBlockedDownloadQuickly() {
        publish("1.0.1", 105)
        val c = candidate()
        routes["/dl/v1.0.1/apk"] = {
            MockResponse().setBody(Buffer().write(apkBytes)).throttleBody(1024, 1, TimeUnit.SECONDS)
        }
        val target = tmp.root.resolve("c.apk")
        val started = System.nanoTime()
        try {
            runBlocking { withTimeout(500) { client().download(c, target) {} } }
            fail("expected timeout")
        } catch (_: TimeoutCancellationException) {
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs < 3_000)
        assertFalse(target.exists())
    }
}
