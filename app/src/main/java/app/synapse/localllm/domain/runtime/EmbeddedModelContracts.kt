package app.synapse.localllm.domain.runtime

data class ImportEmbeddedModelReceipt(
    val modelPath: String,
    val displayName: String,
    val byteCount: Long?,
)

interface EmbeddedModelStore {
    suspend fun importModel(command: ImportEmbeddedModelCommand): ImportEmbeddedModelReceipt
}

data class ImportEmbeddedModelCommand(
    val sourceUri: String,
    val displayName: String,
    val byteCount: Long?,
)
