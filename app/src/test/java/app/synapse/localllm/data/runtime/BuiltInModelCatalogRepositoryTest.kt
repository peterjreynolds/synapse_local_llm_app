package app.synapse.localllm.data.runtime

import app.synapse.localllm.domain.runtime.ModelPromptProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInModelCatalogRepositoryTest {
    @Test
    fun qwenCatalogEntryPointsAtSingleGgufWithChecksum() = runTest {
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
}
