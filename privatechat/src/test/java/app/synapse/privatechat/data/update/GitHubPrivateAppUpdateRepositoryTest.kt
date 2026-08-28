package app.synapse.privatechat.data.update

import app.synapse.privatechat.domain.update.PrivateAppUpdateCheckOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayInputStream

class GitHubPrivateAppUpdateRepositoryTest {
    @Test
    fun `requests only the exact metadata URL and exposes a newer compatible update`() =
        runTest {
            val source = StubPrivateUpdateTransferSource(validMetadata())
            val repository = repository(source)

            val outcome = repository.checkForNewerCompatibleUpdate()

            assertEquals(listOf(SynapsePrivateUpdateTrust.METADATA_URL), source.metadataUrls)
            val available = outcome as PrivateAppUpdateCheckOutcome.Available
            assertEquals(2038, available.update.versionCode)
        }

    @Test
    fun `does not prompt for an installed or incompatible release`() =
        runTest {
            val installed = repository(StubPrivateUpdateTransferSource(validMetadata(versionCode = 2037)), 2037)
            val newerAndroid =
                repository(
                    StubPrivateUpdateTransferSource(validMetadata(minimumAndroidApi = 29)),
                    deviceAndroidApi = 28,
                )
            val wrongAbi =
                repository(
                    StubPrivateUpdateTransferSource(validMetadata(supportedAbis = "\"arm64-v8a\"")),
                    deviceSupportedAbis = setOf("x86_64"),
                )

            assertSame(PrivateAppUpdateCheckOutcome.NoCompatibleUpdate, installed.checkForNewerCompatibleUpdate())
            assertSame(PrivateAppUpdateCheckOutcome.NoCompatibleUpdate, newerAndroid.checkForNewerCompatibleUpdate())
            assertSame(PrivateAppUpdateCheckOutcome.NoCompatibleUpdate, wrongAbi.checkForNewerCompatibleUpdate())
        }

    @Test
    fun `turns invalid metadata into a closed verification failure`() =
        runTest {
            val source =
                StubPrivateUpdateTransferSource(
                    validMetadata().replace(SynapsePrivateUpdateTrust.SIGNER_SHA256, "0".repeat(64)),
                )

            val outcome = repository(source).checkForNewerCompatibleUpdate()

            val failure = outcome as PrivateAppUpdateCheckOutcome.Failed
            assertEquals("The update information could not be verified.", failure.userMessage)
        }

    private fun repository(
        source: PrivateUpdateTransferSource,
        currentVersionCode: Int = 2037,
        deviceAndroidApi: Int = 35,
        deviceSupportedAbis: Set<String> = setOf("arm64-v8a"),
    ) = GitHubPrivateAppUpdateRepository(
        transferSource = source,
        currentVersionCode = currentVersionCode,
        deviceAndroidApi = deviceAndroidApi,
        deviceSupportedAbis = deviceSupportedAbis,
    )
}

internal class StubPrivateUpdateTransferSource(
    private val metadata: String,
    private val apkBytes: ByteArray = ByteArray(0),
) : PrivateUpdateTransferSource {
    val metadataUrls = mutableListOf<String>()
    val apkUrls = mutableListOf<String>()

    override suspend fun readMetadata(metadataUrl: String): String {
        metadataUrls += metadataUrl
        return metadata
    }

    override fun openApk(apkUrl: String): PrivateUpdateApkResponse {
        apkUrls += apkUrl
        return PrivateUpdateApkResponse(
            contentLength = apkBytes.size.toLong(),
            inputStream = ByteArrayInputStream(apkBytes),
            closeConnection = {},
        )
    }
}
