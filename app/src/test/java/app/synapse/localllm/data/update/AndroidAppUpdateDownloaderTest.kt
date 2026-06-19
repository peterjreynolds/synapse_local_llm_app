package app.synapse.localllm.data.update

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.domain.update.AppUpdateDownloadEvent
import app.synapse.localllm.domain.update.AvailableAppUpdate
import app.synapse.localllm.domain.update.DownloadAppUpdateCommand
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAppUpdateDownloaderTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        updateCacheDirectory().deleteRecursively()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        updateCacheDirectory().deleteRecursively()
    }

    @Test
    fun downloadsVerifiesAndExposesSynapseApkUri() = runTest {
        val apkBytes = ByteArray(8192) { index -> (index % 251).toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", apkBytes.size)
                .setBody(Buffer().write(apkBytes)),
        )
        val update = testUpdate(
            apkUrl = server.url("/Synapse-AI.apk").toString(),
            byteCount = apkBytes.size.toLong(),
            sha256 = sha256Hex(apkBytes),
        )

        val events = downloader().downloadAppUpdate(DownloadAppUpdateCommand(update)).toList()

        assertTrue(events.first() is AppUpdateDownloadEvent.Progress)
        val completed = events.last() as AppUpdateDownloadEvent.Completed
        assertEquals(update, completed.update)
        assertEquals("Synapse-AI-2401.apk", completed.receipt.displayName)
        assertEquals(apkBytes.size.toLong(), completed.receipt.byteCount)
        assertEquals(sha256Hex(apkBytes), completed.receipt.sha256)
        assertTrue(completed.receipt.uri.startsWith("content://"))
        val storedApk = File(updateCacheDirectory(), completed.receipt.displayName)
        assertTrue(storedApk.isFile)
        assertEquals(apkBytes.size.toLong(), storedApk.length())
    }

    @Test
    fun rejectsDownloadedApkWhenChecksumDoesNotMatch() = runTest {
        val apkBytes = ByteArray(4096) { index -> index.toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", apkBytes.size)
                .setBody(Buffer().write(apkBytes)),
        )
        val update = testUpdate(
            apkUrl = server.url("/Synapse-AI.apk").toString(),
            byteCount = apkBytes.size.toLong(),
            sha256 = "0".repeat(64),
        )

        val failure = runCatching {
            downloader().downloadAppUpdate(DownloadAppUpdateCommand(update)).toList()
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("checksum mismatch") == true)
        assertFalse(File(updateCacheDirectory(), "Synapse-AI-2401.apk").exists())
    }

    private fun downloader(): AndroidAppUpdateDownloader =
        AndroidAppUpdateDownloader(
            context = context,
            httpClient = OkHttpClient(),
            trustedApkUrlPrefix = server.url("/").toString(),
            updateUriFactory = { file -> Uri.parse("content://app.synapse.localllm.test/${file.name}") },
        )

    private fun testUpdate(
        apkUrl: String,
        byteCount: Long,
        sha256: String,
    ): AvailableAppUpdate =
        AvailableAppUpdate(
            versionCode = 2401,
            releaseName = "Synapse AI",
            releaseUrl = "https://github.com/peterjreynolds/synapse_local_llm_app/releases/tag/synapse-ai",
            apkUrl = apkUrl,
            apkSha256 = sha256,
            byteCount = byteCount,
        )

    private fun updateCacheDirectory(): File =
        File(context.cacheDir, "app-updates")

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
