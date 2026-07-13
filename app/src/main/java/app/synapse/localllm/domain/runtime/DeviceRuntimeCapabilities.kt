package app.synapse.localllm.domain.runtime

data class DeviceRuntimeCapabilities(
    val androidApiLevel: Int,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val appMemoryClassBytes: Long,
    val isLowMemory: Boolean,
    val supportedAbis: List<String>,
) {
    init {
        require(androidApiLevel > 0) { "Android API level must be positive." }
        require(totalMemoryBytes > 0L) { "Total device memory must be positive." }
        require(availableMemoryBytes >= 0L) { "Available device memory cannot be negative." }
        require(appMemoryClassBytes > 0L) { "App memory class must be positive." }
    }
}

fun interface DeviceRuntimeCapabilitiesReader {
    fun readDeviceRuntimeCapabilities(): DeviceRuntimeCapabilities
}

enum class ModelDeviceFit {
    SUITABLE,
    CAUTION,
    NOT_RECOMMENDED,
}

data class ModelDeviceCompatibilityAssessment(
    val entryId: String,
    val fit: ModelDeviceFit,
    val estimatedWorkingSetBytes: Long,
    val isRecommendedForDevice: Boolean,
    val guidance: String,
)

class ModelDeviceCompatibilityPolicy {
    fun assessModelCatalogForDevice(
        catalogEntries: List<ModelCatalogEntry>,
        capabilities: DeviceRuntimeCapabilities,
    ): Map<String, ModelDeviceCompatibilityAssessment> {
        if (catalogEntries.isEmpty()) return emptyMap()

        val provisionalAssessments = catalogEntries.associateWith { entry ->
            assessModelForDevice(entry, capabilities)
        }
        val recommendedEntry = provisionalAssessments
            .filterValues { assessment -> assessment.fit == ModelDeviceFit.SUITABLE }
            .keys
            .maxByOrNull { entry -> entry.sizeBytes }
            ?: catalogEntries.minBy { entry -> entry.sizeBytes }

        return provisionalAssessments.map { (entry, assessment) ->
            val isRecommendedForDevice = entry.id == recommendedEntry.id
            entry.id to assessment.copy(
                isRecommendedForDevice = isRecommendedForDevice,
                guidance = buildGuidance(
                    fit = assessment.fit,
                    recommendedEntry = recommendedEntry,
                    capabilities = capabilities,
                    isRecommendedForDevice = isRecommendedForDevice,
                ),
            )
        }.toMap()
    }

    private fun assessModelForDevice(
        entry: ModelCatalogEntry,
        capabilities: DeviceRuntimeCapabilities,
    ): ModelDeviceCompatibilityAssessment {
        val estimatedWorkingSetBytes = entry.sizeBytes +
            maxOf(MINIMUM_RUNTIME_OVERHEAD_BYTES, entry.sizeBytes / MODEL_RUNTIME_OVERHEAD_DIVISOR)
        val comfortableBudgetBytes =
            (capabilities.totalMemoryBytes * COMFORTABLE_MEMORY_BUDGET_PERCENT) / 100L
        val maximumAdvisoryBudgetBytes =
            (capabilities.totalMemoryBytes * MAXIMUM_MEMORY_BUDGET_PERCENT) / 100L
        val fit = when {
            estimatedWorkingSetBytes <= comfortableBudgetBytes -> ModelDeviceFit.SUITABLE
            estimatedWorkingSetBytes <= maximumAdvisoryBudgetBytes -> ModelDeviceFit.CAUTION
            else -> ModelDeviceFit.NOT_RECOMMENDED
        }
        return ModelDeviceCompatibilityAssessment(
            entryId = entry.id,
            fit = fit,
            estimatedWorkingSetBytes = estimatedWorkingSetBytes,
            isRecommendedForDevice = false,
            guidance = "",
        )
    }

    private fun buildGuidance(
        fit: ModelDeviceFit,
        recommendedEntry: ModelCatalogEntry,
        capabilities: DeviceRuntimeCapabilities,
        isRecommendedForDevice: Boolean,
    ): String {
        val fitGuidance = when {
            isRecommendedForDevice && fit == ModelDeviceFit.SUITABLE ->
                "Recommended for this device's memory."

            isRecommendedForDevice ->
                "Smallest catalog model; close other apps before local inference."

            fit == ModelDeviceFit.SUITABLE ->
                "Expected to fit this device's memory budget."

            fit == ModelDeviceFit.CAUTION ->
                "May run under memory pressure; ${recommendedEntry.name} is the safer choice."

            else ->
                "Likely too large for reliable local inference; use ${recommendedEntry.name} instead."
        }
        return if (capabilities.isLowMemory) {
            "$fitGuidance Android currently reports low memory."
        } else {
            fitGuidance
        }
    }

    private companion object {
        // Leave room for Android, the UI, KV cache, and allocator peaks; these are guidance bands,
        // not execution blocks, because native runtime demand varies by model architecture and context.
        const val MODEL_RUNTIME_OVERHEAD_DIVISOR = 4L
        const val COMFORTABLE_MEMORY_BUDGET_PERCENT = 55L
        const val MAXIMUM_MEMORY_BUDGET_PERCENT = 75L
        const val MINIMUM_RUNTIME_OVERHEAD_BYTES = 512L * 1024L * 1024L
    }
}
