package app.synapse.localllm.data.runtime

import app.synapse.localllm.domain.runtime.ModelPromptProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInModelCatalogRepositoryTest {
    @Test
    fun catalogEntriesPointAtSingleGgufFilesWithChecksums() = runTest {
        val entries = BuiltInModelCatalogRepository().listModelCatalogEntries()

        assertEquals(4, entries.size)
        entries.forEach { entry ->
            assertTrue(entry.fileName.endsWith(".gguf"))
            assertTrue(entry.downloadUrl.contains("/resolve/main/${entry.fileName}"))
            assertTrue(entry.downloadUrl.endsWith("?download=true"))
            assertTrue(entry.sizeBytes > 0L)
            assertTrue(entry.sha256.matches(Regex("^[a-f0-9]{64}$")))
        }
    }

    @Test
    fun qwenCatalogEntryUsesVerifiedMetadata() = runTest {
        val entries = BuiltInModelCatalogRepository().listModelCatalogEntries()
        val qwen = entries.single { entry -> entry.id == "qwen3-5-9b-q4-k-m" }

        assertEquals("Qwen3.5-9B-Q4_K_M.gguf", qwen.fileName)
        assertTrue(qwen.downloadUrl.contains("/resolve/main/Qwen3.5-9B-Q4_K_M.gguf"))
        assertTrue(qwen.downloadUrl.endsWith("?download=true"))
        assertEquals(5_627_044_256L, qwen.sizeBytes)
        assertEquals("cd76ec205963b3b33350093e6904d9de16c4e666fd104e1f632d25c7f15f2a13", qwen.sha256)
        assertEquals(ModelPromptProfile.QWEN_CHATML, qwen.promptProfile)
        assertTrue(qwen.recommended)
    }

    @Test
    fun phoneFriendlyCatalogEntriesUseLlamaPromptProfile() = runTest {
        val entries = BuiltInModelCatalogRepository().listModelCatalogEntries()

        assertEquals(
            ModelPromptProfile.LLAMA_INSTRUCT,
            entries.single { entry -> entry.id == "llama-3-2-3b-instruct-q4-k-m" }.promptProfile,
        )
        assertEquals(
            ModelPromptProfile.LLAMA_INSTRUCT,
            entries.single { entry -> entry.id == "dolphin-3-llama-3-1-8b-q4-k-m" }.promptProfile,
        )
        assertEquals(
            ModelPromptProfile.LLAMA_INSTRUCT,
            entries.single { entry -> entry.id == "tinyllama-1-1b-chat-q4-k-m" }.promptProfile,
        )
    }
}
