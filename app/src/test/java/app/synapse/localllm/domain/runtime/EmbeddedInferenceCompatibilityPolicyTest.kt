package app.synapse.localllm.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedInferenceCompatibilityPolicyTest {
    private val policy = EmbeddedInferenceCompatibilityPolicy()

    @Test
    fun arm64DeviceKeepsEmbeddedInferenceAvailable() {
        assertEquals(
            EmbeddedInferenceAvailability.Available,
            policy.assessSupportedAbis(listOf("arm64-v8a", "armeabi-v7a")),
        )
    }

    @Test
    fun android9CompatibilityAbiGatesOnlyEmbeddedInference() {
        val availability = policy.assessSupportedAbis(listOf("armeabi-v7a"))

        require(availability is EmbeddedInferenceAvailability.Unavailable)
        assertTrue(availability.reason.contains("arm64-v8a"))
        assertTrue(availability.reason.contains("Cinder"))
        assertTrue(availability.reason.contains("calling"))
    }
}
