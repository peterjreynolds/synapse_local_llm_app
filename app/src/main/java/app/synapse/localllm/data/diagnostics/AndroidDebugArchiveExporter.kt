package app.synapse.localllm.data.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import app.synapse.localllm.BuildConfig
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
    ): DebugArchiveReceipt {
        val createdAt = clock.now()
        val archiveDirectory = File(applicationContext.cacheDir, "diagnostics")
        archiveDirectory.mkdirs()
        val archiveFile = File(
            archiveDirectory,
            "synapse-debug-${DateTimeFormatter.ISO_INSTANT.format(createdAt).sanitizeForFilename()}.zip",
        )

        ZipOutputStream(archiveFile.outputStream().buffered()).use { archive ->
            archive.writeTextEntry(
                "README.txt",
                buildReadme(createdAt),
            )
            archive.writeTextEntry(
                "metadata/runtime.txt",
                buildRuntimeMetadata(settings, runtimeStatus, storageHealthSnapshot),
            )
            archive.writeTextEntry(
                "metadata/device.txt",
                buildDeviceMetadata(),
            )
            archive.writeTextEntry(
                "metadata/model.txt",
                buildModelMetadata(settings),
            )
            listAppStateFiles().forEach { appStateFile ->
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
        settings, prompt configuration, and runtime metadata. It intentionally excludes
        the imported GGUF model file.
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
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("release=${Build.VERSION.RELEASE}")
            appendLine("supportedAbis=${Build.SUPPORTED_ABIS.joinToString()}")
        }

    private fun buildModelMetadata(settings: SynapseSettings): String =
        buildString {
            appendLine("displayName=${settings.embeddedModelDisplayName ?: "none"}")
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

    private data class DebugArchiveFile(
        val entryName: String,
        val file: File,
    )

    private companion object {
        const val DATABASE_NAME = "synapse.db"
    }
}

data class DebugArchiveReceipt(
    val uri: Uri,
    val displayName: String,
    val createdAt: Instant,
)
