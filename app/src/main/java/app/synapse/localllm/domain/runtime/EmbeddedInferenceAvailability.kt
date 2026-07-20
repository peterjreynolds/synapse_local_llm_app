package app.synapse.localllm.domain.runtime

sealed interface EmbeddedInferenceAvailability {
    data object Available : EmbeddedInferenceAvailability

    data class Unavailable(
        val reason: String,
    ) : EmbeddedInferenceAvailability
}

class EmbeddedInferenceCompatibilityPolicy {
    fun assessSupportedAbis(supportedAbis: List<String>): EmbeddedInferenceAvailability =
        if (supportedAbis.any { abi -> abi == EMBEDDED_INFERENCE_ABI }) {
            EmbeddedInferenceAvailability.Available
        } else {
            EmbeddedInferenceAvailability.Unavailable(
                reason =
                    "Embedded llama.cpp requires an arm64-v8a Android process. " +
                        "Synapse Chat, Cinder, notifications, calling, and the server runtime remain available.",
            )
        }

    private companion object {
        const val EMBEDDED_INFERENCE_ABI = "arm64-v8a"
    }
}
