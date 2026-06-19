package app.synapse.localllm.data.update

import app.synapse.localllm.domain.update.AppUpdateCheckResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubReleaseAppUpdateRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun returnsAvailableUpdateWhenReleaseVersionIsNewer() = runTest {
        val sha256 = "a".repeat(64)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "name": "Synapse AI",
                      "body": "Version code: 2401\nSHA-256: $sha256",
                      "html_url": "https://github.com/peterjreynolds/synapse_local_llm_app/releases/tag/synapse-ai",
                      "assets": [
                        {
                          "name": "Synapse-AI.apk",
                          "size": 123456,
                          "digest": "sha256:$sha256",
                          "browser_download_url": "https://github.com/peterjreynolds/synapse_local_llm_app/releases/download/synapse-ai/Synapse-AI.apk"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val result = repository(currentVersionCode = 2400).checkForAppUpdate()

        require(result is AppUpdateCheckResult.Available)
        assertEquals(2401, result.update.versionCode)
        assertEquals("Synapse AI", result.update.releaseName)
        assertEquals("Synapse-AI.apk", result.update.apkUrl.substringAfterLast("/"))
        assertEquals(sha256, result.update.apkSha256)
        assertEquals(123456L, result.update.byteCount)
    }

    @Test
    fun returnsUpToDateWhenReleaseVersionIsNotNewer() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "name": "Synapse AI",
                      "body": "Version code: 2400",
                      "html_url": "https://github.com/peterjreynolds/synapse_local_llm_app/releases/tag/synapse-ai",
                      "assets": [
                        {
                          "name": "Synapse-AI.apk",
                          "size": 123456,
                          "browser_download_url": "https://github.com/peterjreynolds/synapse_local_llm_app/releases/download/synapse-ai/Synapse-AI.apk"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val result = repository(currentVersionCode = 2400).checkForAppUpdate()

        assertEquals(AppUpdateCheckResult.UpToDate, result)
    }

    @Test
    fun returnsUnavailableWhenReleaseCannotBeRead() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        val result = repository(currentVersionCode = 2400).checkForAppUpdate()

        require(result is AppUpdateCheckResult.Unavailable)
        assertTrue(result.reason.contains("HTTP 404"))
    }

    private fun repository(currentVersionCode: Int): GitHubReleaseAppUpdateRepository =
        GitHubReleaseAppUpdateRepository(
            httpClient = OkHttpClient(),
            currentVersionCode = currentVersionCode,
            releaseApiUrl = server.url("/repos/synapse/releases/tags/synapse-ai").toString(),
        )
}
