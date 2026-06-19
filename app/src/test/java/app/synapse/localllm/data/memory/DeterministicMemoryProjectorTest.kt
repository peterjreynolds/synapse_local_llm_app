package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.TraceEventId
import app.synapse.localllm.domain.memory.MemoryClaimDomain
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.SurfacePolicy
import app.synapse.localllm.domain.memory.TraceEventRecord
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicMemoryProjectorTest {
    private val projector = DeterministicMemoryProjector()

    @Test
    fun extractsExplicitPreferenceWithSourceEvidence() {
        val traceEvent = userTrace("I prefer concise Kotlin code when we are building Android apps.")

        val candidates = projector.extractMemoryCandidates(traceEvent)

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals(MemoryKind.PREFERENCE, candidate.kind)
        assertEquals(SurfacePolicy.PROMPT_VISIBLE, candidate.surfacePolicy)
        assertEquals(listOf(traceEvent.id), candidate.sourceTraceEventIds)
        assertTrue(candidate.reasonCodes.contains("explicit-user-preference"))
    }

    @Test
    fun extractsFavoriteFoodAsPreferenceMemory() {
        val traceEvent = userTrace("My favorite food is pizza.")

        val candidate = projector.extractMemoryCandidates(traceEvent).single()

        assertEquals(MemoryKind.PREFERENCE, candidate.kind)
        assertEquals("User's favorite food is pizza.", candidate.text)
        assertEquals(MemoryClaimDomain.PREFERENCE, candidate.domain)
        assertEquals("food", candidate.subject)
        assertEquals("favorite", candidate.predicate)
        assertEquals("pizza", candidate.value)
        assertEquals("user.preference.food.favorite", candidate.claimKey)
        assertTrue(candidate.reasonCodes.contains("explicit-user-favorite"))
    }

    @Test
    fun extractsFavoriteFoodInsideRememberRequest() {
        val traceEvent = userTrace("Remember that my favorite food is sushi.")

        val candidate = projector.extractMemoryCandidates(traceEvent).single()

        assertEquals("User's favorite food is sushi.", candidate.text)
        assertTrue(candidate.reasonCodes.contains("explicit-user-favorite"))
    }

    @Test
    fun extractsFavoriteFoodInsideDontForgetRequest() {
        val traceEvent = userTrace("Don't forget that my favorite food is tacos.")

        val candidate = projector.extractMemoryCandidates(traceEvent).single()

        assertEquals("User's favorite food is tacos.", candidate.text)
        assertTrue(candidate.reasonCodes.contains("explicit-user-favorite"))
        assertTrue(candidate.reasonCodes.contains("explicit-user-memory-command"))
    }

    @Test
    fun extractsCorrectedFullNameAsIdentityMemory() {
        val traceEvent = userTrace("No, my full name is Peter Joseph Reynolds.")

        val candidate = projector.extractMemoryCandidates(traceEvent).single()

        assertEquals(MemoryKind.IDENTITY, candidate.kind)
        assertEquals("User's full name is Peter Joseph Reynolds.", candidate.text)
        assertEquals(MemoryClaimDomain.IDENTITY, candidate.domain)
        assertEquals("user.identity.self.full_name", candidate.claimKey)
        assertEquals("self", candidate.subject)
        assertEquals("full name", candidate.predicate)
        assertEquals("Peter Joseph Reynolds", candidate.value)
        assertTrue(candidate.reasonCodes.contains("explicit-user-identity"))
        assertTrue(candidate.keywords.contains("identity"))
    }

    @Test
    fun extractsExplicitProjectRuleAsProjectMemory() {
        val traceEvent = userTrace(
            "Remember that all new proposals for Project Walby should be reviewed by Roberto Moreno.",
        )

        val candidate = projector.extractMemoryCandidates(traceEvent).single()

        assertEquals(MemoryKind.PROJECT, candidate.kind)
        assertEquals("Walby", candidate.subject)
        assertTrue(candidate.text.contains("Project Walby"))
        assertTrue(candidate.reasonCodes.contains("explicit-user-memory-command"))
        assertTrue(candidate.reasonCodes.contains("explicit-user-project-context"))
    }

    @Test
    fun extractsAppointmentMemory() {
        val traceEvent = userTrace("I have a dentist appointment tomorrow at 3 PM.")

        val candidate = projector.extractMemoryCandidates(traceEvent).single()

        assertEquals(MemoryKind.APPOINTMENT, candidate.kind)
        assertTrue(candidate.text.contains("dentist appointment"))
        assertTrue(candidate.reasonCodes.contains("explicit-user-appointment"))
    }

    @Test
    fun genericRememberCommandCreatesGistMemory() {
        val traceEvent = userTrace("Remember that the workshop door code is 4582.")

        val candidate = projector.extractMemoryCandidates(traceEvent).single()

        assertEquals(MemoryKind.GIST, candidate.kind)
        assertEquals("the workshop door code is 4582.", candidate.text)
        assertTrue(candidate.reasonCodes.contains("explicit-user-general-memory"))
    }

    @Test
    fun forgetCommandDoesNotWriteNewMemory() {
        val traceEvent = userTrace("Forget my memory about Project Walby proposals.")

        val candidates = projector.extractMemoryCandidates(traceEvent)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun ignoresAssistantTextForDurableMemoryExtraction() {
        val traceEvent = TraceEventRecord(
            id = TraceEventId("trace-1"),
            sourceMessageId = ChatMessageId("message-1"),
            role = ConversationRole.ASSISTANT,
            text = "I prefer concise Kotlin code.",
            observedAt = Instant.parse("2026-06-14T16:00:00Z"),
        )

        val candidates = projector.extractMemoryCandidates(traceEvent)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun extractsExplicitCommitment() {
        val traceEvent = userTrace("Remind me to test the APK on my S25 Ultra tonight.")

        val candidates = projector.extractMemoryCandidates(traceEvent)

        assertEquals(MemoryKind.COMMITMENT, candidates.single().kind)
        assertTrue(candidates.single().reasonCodes.contains("explicit-user-commitment"))
    }

    @Test
    fun rejectsUserCorrectionAsProcedureMemory() {
        val traceEvent = userTrace("Never said that. You were half right, but don't save it.")

        val candidates = projector.extractMemoryCandidates(traceEvent)

        assertTrue(candidates.isEmpty())
    }

    private fun userTrace(text: String): TraceEventRecord =
        TraceEventRecord(
            id = TraceEventId("trace-1"),
            sourceMessageId = ChatMessageId("message-1"),
            role = ConversationRole.USER,
            text = text,
            observedAt = Instant.parse("2026-06-14T16:00:00Z"),
        )
}
