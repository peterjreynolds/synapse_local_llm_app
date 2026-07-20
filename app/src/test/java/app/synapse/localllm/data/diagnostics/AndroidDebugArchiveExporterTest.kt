package app.synapse.localllm.data.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.BuildConfig
import app.synapse.localllm.domain.diagnostics.DebugUiSnapshot
import app.synapse.localllm.domain.ids.ReceiptId
import app.synapse.localllm.domain.runtime.RuntimeStartReceipt
import app.synapse.localllm.domain.runtime.RuntimeStartStatus
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.settings.SynapseSettings
import app.synapse.localllm.domain.storage.StorageHealthSnapshot
import app.synapse.localllm.domain.storage.StorageHealthState
import app.synapse.localllm.domain.time.SynapseClock
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile
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
class AndroidDebugArchiveExporterTest {
    private lateinit var context: Context
    private lateinit var exporter: AndroidDebugArchiveExporter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearTestState()
        exporter = AndroidDebugArchiveExporter(
            context = context,
            clock = FixedSynapseClock,
        )
    }

    @After
    fun tearDown() {
        clearTestState()
    }

    @Test
    fun exportDebugArchiveRedactsContentBearingStateAndRawFiles() {
        val databaseCanary = "database-message-canary"
        val dataStoreCanary = "datastore-password-canary"
        val promptCanary = "prompt-secret-canary"
        val endpointCanary = "endpoint-secret-canary.example"
        val runtimeCanary = "runtime-failure-canary"
        val storageCanary = "storage-reason-canary"
        val roomCanary = "room-title-canary"
        val noticeCanary = "notice-content-canary"
        val modelCanary = "model-path-canary"

        context.getDatabasePath(DATABASE_NAME).apply {
            parentFile?.mkdirs()
            writeText(databaseCanary)
        }
        File(context.filesDir, "datastore/settings.preferences_pb").apply {
            parentFile?.mkdirs()
            writeText(dataStoreCanary)
        }
        val modelFile = File(context.filesDir, "$modelCanary.gguf").apply {
            writeText("model-weight-canary")
        }

        val receipt = exporter.exportDebugArchive(
            settings = SynapseSettings(
                baseUrl = "https://$endpointCanary/private",
                modelName = "model-name-canary",
                embeddedModelPath = modelFile.path,
                embeddedModelDisplayName = "model-display-canary",
                embeddedModelByteCount = modelFile.length(),
                persona = promptCanary,
                customInstructions = "instruction-secret-canary",
                systemPrompt = "system-$promptCanary",
                smsAutoReplyInstructions = "sms-instruction-canary",
                chatSoundsEnabled = false,
                chatHapticsEnabled = false,
                reducedMotionEnabled = true,
            ),
            runtimeStatus = RuntimeStatus.Unreachable(
                baseUrl = "https://runtime-host-canary.example",
                checkedAt = NOW,
                reason = runtimeCanary,
            ),
            storageHealthSnapshot = StorageHealthSnapshot(
                state = StorageHealthState.WARNING,
                checkedAt = NOW,
                availableBytes = 100L,
                memoryDatabaseBytes = 200L,
                attachmentCacheBytes = 300L,
                reason = storageCanary,
            ),
            uiSnapshot = DebugUiSnapshot(
                activePanel = "CHAT",
                isThreadDrawerOpen = true,
                currentThreadId = "room-id-canary",
                currentThreadTitle = roomCanary,
                visibleThreadCount = 2,
                visibleMessageCount = 3,
                composerCharacterCount = 4,
                pendingAttachmentCount = 1,
                isSending = true,
                isImportingModel = false,
                lastNotice = noticeCanary,
            ),
        )

        val archiveFile = File(context.cacheDir, "diagnostics/${receipt.displayName}")
        val archiveTextByPath = ZipFile(archiveFile).use { archive ->
            archive.entries().asSequence().associate { entry ->
                entry.name to archive.getInputStream(entry).bufferedReader().use { it.readText() }
            }
        }
        val archiveText = archiveTextByPath.values.joinToString("\n")
        val privateCanaries = listOf(
            databaseCanary,
            dataStoreCanary,
            promptCanary,
            endpointCanary,
            runtimeCanary,
            storageCanary,
            roomCanary,
            noticeCanary,
            modelCanary,
            context.dataDir.path,
            context.filesDir.path,
            context.cacheDir.path,
        )

        assertEquals(EXPECTED_ENTRY_PATHS, archiveTextByPath.keys)
        privateCanaries.forEach { canary -> assertFalse("Archive leaked $canary", archiveText.contains(canary)) }
        assertTrue(archiveText.contains("endpointClass=REMOTE_NETWORK"))
        assertTrue(archiveText.contains("runtimeStatus=UNREACHABLE"))
        assertTrue(archiveText.contains("storageHealthState=WARNING"))
        assertTrue(archiveText.contains("chatSoundsEnabled=false"))
        assertTrue(archiveText.contains("chatHapticsEnabled=false"))
        assertTrue(archiveText.contains("reducedMotionEnabled=true"))
        assertTrue(archiveText.contains("rawAppStateIncluded=false"))
        assertTrue(archiveText.contains("databaseSummaryAvailable=false"))
        assertEquals("content", receipt.uri.scheme)
        assertEquals("${BuildConfig.APPLICATION_ID}.fileprovider", receipt.uri.authority)

        assertStartingReceiptIsRedacted()
    }

    private fun assertStartingReceiptIsRedacted() {
        val receiptMessageCanary = "start-receipt-message-canary"
        val receipt = exporter.exportDebugArchive(
            settings = SynapseSettings(),
            runtimeStatus = RuntimeStatus.Starting(
                RuntimeStartReceipt(
                    id = ReceiptId("receipt-id-canary"),
                    status = RuntimeStartStatus.SENT_TO_TERMUX,
                    requestedAt = NOW,
                    message = receiptMessageCanary,
                ),
            ),
            storageHealthSnapshot = null,
            uiSnapshot = emptyUiSnapshot(),
        )

        val archiveFile = File(context.cacheDir, "diagnostics/${receipt.displayName}")
        val runtimeText = ZipFile(archiveFile).use { archive ->
            archive.getInputStream(archive.getEntry("metadata/runtime.txt")).bufferedReader().use { it.readText() }
        }

        assertTrue(runtimeText.contains("runtimeStatus=STARTING_SENT_TO_TERMUX"))
        assertFalse(runtimeText.contains(receiptMessageCanary))
        assertFalse(runtimeText.contains("receipt-id-canary"))
    }

    private fun emptyUiSnapshot(): DebugUiSnapshot =
        DebugUiSnapshot(
            activePanel = "CHAT",
            isThreadDrawerOpen = false,
            currentThreadId = null,
            currentThreadTitle = null,
            visibleThreadCount = 0,
            visibleMessageCount = 0,
            composerCharacterCount = 0,
            pendingAttachmentCount = 0,
            isSending = false,
            isImportingModel = false,
            lastNotice = null,
        )

    private fun clearTestState() {
        File(context.cacheDir, "diagnostics").deleteRecursively()
        File(context.filesDir, "datastore").deleteRecursively()
        File(context.filesDir, "model-path-canary.gguf").delete()
        listOf(
            context.getDatabasePath(DATABASE_NAME),
            context.getDatabasePath("$DATABASE_NAME-wal"),
            context.getDatabasePath("$DATABASE_NAME-shm"),
        ).forEach(File::delete)
    }

    private object FixedSynapseClock : SynapseClock {
        override fun now(): Instant = NOW
    }

    private companion object {
        const val DATABASE_NAME = "synapse.db"
        val NOW: Instant = Instant.parse("2026-07-14T15:00:00Z")
        val EXPECTED_ENTRY_PATHS = setOf(
            "README.txt",
            "metadata/runtime.txt",
            "metadata/device.txt",
            "metadata/window.txt",
            "metadata/ui-state.txt",
            "metadata/model.txt",
            "metadata/database-summary.txt",
            "metadata/app-state-summary.txt",
            "metadata/archive-manifest.txt",
        )
    }
}
