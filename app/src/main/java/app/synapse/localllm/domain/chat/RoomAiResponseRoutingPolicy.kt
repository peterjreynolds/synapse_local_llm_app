package app.synapse.localllm.domain.chat

enum class AiResponseDecisionReason {
    AI_CHAT_AUTOMATIC,
    ROOM_AUTOMATIC,
    SYNAPSE_MENTIONED,
    SYNAPSE_NOT_A_MEMBER,
    MENTION_REQUIRED,
}

data class AiResponseDecision(
    val shouldRespond: Boolean,
    val reason: AiResponseDecisionReason,
)

class RoomAiResponseRoutingPolicy {
    fun decide(
        room: ChatRoomRecord,
        humanMessageBody: String,
    ): AiResponseDecision {
        val synapseMembership = room.activeMembers.firstOrNull { member ->
            member.participant.id == BuiltInParticipantIds.SYNAPSE_LOCAL_AI &&
                member.participant.kind == ParticipantKind.LOCAL_AI
        } ?: return AiResponseDecision(
            shouldRespond = false,
            reason = AiResponseDecisionReason.SYNAPSE_NOT_A_MEMBER,
        )

        if (room.kind == RoomKind.AI_CHAT) {
            return AiResponseDecision(
                shouldRespond = true,
                reason = AiResponseDecisionReason.AI_CHAT_AUTOMATIC,
            )
        }
        if (synapseMembership.aiResponsePolicy == AiResponsePolicy.AUTOMATIC) {
            return AiResponseDecision(
                shouldRespond = true,
                reason = AiResponseDecisionReason.ROOM_AUTOMATIC,
            )
        }
        if (synapseMentionPattern.containsMatchIn(humanMessageBody)) {
            return AiResponseDecision(
                shouldRespond = true,
                reason = AiResponseDecisionReason.SYNAPSE_MENTIONED,
            )
        }
        return AiResponseDecision(
            shouldRespond = false,
            reason = AiResponseDecisionReason.MENTION_REQUIRED,
        )
    }

    private companion object {
        val synapseMentionPattern = Regex("(?<![\\p{L}\\p{N}_])@synapse\\b", RegexOption.IGNORE_CASE)
    }
}
