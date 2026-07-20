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
                      "body": "Version code: 2401\nMinimum Android API: 28\nAPK ABIs: arm64-v8a, armeabi-v7a, x86_64.\nSHA-256: $sha256",
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
        assertEquals(28, result.update.minimumAndroidApi)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a", "x86_64"), result.update.supportedAbis)
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

    @Test
    fun returnsUnavailableWhenNewerReleaseRequiresNewerAndroid() = runTest {
        enqueueRelease(
            body = "Version code: 2401\nMinimum Android API: 29\nAPK ABIs: armeabi-v7a.",
        )

        val result = repository(currentVersionCode = 2400, deviceAndroidApiLevel = 28)
            .checkForAppUpdate()

        require(result is AppUpdateCheckResult.Unavailable)
        assertTrue(result.reason.contains("API 29"))
    }

    @Test
    fun returnsUnavailableWhenNewerReleaseDoesNotSupportDeviceAbi() = runTest {
        enqueueRelease(
            body = "Version code: 2401\nMinimum Android API: 28\nAPK ABIs: arm64-v8a.",
        )

        val result = repository(
            currentVersionCode = 2400,
            deviceSupportedAbis = setOf("armeabi-v7a"),
        ).checkForAppUpdate()

        require(result is AppUpdateCheckResult.Unavailable)
        assertTrue(result.reason.contains("armeabi-v7a"))
    }

    private fun enqueueRelease(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "name": "Synapse AI",
                      "body": ${jsonString(body)},
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
    }

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }

    private fun repository(
        currentVersionCode: Int,
        deviceAndroidApiLevel: Int = 28,
        deviceSupportedAbis: Set<String> = setOf("armeabi-v7a"),
    ): GitHubReleaseAppUpdateRepository =
        GitHubReleaseAppUpdateRepository(
            httpClient = OkHttpClient(),
            currentVersionCode = currentVersionCode,
            deviceAndroidApiLevel = deviceAndroidApiLevel,
            deviceSupportedAbis = deviceSupportedAbis,
            releaseApiUrl = server.url("/repos/synapse/releases/tags/synapse-ai").toString(),
        )
}
