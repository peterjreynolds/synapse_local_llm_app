package app.synapse.localllm.domain.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class ModelCatalogEntry(
    val id: String,
    val name: String,
    val fileName: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val sha256: String,
    val promptProfile: ModelPromptProfile,
    val compatibilityNotes: String,
    val sourceLabel: String,
    val recommended: Boolean,
)

data class DownloadModelCommand(
    val entry: ModelCatalogEntry,
)

enum class ModelDownloadStage {
    STARTING,
    DOWNLOADING,
    VERIFYING,
    COMPLETED,
    FAILED,
}

enum class ModelDownloadStartStatus {
    STARTED,
    ALREADY_RUNNING,
    FAILED_TO_START,
}

data class ModelDownloadStartReceipt(
    val entryId: String,
    val displayName: String,
    val status: ModelDownloadStartStatus,
    val message: String,
)

sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState

    data class Active(
        val entry: ModelCatalogEntry,
        val stage: ModelDownloadStage,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val powerSaveMode: Boolean,
    ) : ModelDownloadState

    data class Completed(
        val entry: ModelCatalogEntry,
        val receipt: ImportEmbeddedModelReceipt,
    ) : ModelDownloadState

    data class Failed(
        val entry: ModelCatalogEntry,
        val message: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val powerSaveMode: Boolean,
    ) : ModelDownloadState
}

sealed interface ModelDownloadEvent {
    data class Progress(
        val entry: ModelCatalogEntry,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : ModelDownloadEvent

    data class Verifying(
        val entry: ModelCatalogEntry,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : ModelDownloadEvent

    data class Completed(
        val entry: ModelCatalogEntry,
        val receipt: ImportEmbeddedModelReceipt,
    ) : ModelDownloadEvent
}

interface ModelCatalogRepository {
    suspend fun listModelCatalogEntries(): List<ModelCatalogEntry>
}

interface ModelDownloader {
    fun downloadModel(command: DownloadModelCommand): Flow<ModelDownloadEvent>
}

interface ModelDownloadController {
    val modelDownloadState: StateFlow<ModelDownloadState>

    fun startModelDownload(command: DownloadModelCommand): ModelDownloadStartReceipt
}
