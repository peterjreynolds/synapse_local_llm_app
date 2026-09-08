package app.synapse.privatechat.data.update

import app.synapse.privatechat.domain.update.PrivateAppUpdateDownloadEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AndroidPrivateAppUpdateDownloaderTest {
    private lateinit var updateDirectory: File

    @Before
    fun createUpdateDirectory() {
        updateDirectory = Files.createTempDirectory("synapse-private-updates").toFile()
    }

    @After
    fun deleteUpdateDirectory() {
        updateDirectory.deleteRecursively()
    }

    @Test
    fun `downloads verifies finalizes and exposes only the verified APK`() =
        runTest {
            val apkBytes = ByteArray(8_192) { index -> (index % 251).toByte() }
            val update = testUpdate(apkByteCount = apkBytes.size.toLong(), apkSha256 = apkBytes.sha256Hex())
            val source = StubPrivateUpdateTransferSource(validMetadata(), apkBytes)
            val downloader = downloader(source)

            val events = downloader.downloadAndVerifyUpdate(update).toList()

            assertTrue(events.first() is PrivateAppUpdateDownloadEvent.Progress)
            assertTrue(events.any { event -> event is PrivateAppUpdateDownloadEvent.Verifying })
            val completed = events.last() as PrivateAppUpdateDownloadEvent.Completed
            assertEquals(listOf(SynapsePrivateUpdateTrust.APK_URL), source.apkUrls)
            assertEquals("Synapse-Private-2038.apk", completed.receipt.displayName)
            assertEquals(apkBytes.size.toLong(), completed.receipt.byteCount)
            assertEquals(apkBytes.sha256Hex(), completed.receipt.sha256)
            assertEquals(
                "content://app.synapse.privatechat.updateprovider/verified_app_updates/" +
                    "Synapse-Private-2038.apk",
                completed.receipt.installerUri,
            )
            assertTrue(updateDirectory.resolve(completed.receipt.displayName).isFile)
            assertFalse(updateDirectory.resolve("Synapse-Private-2038.download").exists())
        }

    @Test
    fun `deletes partial download when checksum verification fails`() =
        runTest {
            val apkBytes = ByteArray(8_192) { index -> index.toByte() }
            val update = testUpdate(apkByteCount = apkBytes.size.toLong(), apkSha256 = "0".repeat(64))
            val downloader = downloader(StubPrivateUpdateTransferSource(validMetadata(), apkBytes))

            val failure = runCatching { downloader.downloadAndVerifyUpdate(update).toList() }.exceptionOrNull()

            assertTrue(failure?.message?.contains("checksum") == true)
            assertTrue(updateDirectory.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `stops before writing beyond the metadata byte count`() =
        runTest {
            val apkBytes = ByteArray(8_193)
            val update = testUpdate(apkByteCount = 8_192L, apkSha256 = apkBytes.sha256Hex())
            val source =
                object : PrivateUpdateTransferSource {
                    override suspend fun readMetadata(metadataUrl: String): String = validMetadata()

                    override fun openApk(apkUrl: String): PrivateUpdateApkResponse =
                        PrivateUpdateApkResponse(
                            contentLength = null,
                            inputStream = apkBytes.inputStream(),
                            closeConnection = {},
                        )
                }

            val failure = runCatching { downloader(source).downloadAndVerifyUpdate(update).toList() }.exceptionOrNull()

            assertTrue(failure?.message?.contains("verified size") == true)
            assertTrue(updateDirectory.listFiles().orEmpty().isEmpty())
        }

    private fun downloader(source: PrivateUpdateTransferSource): AndroidPrivateAppUpdateDownloader =
        AndroidPrivateAppUpdateDownloader(
            updateDirectory = updateDirectory,
            transferSource = source,
            apkInspector = PrivateApkInspector { testInspection() },
            installerUriFactory = { file ->
                "content://app.synapse.privatechat.updateprovider/verified_app_updates/${file.name}"
            },
            installedVersionCode = 2037,
            deviceAndroidApi = 35,
            deviceSupportedAbis = setOf("arm64-v8a"),
        )
}
