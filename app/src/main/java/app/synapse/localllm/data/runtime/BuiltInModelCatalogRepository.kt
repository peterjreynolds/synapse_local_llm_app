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
            ModelCatalogEntry(
                id = "llama-3-2-3b-instruct-q4-k-m",
                name = "Llama 3.2 3B Instruct Q4_K_M",
                fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                sizeBytes = 2_019_377_440L,
                downloadUrl = "https://huggingface.co/lmstudio-community/Llama-3.2-3B-Instruct-GGUF/resolve/main/" +
                    "Llama-3.2-3B-Instruct-Q4_K_M.gguf?download=true",
                sha256 = "e4f1a04d927b09ec18eb2f233d85ecd760fc2d35cec97e37f8604d3632210d9a",
                promptProfile = ModelPromptProfile.LLAMA_INSTRUCT,
                compatibilityNotes = "Small phone-friendly Llama option. Faster and lighter than 8B/9B models.",
                sourceLabel = "Hugging Face GGUF direct download",
                recommended = false,
            ),
            ModelCatalogEntry(
                id = "dolphin-3-llama-3-1-8b-q4-k-m",
                name = "Dolphin 3.0 Llama 3.1 8B Q4_K_M",
                fileName = "Dolphin3.0-Llama3.1-8B-Q4_K_M.gguf",
                sizeBytes = 4_920_745_856L,
                downloadUrl = "https://huggingface.co/dphn/Dolphin3.0-Llama3.1-8B-GGUF/resolve/main/" +
                    "Dolphin3.0-Llama3.1-8B-Q4_K_M.gguf?download=true",
                sha256 = "4c001f29d4edbd9327a6df8e16e2b7907a64e0e31b0a7abe9c779a96773f304e",
                promptProfile = ModelPromptProfile.LLAMA_INSTRUCT,
                compatibilityNotes = "Heavier uncensored-style Dolphin/Llama model. Expect more heat and latency on phone.",
                sourceLabel = "Hugging Face GGUF direct download",
                recommended = false,
            ),
            ModelCatalogEntry(
                id = "tinyllama-1-1b-chat-q4-k-m",
                name = "TinyLlama 1.1B Chat Q4_K_M",
                fileName = "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
                sizeBytes = 668_788_096L,
                downloadUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/" +
                    "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf?download=true",
                sha256 = "9fecc3b3cd76bba89d504f29b616eedf7da85b96540e490ca5824d3f7d2776a0",
                promptProfile = ModelPromptProfile.LLAMA_INSTRUCT,
                compatibilityNotes = "Tiny fallback for quick tests. Much faster, but much weaker.",
                sourceLabel = "Hugging Face GGUF direct download",
                recommended = false,
            ),
        )
    }
}
