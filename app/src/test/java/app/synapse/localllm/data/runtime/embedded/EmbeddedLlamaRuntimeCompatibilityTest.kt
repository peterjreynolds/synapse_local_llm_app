package app.synapse.localllm.data.runtime.embedded

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.runtime.ChatCompletionRequest
import app.synapse.localllm.domain.runtime.ChatStreamEvent
import app.synapse.localllm.domain.runtime.EmbeddedInferenceAvailability
import app.synapse.localllm.domain.runtime.ModelChatMessage
import app.synapse.localllm.domain.runtime.ModelPromptProfile
import app.synapse.localllm.domain.runtime.RuntimeStartStatus
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.settings.InferenceRuntimeBackend
import app.synapse.localllm.domain.settings.SynapseSettings
import app.synapse.localllm.domain.time.SystemSynapseClock
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EmbeddedLlamaRuntimeCompatibilityTest {
    private val unavailableReason =
        "Embedded llama.cpp requires an arm64-v8a Android process. Compatibility features remain available."

    @Test
    fun unavailableAbiFailsClosedWithoutLoadingNativeEngine() = runTest {
        val runtime = runtime()

        val status = runtime.checkStatus(SynapseSettings())
        val startReceipt = runtime.start(SynapseSettings())
        val events = runtime.streamChatCompletion(chatRequest()).toList()
        runtime.cancelGeneration()

        require(status is RuntimeStatus.Unreachable)
        assertEquals(unavailableReason, status.reason)
        assertEquals(RuntimeStartStatus.EMBEDDED_RUNTIME_UNAVAILABLE, startReceipt.status)
        assertEquals(unavailableReason, startReceipt.message)
        assertEquals(listOf(ChatStreamEvent.Failed(unavailableReason)), events)
    }

    private fun runtime(): EmbeddedLlamaRuntime =
        EmbeddedLlamaRuntime(
            context = ApplicationProvider.getApplicationContext<Context>(),
            idFactory = SynapseIdFactory(),
            clock = SystemSynapseClock(),
            availability = EmbeddedInferenceAvailability.Unavailable(unavailableReason),
        )

    private fun chatRequest(): ChatCompletionRequest =
        ChatCompletionRequest(
            backend = InferenceRuntimeBackend.EMBEDDED_LLAMA,
            baseUrl = "embedded://llama.cpp",
            model = "compatibility-test",
            embeddedModelPath = null,
            modelPromptProfile = ModelPromptProfile.AUTO,
            messages = listOf(ModelChatMessage(ConversationRole.USER, "hello")),
            temperature = 0.7,
            maxTokens = 32,
        )
}
