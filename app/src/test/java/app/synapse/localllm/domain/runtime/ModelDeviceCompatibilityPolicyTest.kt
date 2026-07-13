package app.synapse.localllm.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDeviceCompatibilityPolicyTest {
    private val policy = ModelDeviceCompatibilityPolicy()

    @Test
    fun fourGibDeviceRecommendsSmallModelAndWarnsWithoutRemovingChoices() {
        val catalog = testCatalog()

        val assessments = policy.assessModelCatalogForDevice(
            catalogEntries = catalog,
            capabilities = capabilities(totalMemoryGib = 4L),
        )

        assertEquals(catalog.map { entry -> entry.id }.toSet(), assessments.keys)
        assertTrue(requireNotNull(assessments["tiny"]).isRecommendedForDevice)
        assertEquals(ModelDeviceFit.CAUTION, requireNotNull(assessments["medium"]).fit)
        assertEquals(ModelDeviceFit.NOT_RECOMMENDED, requireNotNull(assessments["large"]).fit)
        assertTrue(requireNotNull(assessments["large"]).guidance.contains("use Tiny Model instead"))
    }

    @Test
    fun twelveGibDeviceKeepsLargeModelAsDeviceRecommendation() {
        val assessments = policy.assessModelCatalogForDevice(
            catalogEntries = testCatalog(),
            capabilities = capabilities(totalMemoryGib = 12L),
        )

        assertEquals(ModelDeviceFit.SUITABLE, requireNotNull(assessments["large"]).fit)
        assertTrue(requireNotNull(assessments["large"]).isRecommendedForDevice)
    }

    @Test
    fun activeLowMemoryPressureIsVisibleInEveryAssessment() {
        val assessments = policy.assessModelCatalogForDevice(
            catalogEntries = testCatalog(),
            capabilities = capabilities(totalMemoryGib = 6L, isLowMemory = true),
        )

        assertTrue(assessments.values.all { assessment -> assessment.guidance.contains("currently reports low memory") })
    }

    private fun capabilities(
        totalMemoryGib: Long,
        isLowMemory: Boolean = false,
    ): DeviceRuntimeCapabilities =
        DeviceRuntimeCapabilities(
            androidApiLevel = 29,
            totalMemoryBytes = totalMemoryGib * GIB,
            availableMemoryBytes = 2L * GIB,
            appMemoryClassBytes = 512L * MIB,
            isLowMemory = isLowMemory,
            supportedAbis = listOf("arm64-v8a"),
        )

    private fun testCatalog(): List<ModelCatalogEntry> =
        listOf(
            modelEntry(id = "large", name = "Large Model", sizeBytes = 5_627_044_256L),
            modelEntry(id = "medium", name = "Medium Model", sizeBytes = 2_019_377_440L),
            modelEntry(id = "tiny", name = "Tiny Model", sizeBytes = 668_788_096L),
        )

    private fun modelEntry(
        id: String,
        name: String,
        sizeBytes: Long,
    ): ModelCatalogEntry =
        ModelCatalogEntry(
            id = id,
            name = name,
            fileName = "$id.gguf",
            sizeBytes = sizeBytes,
            downloadUrl = "https://example.com/$id.gguf",
            sha256 = "a".repeat(64),
            promptProfile = ModelPromptProfile.LLAMA_INSTRUCT,
            compatibilityNotes = "Test model.",
            sourceLabel = "Test source",
            recommended = false,
        )

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = MIB * 1024L
    }
}
