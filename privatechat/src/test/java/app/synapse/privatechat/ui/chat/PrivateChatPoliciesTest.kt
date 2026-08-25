package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.ChangePrivateRoomRetentionCommand
import app.synapse.privatechat.domain.chat.PrivateActivitySharingPreferences
import app.synapse.privatechat.domain.chat.PrivateChatMutationReceipt
import app.synapse.privatechat.domain.chat.PrivateClientMutationId
import app.synapse.privatechat.domain.chat.PrivateConversationSnapshot
import app.synapse.privatechat.domain.chat.PrivateMessageId
import app.synapse.privatechat.domain.chat.PrivateMessageOwnership
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateMessageSnapshot
import app.synapse.privatechat.domain.chat.PrivateMessageText
import app.synapse.privatechat.domain.chat.PrivatePresenceSharingState
import app.synapse.privatechat.domain.chat.PrivatePresenceSnapshot
import app.synapse.privatechat.domain.chat.PrivateProfileSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomFeedSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomInvitationCode
import app.synapse.privatechat.domain.chat.PrivateRoomInvitationId
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMemberRole
import app.synapse.privatechat.domain.chat.PrivateRoomMemberSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import app.synapse.privatechat.domain.chat.PrivateRoomSummary
import app.synapse.privatechat.domain.chat.PrivateSocialSnapshot
import app.synapse.privatechat.domain.chat.PrivateTypingParticipant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PrivateChatPoliciesTest {
    @Test
    fun `conversation policy removes expired content self typing and orders deterministically`() {
        val now = Instant.parse("2026-08-20T18:00:00Z")
        val currentAccount = PrivateAccountId("current")
        val room = roomSummary()
        val later = message(room.roomId, "later", now.minusSeconds(10), now.plusSeconds(30))
        val earlier = message(room.roomId, "earlier", now.minusSeconds(20), now.plusSeconds(30))
        val expired = message(room.roomId, "expired", now.minusSeconds(40), now)
        val snapshot =
            PrivateConversationSnapshot(
                accountId = currentAccount,
                room = room,
                members =
                    listOf(
                        PrivateRoomMemberSnapshot(currentAccount, "Current", PrivateRoomMemberRole.OWNER),
                        PrivateRoomMemberSnapshot(PrivateAccountId("friend"), "Friend", PrivateRoomMemberRole.MEMBER),
                    ),
                messages = listOf(later, expired, earlier),
                typingParticipants =
                    listOf(
                        PrivateTypingParticipant(currentAccount, "Current", now.plusSeconds(10)),
                        PrivateTypingParticipant(PrivateAccountId("expired-typing"), "Old", now),
                        PrivateTypingParticipant(PrivateAccountId("friend"), "Friend", now.plusSeconds(10)),
                    ),
            )

        val sanitized = PrivateChatSnapshotPolicy.sanitizeConversation(snapshot, now)

        assertEquals(listOf("earlier", "later"), sanitized.messages.map { it.messageId.canonical })
        assertEquals(listOf("friend"), sanitized.typingParticipants.map { it.accountId.canonical })
    }

    @Test
    fun `room feed reducer closes a conversation removed by the authoritative feed`() {
        val missingRoomId = PrivateRoomId("missing")
        val state =
            PrivateChatUiState(
                session = PrivateChatSessionUiState.Active(PrivateAccountId("current")),
                selectedRoomId = missingRoomId,
                conversation = PrivateConversationUiState.Loading,
                composerText = "sensitive draft",
            )
        val snapshot =
            PrivateRoomFeedSnapshot(
                accountId = PrivateAccountId("current"),
                rooms = listOf(roomSummary()),
                activitySharingPreferences = PrivateActivitySharingPreferences(),
            )

        val reduced = PrivateChatUiReducer.acceptRoomFeed(state, snapshot)

        assertNull(reduced.selectedRoomId)
        assertTrue(reduced.conversation is PrivateConversationUiState.NotSelected)
        assertEquals("", reduced.composerText)
    }

    @Test
    fun `retention receipt must match every command identity field`() {
        val command =
            ChangePrivateRoomRetentionCommand(
                accountId = PrivateAccountId("current"),
                roomId = PrivateRoomId("room"),
                mutationId = PrivateClientMutationId("mutation"),
                retention = PrivateMessageRetention.ONE_DAY,
            )
        val receipt =
            PrivateChatMutationReceipt.RetentionChanged(
                accountId = command.accountId,
                roomId = command.roomId,
                mutationId = PrivateClientMutationId("different-mutation"),
                retention = command.retention,
            )

        assertTrue(!PrivateChatReceiptValidator.matches(receipt, command))
    }

    @Test
    fun `social policy removes expired presence and orders peers deterministically`() {
        val now = Instant.parse("2026-08-20T18:00:00Z")
        val snapshot =
            PrivateSocialSnapshot(
                accountId = PrivateAccountId("current"),
                profile = PrivateProfileSnapshot(PrivateAccountId("current"), "Current", "current_user"),
                presenceSharing = PrivatePresenceSharingState.DISABLED,
                visiblePresence =
                    listOf(
                        presence("zebra", "Zebra", now.minusSeconds(5), now.plusSeconds(10)),
                        presence("expired", "Expired", now.minusSeconds(20), now),
                        presence("alpha", "alpha", now.minusSeconds(5), now.plusSeconds(10)),
                    ),
            )

        val sanitized = PrivateChatSnapshotPolicy.sanitizeSocial(snapshot, now)

        assertEquals(listOf("alpha", "zebra"), sanitized.visiblePresence.map { it.accountId.canonical })
    }

    @Test
    fun `chat state string redacts drafts and authoritative message snapshots`() {
        val now = Instant.parse("2026-08-20T18:00:00Z")
        val room = roomSummary()
        val currentAccount = PrivateAccountId("current")
        val conversation =
            PrivateConversationSnapshot(
                accountId = currentAccount,
                room = room,
                members =
                    listOf(
                        PrivateRoomMemberSnapshot(currentAccount, "Current", PrivateRoomMemberRole.OWNER),
                        PrivateRoomMemberSnapshot(PrivateAccountId("friend"), "Friend", PrivateRoomMemberRole.MEMBER),
                    ),
                messages = listOf(message(room.roomId, "secret-message", now, now.plusSeconds(60))),
                typingParticipants = emptyList(),
            )
        val renderedState =
            PrivateChatUiState(
                session = PrivateChatSessionUiState.Active(currentAccount),
                roomFeed =
                    PrivateRoomFeedUiState.Available(
                        PrivateRoomFeedSnapshot(
                            accountId = currentAccount,
                            rooms = listOf(room),
                            activitySharingPreferences = PrivateActivitySharingPreferences(),
                        ),
                    ),
                selectedRoomId = room.roomId,
                conversation = PrivateConversationUiState.Available(conversation),
                composerText = "sensitive draft",
            ).toString()

        assertTrue(!renderedState.contains("sensitive draft"))
        assertTrue(!renderedState.contains("message-secret-message"))
    }

    @Test
    fun `presence summary bounds the number of rendered peer names`() {
        val now = Instant.parse("2026-08-20T18:00:00Z")
        val peers =
            listOf(
                presence("one", "One", now, now.plusSeconds(60)),
                presence("two", "Two", now, now.plusSeconds(60)),
                presence("three", "Three", now, now.plusSeconds(60)),
                presence("four", "Four", now, now.plusSeconds(60)),
            )

        assertEquals("Online now: One, Two, Three, and 1 other", privateVisiblePresenceLabel(peers))
    }

    @Test
    fun `presentation expiry sweep removes stale state and exits an expired edit`() {
        val now = Instant.parse("2026-08-20T18:00:00Z")
        val currentAccount = PrivateAccountId("current")
        val room = roomSummary()
        val expiredMessage = message(room.roomId, "expired-edit", now.minusSeconds(60), now)
        val state =
            PrivateChatUiState(
                session = PrivateChatSessionUiState.Active(currentAccount),
                social =
                    PrivateSocialUiState.Available(
                        PrivateSocialSnapshot(
                            accountId = currentAccount,
                            profile = PrivateProfileSnapshot(currentAccount, "Current", "current_user"),
                            presenceSharing = PrivatePresenceSharingState.ENABLED,
                            visiblePresence =
                                listOf(
                                    presence("friend", "Friend", now.minusSeconds(20), now),
                                ),
                        ),
                    ),
                selectedRoomId = room.roomId,
                conversation =
                    PrivateConversationUiState.Available(
                        PrivateConversationSnapshot(
                            accountId = currentAccount,
                            room = room,
                            members =
                                listOf(
                                    PrivateRoomMemberSnapshot(currentAccount, "Current", PrivateRoomMemberRole.OWNER),
                                    PrivateRoomMemberSnapshot(
                                        PrivateAccountId("friend"),
                                        "Friend",
                                        PrivateRoomMemberRole.MEMBER,
                                    ),
                                ),
                            messages = listOf(expiredMessage),
                            typingParticipants = emptyList(),
                        ),
                    ),
                composerText = "stale edit",
                composerMode =
                    PrivateComposerMode.Editing(
                        messageId = expiredMessage.messageId,
                        expectedRevision = expiredMessage.revision,
                        originalBody = expiredMessage.body,
                        draftBeforeEdit = "kept draft",
                    ),
                roomInvitation =
                    PrivateRoomInvitationUiState.Confirmed(
                        PrivateChatMutationReceipt.OneUseRoomInvitationCreated(
                            accountId = currentAccount,
                            roomId = room.roomId,
                            mutationId = PrivateClientMutationId("mutation"),
                            invitationId = PrivateRoomInvitationId("invitation"),
                            invitationCode = PrivateRoomInvitationCode("A".repeat(32)),
                            expiresAt = now,
                        ),
                    ),
            )

        val sanitized = PrivateChatSnapshotPolicy.sanitizePresentedState(state, now)
        val conversation = sanitized.conversation as PrivateConversationUiState.Available
        val social = sanitized.social as PrivateSocialUiState.Available

        assertTrue(conversation.snapshot.messages.isEmpty())
        assertTrue(social.snapshot.visiblePresence.isEmpty())
        assertEquals("kept draft", sanitized.composerText)
        assertTrue(sanitized.composerMode is PrivateComposerMode.NewMessage)
        assertTrue(sanitized.roomInvitation is PrivateRoomInvitationUiState.Hidden)
    }

    @Test
    fun `background policy clears invitation state and closes sensitive overlays`() {
        val state =
            PrivateChatUiState(
                roomInvitation = PrivateRoomInvitationUiState.Creating,
                accountInvitation = PrivateAccountInvitationUiState.Creating,
                overlay = PrivateChatOverlay.CREATE_CONVERSATION,
            )

        val sanitized = PrivateChatSnapshotPolicy.clearInvitationSecretsForBackground(state)

        assertTrue(sanitized.roomInvitation is PrivateRoomInvitationUiState.Hidden)
        assertTrue(sanitized.accountInvitation is PrivateAccountInvitationUiState.Hidden)
        assertEquals(PrivateChatOverlay.HIDDEN, sanitized.overlay)
    }

    private fun roomSummary(): PrivateRoomSummary =
        PrivateRoomSummary(
            roomId = PrivateRoomId("room"),
            kind = PrivateRoomKind.DIRECT,
            title = "Friend",
            participantCount = 2,
            retention = PrivateMessageRetention.ONE_DAY,
            archiveState = PrivateRoomArchiveState.ACTIVE,
            pinState = PrivateRoomPinState.UNPINNED,
            muteState = PrivateRoomMuteState.AUDIBLE,
            unreadMessageCount = 0,
            latestMessagePreview = null,
        )

    private fun message(
        roomId: PrivateRoomId,
        id: String,
        sentAt: Instant,
        expiresAt: Instant,
    ): PrivateMessageSnapshot =
        PrivateMessageSnapshot(
            roomId = roomId,
            messageId = PrivateMessageId(id),
            senderAccountId = PrivateAccountId("friend"),
            senderDisplayName = "Friend",
            ownership = PrivateMessageOwnership.OTHER_PARTICIPANT,
            body = PrivateMessageText("message-$id"),
            replyPreview = null,
            revision = 1,
            reactions = emptyList(),
            sentAt = sentAt,
            editedAt = null,
            expiresAt = expiresAt,
        )

    private fun presence(
        accountId: String,
        displayName: String,
        publishedAt: Instant,
        expiresAt: Instant,
    ): PrivatePresenceSnapshot =
        PrivatePresenceSnapshot(
            accountId = PrivateAccountId(accountId),
            displayName = displayName,
            publishedAt = publishedAt,
            expiresAt = expiresAt,
        )
}
