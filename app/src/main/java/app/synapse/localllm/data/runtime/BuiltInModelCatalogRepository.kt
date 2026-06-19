package app.synapse.localllm.data.runtime

import app.synapse.localllm.domain.runtime.ModelCatalogEntry
import app.synapse.localllm.domain.runtime.ModelCatalogRepository
import app.synapse.localllm.domain.runtime.ModelPromptProfile

class BuiltInModelCatalogRepository : ModelCatalogRepository {
    override suspend fun listModelCatalogEntries(): List<ModelCatalogEntry> = builtInModels

    private companion object {
        val builtInModels = listOf(
            ModelCatalogEntry(
                id = "qwen3-5-9b-q4-k-m",
                name = "Qwen3.5 9B Q4_K_M",
                fileName = "Qwen3.5-9B-Q4_K_M.gguf",
                sizeBytes = 5_627_044_256L,
                downloadUrl = "https://huggingface.co/lmstudio-community/Qwen3.5-9B-GGUF/resolve/main/" +
                    "Qwen3.5-9B-Q4_K_M.gguf?download=true",
                sha256 = "cd76ec205963b3b33350093e6904d9de16c4e666fd104e1f632d25c7f15f2a13",
                promptProfile = ModelPromptProfile.QWEN_CHATML,
                compatibilityNotes = "Recommended first model for Samsung S25 Ultra. Downloads only the GGUF file.",
                sourceLabel = "Hugging Face GGUF direct download",
                recommended = true,
            ),
        )
    }
}
