package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.chat.PrivateConversationSnapshot
import app.synapse.privatechat.domain.chat.PrivateMessageSnapshot
import app.synapse.privatechat.domain.chat.PrivatePresenceSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomFeedSnapshot
import app.synapse.privatechat.domain.chat.PrivateSocialSnapshot
import java.time.Instant

internal object PrivateChatSnapshotPolicy {
    fun clearInvitationSecretsForBackground(state: PrivateChatUiState): PrivateChatUiState =
        state.copy(
            roomInvitation = PrivateRoomInvitationUiState.Hidden,
            accountInvitation = PrivateAccountInvitationUiState.Hidden,
            overlay = PrivateChatOverlay.HIDDEN,
        )

    fun sanitizePresentedState(
        state: PrivateChatUiState,
        now: Instant,
    ): PrivateChatUiState {
        val roomFeed =
            when (val currentRoomFeed = state.roomFeed) {
                is PrivateRoomFeedUiState.Available ->
                    PrivateRoomFeedUiState.Available(sanitizeRoomFeed(currentRoomFeed.snapshot, now))

                else -> currentRoomFeed
            }
        val conversation =
            when (val currentConversation = state.conversation) {
                is PrivateConversationUiState.Available ->
                    PrivateConversationUiState.Available(sanitizeConversation(currentConversation.snapshot, now))

                else -> currentConversation
            }
        val social =
            when (val currentSocial = state.social) {
                is PrivateSocialUiState.Available ->
                    PrivateSocialUiState.Available(sanitizeSocial(currentSocial.snapshot, now))

                else -> currentSocial
            }
        val roomInvitation =
            when (val currentInvitation = state.roomInvitation) {
                is PrivateRoomInvitationUiState.Confirmed ->
                    if (currentInvitation.receipt.expiresAt.isAfter(now)) {
                        currentInvitation
                    } else {
                        PrivateRoomInvitationUiState.Hidden
                    }

                else -> currentInvitation
            }
        val accountInvitation =
            when (val currentInvitation = state.accountInvitation) {
                is PrivateAccountInvitationUiState.Confirmed ->
                    if (currentInvitation.receipt.expiresAt.isAfter(now)) {
                        currentInvitation
                    } else {
                        PrivateAccountInvitationUiState.Hidden
                    }

                else -> currentInvitation
            }
        val reconciledComposer = reconcileComposerWithConversation(state, conversation)
        if (
            roomFeed == state.roomFeed &&
            conversation == state.conversation &&
            social == state.social &&
            roomInvitation == state.roomInvitation &&
            accountInvitation == state.accountInvitation &&
            reconciledComposer.first == state.composerText &&
            reconciledComposer.second == state.composerMode
        ) {
            return state
        }
        return state.copy(
            roomFeed = roomFeed,
            conversation = conversation,
            social = social,
            composerText = reconciledComposer.first,
            composerMode = reconciledComposer.second,
            roomInvitation = roomInvitation,
            accountInvitation = accountInvitation,
        )
    }

    fun sanitizeRoomFeed(
        snapshot: PrivateRoomFeedSnapshot,
        now: Instant,
    ): PrivateRoomFeedSnapshot =
        snapshot.copy(
            rooms =
                snapshot.rooms.map { room ->
                    room.copy(
                        latestMessagePreview =
                            room.latestMessagePreview?.takeIf { preview -> preview.expiresAt.isAfter(now) },
                    )
                },
        )

    fun sanitizeConversation(
        snapshot: PrivateConversationSnapshot,
        now: Instant,
    ): PrivateConversationSnapshot =
        snapshot.copy(
            room =
                snapshot.room.copy(
                    latestMessagePreview =
                        snapshot.room.latestMessagePreview?.takeIf { preview -> preview.expiresAt.isAfter(now) },
                ),
            messages =
                snapshot.messages
                    .filter { message -> message.expiresAt.isAfter(now) }
                    .sortedWith(
                        compareBy<PrivateMessageSnapshot> { message -> message.sentAt }
                            .thenBy { message -> message.messageId.canonical },
                    ),
            typingParticipants =
                snapshot.typingParticipants.filter { participant ->
                    participant.accountId != snapshot.accountId && participant.expiresAt.isAfter(now)
                },
        )

    fun sanitizeSocial(
        snapshot: PrivateSocialSnapshot,
        now: Instant,
    ): PrivateSocialSnapshot =
        snapshot.copy(
            visiblePresence =
                snapshot.visiblePresence
                    .filter { presence -> presence.expiresAt.isAfter(now) }
                    .sortedWith(
                        compareBy<PrivatePresenceSnapshot> { presence ->
                            presence.displayName.lowercase()
                        }.thenBy { presence -> presence.accountId.canonical },
                    ),
        )

    private fun reconcileComposerWithConversation(
        state: PrivateChatUiState,
        conversation: PrivateConversationUiState,
    ): Pair<String, PrivateComposerMode> {
        val presentedMessages =
            (conversation as? PrivateConversationUiState.Available)
                ?.snapshot
                ?.messages
                ?: return state.composerText to state.composerMode
        return when (val composerMode = state.composerMode) {
            is PrivateComposerMode.Editing ->
                if (presentedMessages.any { message -> message.messageId == composerMode.messageId }) {
                    state.composerText to composerMode
                } else {
                    composerMode.draftBeforeEdit to PrivateComposerMode.NewMessage
                }

            is PrivateComposerMode.ReplyingTo ->
                if (presentedMessages.any { message -> message.messageId == composerMode.preview.messageId }) {
                    state.composerText to composerMode
                } else {
                    state.composerText to PrivateComposerMode.NewMessage
                }

            PrivateComposerMode.NewMessage -> state.composerText to composerMode
        }
    }
}
