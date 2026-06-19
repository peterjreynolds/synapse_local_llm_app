package app.synapse.localllm.domain.runtime

import kotlinx.coroutines.flow.Flow

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
