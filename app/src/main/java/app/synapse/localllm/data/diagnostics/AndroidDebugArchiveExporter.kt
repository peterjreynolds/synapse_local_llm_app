package app.synapse.localllm.data.diagnostics

import android.content.Context
import android.content.res.Configuration
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.core.content.FileProvider
import app.synapse.localllm.BuildConfig
import app.synapse.localllm.domain.diagnostics.DebugUiSnapshot
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.settings.SynapseSettings
import app.synapse.localllm.domain.storage.StorageHealthSnapshot
import app.synapse.localllm.domain.time.SynapseClock
import java.io.File
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
        archiveDirectory.mkdirs()
        val archiveFile = File(
            archiveDirectory,
            "synapse-debug-${DateTimeFormatter.ISO_INSTANT.format(createdAt).sanitizeForFilename()}.zip",
        )

        val appStateFiles = listAppStateFiles()
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
            DebugArchiveTextEntry("metadata/app-state-files.txt", buildAppStateFileManifest(appStateFiles)),
        )
        val textEntries = metadataEntries + DebugArchiveTextEntry(
            "metadata/archive-manifest.txt",
            buildArchiveManifest(metadataEntries, appStateFiles),
        )

        ZipOutputStream(archiveFile.outputStream().buffered()).use { archive ->
            textEntries.forEach { metadataEntry ->
                archive.writeTextEntry(metadataEntry.path, metadataEntry.text)
            }
            appStateFiles.forEach { appStateFile ->
                archive.writeFileEntry(appStateFile.entryName, appStateFile.file)
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

    private fun listAppStateFiles(): List<DebugArchiveFile> {
        val databaseFiles = listOf(
            applicationContext.getDatabasePath(DATABASE_NAME),
            applicationContext.getDatabasePath("$DATABASE_NAME-wal"),
            applicationContext.getDatabasePath("$DATABASE_NAME-shm"),
        ).map { file -> DebugArchiveFile("database/${file.name}", file) }

        val settingsFiles = File(applicationContext.filesDir, "datastore")
            .listFiles()
            .orEmpty()
            .filter { file -> file.isFile }
            .map { file -> DebugArchiveFile("datastore/${file.name}", file) }

        return (databaseFiles + settingsFiles)
            .filter { archiveFile -> archiveFile.file.isFile }
    }

    private fun buildReadme(createdAt: Instant): String =
        """
        Synapse AI debug archive

        Created at: $createdAt

        This archive intentionally includes private app state: chats, memory database,
        prompt configuration, settings, runtime metadata, UI state, device/window metrics,
        database summaries, generation timing traces, and app-state file manifests.

        It intentionally excludes the imported GGUF model file.
        """.trimIndent()

    private fun buildRuntimeMetadata(
        settings: SynapseSettings,
        runtimeStatus: RuntimeStatus,
        storageHealthSnapshot: StorageHealthSnapshot?,
    ): String =
        buildString {
            appendLine("runtimeBackend=${settings.runtimeBackend}")
            appendLine("baseUrl=${settings.baseUrl}")
            appendLine("modelName=${settings.modelName}")
            appendLine("modelPromptProfile=${settings.modelPromptProfile}")
            appendLine("temperature=${settings.temperature}")
            appendLine("maxTokens=${settings.maxTokens}")
            appendLine("memoryWritesEnabled=${settings.memoryWritesEnabled}")
            appendLine("speechPlaybackEnabled=${settings.speechPlaybackEnabled}")
            appendLine("runtimeStatus=$runtimeStatus")
            appendLine("storageHealth=$storageHealthSnapshot")
            appendLine()
            appendLine("persona:")
            appendLine(settings.persona)
            appendLine()
            appendLine("customInstructions:")
            appendLine(settings.customInstructions)
            appendLine()
            appendLine("composedSystemPrompt:")
            appendLine(settings.systemPrompt)
        }

    private fun buildDeviceMetadata(): String =
        buildString {
            appendLine("applicationId=${BuildConfig.APPLICATION_ID}")
            appendLine("versionName=${BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${BuildConfig.VERSION_CODE}")
            appendLine("buildType=${BuildConfig.BUILD_TYPE}")
            appendLine("buildGitSha=${BuildConfig.SYNAPSE_BUILD_GIT_SHA}")
            appendLine("apkChannel=${BuildConfig.SYNAPSE_APK_CHANNEL}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("brand=${Build.BRAND}")
            appendLine("product=${Build.PRODUCT}")
            appendLine("hardware=${Build.HARDWARE}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("release=${Build.VERSION.RELEASE}")
            appendLine("supportedAbis=${Build.SUPPORTED_ABIS.joinToString()}")
        }

    private fun buildWindowMetadata(): String {
        val resources = applicationContext.resources
        val configuration = resources.configuration
        val displayMetrics = resources.displayMetrics
        val windowManager = applicationContext.getSystemService(WindowManager::class.java)
        val currentWindowBounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
        val maximumWindowBounds = runCatching { windowManager.maximumWindowMetrics.bounds }.getOrNull()

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
            appendLine("currentWindowBoundsPx=${currentWindowBounds.toDebugBounds()}")
            appendLine("maximumWindowBoundsPx=${maximumWindowBounds.toDebugBounds()}")
            appendLine("activityDecorFitsSystemWindows=false")
            appendLine("activityWindowSoftInputMode=adjustResize")
            appendLine("composeKeyboardPolicy=chat composer and settings list apply imePadding")
        }
    }

    private fun buildUiStateMetadata(uiSnapshot: DebugUiSnapshot): String =
        buildString {
            appendLine("activePanel=${uiSnapshot.activePanel}")
            appendLine("isThreadDrawerOpen=${uiSnapshot.isThreadDrawerOpen}")
            appendLine("currentThreadId=${uiSnapshot.currentThreadId ?: "none"}")
            appendLine("currentThreadTitle=${uiSnapshot.currentThreadTitle ?: "none"}")
            appendLine("visibleThreadCount=${uiSnapshot.visibleThreadCount}")
            appendLine("visibleMessageCount=${uiSnapshot.visibleMessageCount}")
            appendLine("composerCharacterCount=${uiSnapshot.composerCharacterCount}")
            appendLine("pendingAttachmentCount=${uiSnapshot.pendingAttachmentCount}")
            appendLine("isSending=${uiSnapshot.isSending}")
            appendLine("isImportingModel=${uiSnapshot.isImportingModel}")
            appendLine("lastNotice=${uiSnapshot.lastNotice ?: "none"}")
        }

    private fun buildModelMetadata(settings: SynapseSettings): String =
        buildString {
            appendLine("displayName=${settings.embeddedModelDisplayName ?: "none"}")
            appendLine("promptProfile=${settings.modelPromptProfile}")
            appendLine("byteCount=${settings.embeddedModelByteCount ?: "unknown"}")
            appendLine("path=${settings.embeddedModelPath ?: "none"}")
            val modelPath = settings.embeddedModelPath
            if (modelPath != null) {
                val modelFile = File(modelPath)
                appendLine("exists=${modelFile.isFile}")
                appendLine("actualByteCount=${modelFile.takeIf { file -> file.isFile }?.length() ?: "unknown"}")
                appendLine("includedInArchive=false")
            }
        }

    private fun buildDatabaseSummary(): String {
        val databaseFile = applicationContext.getDatabasePath(DATABASE_NAME)
        if (!databaseFile.isFile) {
            return "databasePresent=false\n"
        }

        return runCatching {
            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                buildString {
                    appendLine("databasePresent=true")
                    appendLine("databasePath=${databaseFile.path}")
                    appendLine("databaseBytes=${databaseFile.length()}")
                    appendLine()
                    appendSection("tableCounts", database.queryText(TABLE_COUNTS_SQL))
                    appendSection("activeStreamingMessages", database.queryText(ACTIVE_STREAMING_MESSAGES_SQL))
                    appendSection("recentThreads", database.queryText(RECENT_THREADS_SQL))
                    appendSection("recentMessages", database.queryText(RECENT_MESSAGES_SQL))
                    appendSection("assistantGenerationTraces", database.queryText(GENERATION_TRACES_SQL))
                    appendSection("recentLibraryArtifacts", database.queryText(RECENT_LIBRARY_ARTIFACTS_SQL))
                    appendSection("recentMemoryVersions", database.queryText(RECENT_MEMORY_VERSIONS_SQL))
                    appendSection("recentStorageHealth", database.queryText(RECENT_STORAGE_HEALTH_SQL))
                    appendSection("recentMemoryWriteReceipts", database.queryText(RECENT_MEMORY_WRITES_SQL))
                    appendSection("recentRetrievalReceipts", database.queryText(RECENT_RETRIEVAL_RECEIPTS_SQL))
                    appendSection("recentRetrievedMemoryReceipts", database.queryText(RECENT_RETRIEVED_MEMORY_RECEIPTS_SQL))
                }
            }
        }.getOrElse { exception ->
            "databaseSummaryError=${exception::class.java.name}: ${exception.message}\n"
        }
    }

    private fun buildAppStateFileManifest(appStateFiles: List<DebugArchiveFile>): String =
        buildString {
            appendLine("dataDir=${applicationContext.dataDir.path}")
            appendLine("filesDir=${applicationContext.filesDir.path}")
            appendLine("cacheDir=${applicationContext.cacheDir.path}")
            appendLine()
            appStateFiles.forEach { appStateFile ->
                appendLine(
                    "${appStateFile.entryName} | " +
                        "bytes=${appStateFile.file.length()} | " +
                        "lastModified=${Instant.ofEpochMilli(appStateFile.file.lastModified())}",
                )
            }
        }

    private fun buildArchiveManifest(
        metadataEntries: List<DebugArchiveTextEntry>,
        appStateFiles: List<DebugArchiveFile>,
    ): String =
        buildString {
            appendLine("metadataEntries:")
            metadataEntries.forEach { metadataEntry ->
                appendLine("${metadataEntry.path} | bytes=${metadataEntry.text.toByteArray(Charsets.UTF_8).size}")
            }
            appendLine("metadata/archive-manifest.txt | bytes=this file is generated last")
            appendLine()
            appendLine("appStateFiles:")
            appStateFiles.forEach { appStateFile ->
                appendLine("${appStateFile.entryName} | bytes=${appStateFile.file.length()}")
            }
            appendLine()
            appendLine("excludedFiles:")
            appendLine("GGUF model weights are intentionally excluded.")
        }

    private fun StringBuilder.appendSection(title: String, body: String) {
        appendLine("[$title]")
        append(body.ifBlank { "(none)\n" })
        if (!endsWith("\n")) {
            appendLine()
        }
        appendLine()
    }

    private fun SQLiteDatabase.queryText(sql: String): String =
        runCatching {
            rawQuery(sql, null).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use "(none)\n"
                }
                buildString {
                    do {
                        appendLine(
                            cursor.columnNames.joinToString(separator = " | ") { columnName ->
                                "$columnName=${cursor.readDebugColumn(columnName)}"
                            },
                        )
                    } while (cursor.moveToNext())
                }
            }
        }.getOrElse { exception ->
            "queryError=${exception::class.java.name}: ${exception.message}\n"
        }

    private fun Cursor.readDebugColumn(columnName: String): String {
        val columnIndex = getColumnIndex(columnName)
        if (columnIndex < 0 || isNull(columnIndex)) return "null"
        return getString(columnIndex)
            .replace("\r", " ")
            .replace("\n", " ")
            .take(MAX_DATABASE_PREVIEW_CHARS)
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

    private fun ZipOutputStream.writeFileEntry(path: String, file: File) {
        putNextEntry(ZipEntry(path))
        file.inputStream().buffered().use { input -> input.copyTo(this) }
        closeEntry()
    }

    private fun String.sanitizeForFilename(): String =
        replace(":", "-")

    private data class DebugArchiveTextEntry(
        val path: String,
        val text: String,
    )

    private data class DebugArchiveFile(
        val entryName: String,
        val file: File,
    )

    private companion object {
        const val DATABASE_NAME = "synapse.db"
        const val MAX_DATABASE_PREVIEW_CHARS = 400

        val TABLE_COUNTS_SQL =
            """
            SELECT 'chat_threads' AS tableName, COUNT(*) AS rowCount FROM chat_threads
            UNION ALL SELECT 'chat_messages', COUNT(*) FROM chat_messages
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
            """.trimIndent()

        val ACTIVE_STREAMING_MESSAGES_SQL =
            """
            SELECT id, threadId, role, deliveryState, length(body) AS bodyChars,
                   createdAtEpochMillis, completedAtEpochMillis, failureReason
            FROM chat_messages
            WHERE deliveryState = 'STREAMING'
            ORDER BY createdAtEpochMillis DESC
            LIMIT 40
            """.trimIndent()

        val RECENT_THREADS_SQL =
            """
            SELECT id, title, pinnedAtEpochMillis, archivedAtEpochMillis,
                   titleEditedByUser, createdAtEpochMillis, updatedAtEpochMillis
            FROM chat_threads
            ORDER BY updatedAtEpochMillis DESC
            LIMIT 40
            """.trimIndent()

        val RECENT_MESSAGES_SQL =
            """
            SELECT id, threadId, role, deliveryState, length(body) AS bodyChars,
                   substr(replace(replace(body, char(10), ' '), char(13), ' '), 1, 220) AS bodyPreview,
                   createdAtEpochMillis, completedAtEpochMillis, failureReason
            FROM chat_messages
            ORDER BY createdAtEpochMillis DESC
            LIMIT 80
            """.trimIndent()

        val GENERATION_TRACES_SQL =
            """
            SELECT id, assistantMessageId, backend, modelName,
                   promptMessageCount, promptCharacterCount, retrievedMemoryCount,
                   maxTokens, temperature, startedAtEpochMillis, completedAtEpochMillis,
                   completedAtEpochMillis - startedAtEpochMillis AS durationMillis,
                   firstRawTokenAtEpochMillis - startedAtEpochMillis AS firstRawTokenMillis,
                   firstVisibleTokenAtEpochMillis - startedAtEpochMillis AS firstVisibleTokenMillis,
                   rawTokenEvents, rawCharacterCount, visibleCharacterCount,
                   filteredCharacterCount, stopReason, failureReason
            FROM assistant_generation_traces
            ORDER BY startedAtEpochMillis DESC
            LIMIT 80
            """.trimIndent()

        val RECENT_LIBRARY_ARTIFACTS_SQL =
            """
            SELECT id, title, displayName, relativePath, mimeType, artifactKind,
                   sourceKind, byteCount, sha256, catalogSummary, tagsCsv,
                   createdAtEpochMillis, updatedAtEpochMillis
            FROM library_artifacts
            ORDER BY updatedAtEpochMillis DESC
            LIMIT 40
            """.trimIndent()

        val RECENT_STORAGE_HEALTH_SQL =
            """
            SELECT state, checkedAtEpochMillis, availableBytes, memoryDatabaseBytes,
                   attachmentCacheBytes, reason
            FROM storage_health_snapshots
            ORDER BY checkedAtEpochMillis DESC
            LIMIT 20
            """.trimIndent()

        val RECENT_MEMORY_VERSIONS_SQL =
            """
            SELECT v.id, v.memoryObjectId, o.kind, o.status, o.claimKey, v.scope, v.domain,
                   v.subject, v.predicate, v.valueText, v.writeIntent, v.sensitivity,
                   v.durabilityScore, v.futureUsefulnessScore,
                   substr(replace(replace(v.text, char(10), ' '), char(13), ' '), 1, 220) AS textPreview,
                   substr(replace(replace(v.sourceQuote, char(10), ' '), char(13), ' '), 1, 220) AS sourceQuotePreview,
                   v.confidence, v.surfacePolicy, v.keywordsCsv, v.createdAtEpochMillis
            FROM memory_versions v
            INNER JOIN memory_objects o ON o.id = v.memoryObjectId
            ORDER BY v.createdAtEpochMillis DESC
            LIMIT 80
            """.trimIndent()

        val RECENT_MEMORY_WRITES_SQL =
            """
            SELECT id, outcome, traceEventId, memoryObjectId, memoryVersionId,
                   decidedAtEpochMillis, reason
            FROM memory_write_receipts
            ORDER BY decidedAtEpochMillis DESC
            LIMIT 40
            """.trimIndent()

        val RECENT_RETRIEVAL_RECEIPTS_SQL =
            """
            SELECT id, retrievalIntent,
                   substr(replace(replace(query, char(10), ' '), char(13), ' '), 1, 180) AS queryPreview,
                   length(promptBlock) AS promptBlockChars,
                   retrievedAtEpochMillis
            FROM retrieval_receipts
            ORDER BY retrievedAtEpochMillis DESC
            LIMIT 40
            """.trimIndent()

        val RECENT_RETRIEVED_MEMORY_RECEIPTS_SQL =
            """
            SELECT retrievalReceiptId, memoryObjectId, memoryVersionId, reasonCodes, rankScore
            FROM retrieved_memory_receipts
            LIMIT 80
            """.trimIndent()
    }
}

data class DebugArchiveReceipt(
    val uri: Uri,
    val displayName: String,
    val createdAt: Instant,
)
