package app.synapse.localllm.data.diagnostics

import android.content.Context
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import app.synapse.localllm.BuildConfig
import app.synapse.localllm.domain.diagnostics.DebugUiSnapshot
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.settings.SynapseSettings
import app.synapse.localllm.domain.storage.StorageHealthSnapshot
import app.synapse.localllm.domain.time.SynapseClock
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AndroidDebugArchiveExporter(
    context: Context,
    private val clock: SynapseClock,
) {
    private val applicationContext = context.applicationContext

    fun exportDebugArchive(
        settings: SynapseSettings,
        runtimeStatus: RuntimeStatus,
        storageHealthSnapshot: StorageHealthSnapshot?,
        uiSnapshot: DebugUiSnapshot,
    ): DebugArchiveReceipt {
        val createdAt = clock.now()
        val archiveDirectory = File(applicationContext.cacheDir, "diagnostics")
        check(archiveDirectory.exists() || archiveDirectory.mkdirs()) {
            "Debug archive directory could not be prepared."
        }
        val archiveFile = File(
            archiveDirectory,
            "synapse-debug-${DateTimeFormatter.ISO_INSTANT.format(createdAt).sanitizeForFilename()}.zip",
        )

        val metadataEntries = listOf(
            DebugArchiveTextEntry("README.txt", buildReadme(createdAt)),
            DebugArchiveTextEntry(
                "metadata/runtime.txt",
                buildRuntimeMetadata(settings, runtimeStatus, storageHealthSnapshot),
            ),
            DebugArchiveTextEntry("metadata/device.txt", buildDeviceMetadata()),
            DebugArchiveTextEntry("metadata/window.txt", buildWindowMetadata()),
            DebugArchiveTextEntry("metadata/ui-state.txt", buildUiStateMetadata(uiSnapshot)),
            DebugArchiveTextEntry("metadata/model.txt", buildModelMetadata(settings)),
            DebugArchiveTextEntry("metadata/database-summary.txt", buildDatabaseSummary()),
            DebugArchiveTextEntry("metadata/app-state-summary.txt", buildAppStateSummary()),
        )
        val textEntries = metadataEntries + DebugArchiveTextEntry(
            "metadata/archive-manifest.txt",
            buildArchiveManifest(metadataEntries),
        )

        ZipOutputStream(archiveFile.outputStream().buffered()).use { archive ->
            textEntries.forEach { metadataEntry ->
                archive.writeTextEntry(metadataEntry.path, metadataEntry.text)
            }
        }

        val archiveUri = FileProvider.getUriForFile(
            applicationContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            archiveFile,
        )
        return DebugArchiveReceipt(
            uri = archiveUri,
            displayName = archiveFile.name,
            createdAt = createdAt,
        )
    }

    private fun buildReadme(createdAt: Instant): String =
        """
        Synapse Chat redacted debug archive

        Created at: $createdAt

        This archive contains bounded operational metadata only. It excludes chat and memory
        content, prompts and custom instructions, phone numbers and SMS content, account data,
        credentials and tokens, absolute filesystem paths, raw Room/DataStore files, and model weights.

        Review this archive before sharing it. Operational metadata can still reveal app usage.
        """.trimIndent()

    private fun buildRuntimeMetadata(
        settings: SynapseSettings,
        runtimeStatus: RuntimeStatus,
        storageHealthSnapshot: StorageHealthSnapshot?,
    ): String =
        buildString {
            appendLine("runtimeBackend=${settings.runtimeBackend}")
            appendLine("endpointClass=${settings.baseUrl.toEndpointClass()}")
            appendLine("modelPromptProfile=${settings.modelPromptProfile}")
            appendLine("temperature=${settings.temperature}")
            appendLine("maxTokens=${settings.maxTokens}")
            appendLine("memoryWritesEnabled=${settings.memoryWritesEnabled}")
            appendLine("speechPlaybackEnabled=${settings.speechPlaybackEnabled}")
            appendLine("smsAutoReplyEnabled=${settings.smsAutoReplyEnabled}")
            appendLine("chatSoundsEnabled=${settings.chatSoundsEnabled}")
            appendLine("chatHapticsEnabled=${settings.chatHapticsEnabled}")
            appendLine("reducedMotionEnabled=${settings.reducedMotionEnabled}")
            appendLine("runtimeStatus=${runtimeStatus.toRedactedStatus()}")
            if (storageHealthSnapshot == null) {
                appendLine("storageHealthAvailable=false")
            } else {
                appendLine("storageHealthAvailable=true")
                appendLine("storageHealthState=${storageHealthSnapshot.state}")
                appendLine("availableBytes=${storageHealthSnapshot.availableBytes}")
                appendLine("memoryDatabaseBytes=${storageHealthSnapshot.memoryDatabaseBytes}")
                appendLine("attachmentCacheBytes=${storageHealthSnapshot.attachmentCacheBytes}")
            }
        }

    private fun buildDeviceMetadata(): String =
        buildString {
            appendLine("applicationId=${BuildConfig.APPLICATION_ID}")
            appendLine("versionName=${BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${BuildConfig.VERSION_CODE}")
            appendLine("buildType=${BuildConfig.BUILD_TYPE}")
            appendLine("buildGitSha=${BuildConfig.SYNAPSE_BUILD_GIT_SHA}")
            appendLine("apkChannel=${BuildConfig.SYNAPSE_APK_CHANNEL}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("supportedAbis=${Build.SUPPORTED_ABIS.joinToString()}")
        }

    private fun buildWindowMetadata(): String {
        val resources = applicationContext.resources
        val configuration = resources.configuration
        val displayMetrics = resources.displayMetrics
        val windowManager = applicationContext.getSystemService(WindowManager::class.java)
        val windowBounds = readWindowBounds(windowManager)

        return buildString {
            appendLine("orientation=${configuration.orientation.toOrientationLabel()}")
            appendLine("screenWidthDp=${configuration.screenWidthDp}")
            appendLine("screenHeightDp=${configuration.screenHeightDp}")
            appendLine("smallestScreenWidthDp=${configuration.smallestScreenWidthDp}")
            appendLine("fontScale=${configuration.fontScale}")
            appendLine("density=${displayMetrics.density}")
            appendLine("densityDpi=${displayMetrics.densityDpi}")
            appendLine("displayWidthPx=${displayMetrics.widthPixels}")
            appendLine("displayHeightPx=${displayMetrics.heightPixels}")
            appendLine("currentWindowBoundsPx=${windowBounds.current.toDebugBounds()}")
            appendLine("maximumWindowBoundsPx=${windowBounds.maximum.toDebugBounds()}")
            appendLine("activityDecorFitsSystemWindows=false")
            appendLine("activityWindowSoftInputMode=adjustResize")
            appendLine("composeKeyboardPolicy=chat composer and settings list apply imePadding")
        }
    }

    private fun readWindowBounds(windowManager: WindowManager): DebugWindowBounds =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            readApi30WindowBounds(windowManager)
        } else {
            val displayMetrics = applicationContext.resources.displayMetrics
            val displayBounds = Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
            DebugWindowBounds(current = displayBounds, maximum = displayBounds)
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readApi30WindowBounds(windowManager: WindowManager): DebugWindowBounds =
        DebugWindowBounds(
            current = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull(),
            maximum = runCatching { windowManager.maximumWindowMetrics.bounds }.getOrNull(),
        )

    private fun buildUiStateMetadata(uiSnapshot: DebugUiSnapshot): String =
        buildString {
            appendLine("activePanel=${uiSnapshot.activePanel}")
            appendLine("isRoomDrawerOpen=${uiSnapshot.isThreadDrawerOpen}")
            appendLine("hasCurrentRoom=${uiSnapshot.currentThreadId != null}")
            appendLine("visibleRoomCount=${uiSnapshot.visibleThreadCount}")
            appendLine("visibleMessageCount=${uiSnapshot.visibleMessageCount}")
            appendLine("composerCharacterCount=${uiSnapshot.composerCharacterCount}")
            appendLine("pendingAttachmentCount=${uiSnapshot.pendingAttachmentCount}")
            appendLine("isSending=${uiSnapshot.isSending}")
            appendLine("isImportingModel=${uiSnapshot.isImportingModel}")
            appendLine("hasNotice=${uiSnapshot.lastNotice != null}")
        }

    private fun buildModelMetadata(settings: SynapseSettings): String {
        val modelFile = settings.embeddedModelPath?.let(::File)
        return buildString {
            appendLine("configured=${settings.embeddedModelPath != null}")
            appendLine("promptProfile=${settings.modelPromptProfile}")
            appendLine("declaredByteCount=${settings.embeddedModelByteCount ?: "unknown"}")
            appendLine("exists=${modelFile?.isFile == true}")
            appendLine("actualByteCount=${modelFile?.takeIf(File::isFile)?.length() ?: "unknown"}")
            appendLine("includedInArchive=false")
        }
    }

    private fun buildDatabaseSummary(): String {
        val databaseFile = applicationContext.getDatabasePath(DATABASE_NAME)
        if (!databaseFile.isFile) {
            return "databasePresent=false\ndatabaseSummaryAvailable=false\n"
        }

        return runCatching {
            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                buildString {
                    appendLine("databasePresent=true")
                    appendLine("databaseSummaryAvailable=true")
                    appendLine("databaseBytes=${databaseFile.length()}")
                    database.rawQuery(TABLE_COUNTS_SQL, null).use { cursor ->
                        while (cursor.moveToNext()) {
                            appendLine("table.${cursor.getString(0)}.rows=${cursor.getLong(1)}")
                        }
                    }
                }
            }
        }.getOrElse {
            "databasePresent=true\ndatabaseSummaryAvailable=false\ndatabaseBytes=${databaseFile.length()}\n"
        }
    }

    private fun buildAppStateSummary(): String {
        val databaseFiles = listOf(
            applicationContext.getDatabasePath(DATABASE_NAME),
            applicationContext.getDatabasePath("$DATABASE_NAME-wal"),
            applicationContext.getDatabasePath("$DATABASE_NAME-shm"),
        ).filter(File::isFile)
        val settingsFiles = File(applicationContext.filesDir, "datastore")
            .listFiles()
            .orEmpty()
            .filter(File::isFile)

        return buildString {
            appendLine("databaseFileCount=${databaseFiles.size}")
            appendLine("databaseTotalBytes=${databaseFiles.sumOf(File::length)}")
            appendLine("dataStoreFileCount=${settingsFiles.size}")
            appendLine("dataStoreTotalBytes=${settingsFiles.sumOf(File::length)}")
            appendLine("rawAppStateIncluded=false")
        }
    }

    private fun buildArchiveManifest(metadataEntries: List<DebugArchiveTextEntry>): String =
        buildString {
            appendLine("metadataEntries:")
            metadataEntries.forEach { metadataEntry ->
                appendLine("${metadataEntry.path} | bytes=${metadataEntry.text.toByteArray(Charsets.UTF_8).size}")
            }
            appendLine("metadata/archive-manifest.txt | bytes=this file is generated last")
            appendLine()
            appendLine("excludedContent:")
            appendLine("raw Room and DataStore files")
            appendLine("chat, memory, prompt, SMS, account, credential, token, and filesystem-path content")
            appendLine("GGUF model weights")
        }

    private fun RuntimeStatus.toRedactedStatus(): String =
        when (this) {
            is RuntimeStatus.Ready -> "READY"
            is RuntimeStatus.Starting -> "STARTING_${receipt.status}"
            is RuntimeStatus.Unreachable -> "UNREACHABLE"
            RuntimeStatus.Unknown -> "UNKNOWN"
        }

    private fun String.toEndpointClass(): EndpointClass {
        val uri = runCatching { URI(trim()) }.getOrNull() ?: return EndpointClass.INVALID
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return EndpointClass.INVALID
        val host = uri.host?.lowercase() ?: return EndpointClass.INVALID
        return when {
            host == "localhost" || host == "::1" || host.startsWith("127.") -> EndpointClass.LOOPBACK
            host.isPrivateNetworkHost() -> EndpointClass.PRIVATE_NETWORK
            else -> EndpointClass.REMOTE_NETWORK
        }
    }

    private fun String.isPrivateNetworkHost(): Boolean {
        if (startsWith("10.") || startsWith("192.168.") || startsWith("169.254.")) return true
        if (startsWith("fc") || startsWith("fd") || startsWith("fe8") || startsWith("fe9") ||
            startsWith("fea") || startsWith("feb")
        ) {
            return true
        }
        val secondOctet = split('.').getOrNull(1)?.toIntOrNull()
        return startsWith("172.") && secondOctet in 16..31
    }

    private fun Int.toOrientationLabel(): String =
        when (this) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "undefined"
        }

    private fun Rect?.toDebugBounds(): String =
        this?.let { bounds -> "${bounds.width()}x${bounds.height()}@${bounds.left},${bounds.top}" }
            ?: "unavailable"

    private fun ZipOutputStream.writeTextEntry(path: String, text: String) {
        putNextEntry(ZipEntry(path))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun String.sanitizeForFilename(): String = replace(":", "-")

    private data class DebugArchiveTextEntry(
        val path: String,
        val text: String,
    )

    private data class DebugWindowBounds(
        val current: Rect?,
        val maximum: Rect?,
    )

    private enum class EndpointClass {
        LOOPBACK,
        PRIVATE_NETWORK,
        REMOTE_NETWORK,
        INVALID,
    }

    private companion object {
        const val DATABASE_NAME = "synapse.db"

        val TABLE_COUNTS_SQL =
            """
            SELECT 'chat_threads' AS tableName, COUNT(*) AS rowCount FROM chat_threads
            UNION ALL SELECT 'chat_messages', COUNT(*) FROM chat_messages
            UNION ALL SELECT 'chat_participants', COUNT(*) FROM chat_participants
            UNION ALL SELECT 'room_memberships', COUNT(*) FROM room_memberships
            UNION ALL SELECT 'chat_message_authors', COUNT(*) FROM chat_message_authors
            UNION ALL SELECT 'assistant_generation_traces', COUNT(*) FROM assistant_generation_traces
            UNION ALL SELECT 'attachments', COUNT(*) FROM attachments
            UNION ALL SELECT 'library_artifacts', COUNT(*) FROM library_artifacts
            UNION ALL SELECT 'library_artifact_write_receipts', COUNT(*) FROM library_artifact_write_receipts
            UNION ALL SELECT 'trace_events', COUNT(*) FROM trace_events
            UNION ALL SELECT 'memory_objects', COUNT(*) FROM memory_objects
            UNION ALL SELECT 'memory_versions', COUNT(*) FROM memory_versions
            UNION ALL SELECT 'memory_supports', COUNT(*) FROM memory_supports
            UNION ALL SELECT 'memory_write_receipts', COUNT(*) FROM memory_write_receipts
            UNION ALL SELECT 'retrieval_receipts', COUNT(*) FROM retrieval_receipts
            UNION ALL SELECT 'retrieved_memory_receipts', COUNT(*) FROM retrieved_memory_receipts
            UNION ALL SELECT 'storage_health_snapshots', COUNT(*) FROM storage_health_snapshots
            UNION ALL SELECT 'sms_sender_threads', COUNT(*) FROM sms_sender_threads
            UNION ALL SELECT 'sms_auto_reply_receipts', COUNT(*) FROM sms_auto_reply_receipts
            """.trimIndent()
    }
}

data class DebugArchiveReceipt(
    val uri: Uri,
    val displayName: String,
    val createdAt: Instant,
)
