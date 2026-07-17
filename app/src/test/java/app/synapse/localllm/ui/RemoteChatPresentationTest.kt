package app.synapse.localllm.ui

import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedRoom
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteIdempotencyKey
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteMessageSearchResult
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteRoomKind
import app.synapse.localllm.domain.remote.RemoteRoomMemberRole
import app.synapse.localllm.domain.update.AvailableAppUpdate
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChatPresentationTest {
    @Test
    fun presenceLabelDistinguishesOnlineLastSeenAndUnknownProfiles() {
        assertEquals("Private synced chat", remotePresenceLabel(null))
        assertEquals("Online", remotePresenceLabel(profile(isOnline = true, lastSeenAt = NOW)))
        assertTrue(remotePresenceLabel(profile(isOnline = false, lastSeenAt = NOW)).startsWith("Last seen "))
        assertEquals("Offline", remotePresenceLabel(profile(isOnline = false, lastSeenAt = null)))
    }

    @Test
    fun updateLabelExposesAvailableReleaseWithoutHidingStatus() {
        val update = AvailableAppUpdate(
            versionCode = 42,
            releaseName = "Synapse 0.2.0",
            releaseUrl = "https://example.invalid/release",
            apkUrl = "https://example.invalid/Synapse-AI.apk",
            apkSha256 = null,
            byteCount = null,
        )

        assertEquals(
            "Update available: Synapse 0.2.0",
            remoteUpdateStatusLabel(
                AppUpdateUiState(
                    status = AppUpdateStatus.AVAILABLE,
                    availableUpdate = update,
                ),
            ),
        )
    }

    @Test
    fun peoplePresentationOrdersRecentContactsBeforeRemainingDirectory() {
        val trish = profile("trish-uid", "Trish")
        val josh = profile("josh-uid", "Josh")
        val alex = profile("alex-uid", "Alex")
        val presentation = buildRemotePeoplePresentation(
            profiles = listOf(trish, josh, alex),
            rooms = listOf(
                room(josh.profileUid, NOW.minusSeconds(60)),
                room(trish.profileUid, NOW),
            ),
            currentAccountUid = "peter-uid",
            searchQuery = "",
        )

        assertEquals(listOf(trish, josh), presentation.recentContacts)
        assertEquals(listOf(alex), presentation.directory)
    }

    @Test
    fun peopleSearchReturnsMatchesWithoutRecentContactDuplication() {
        val trish = profile("trish-uid", "Trish")
        val presentation = buildRemotePeoplePresentation(
            profiles = listOf(trish, profile("josh-uid", "Josh")),
            rooms = listOf(room(trish.profileUid, NOW)),
            currentAccountUid = "peter-uid",
            searchQuery = "tri",
        )

        assertEquals(emptyList<RemoteCachedProfile>(), presentation.recentContacts)
        assertEquals(listOf(trish), presentation.directory)
    }

    @Test
    fun peopleSearchNormalizesCompatibilityCharacters() {
        val trish = profile("trish-uid", "Trish")

        val presentation = buildRemotePeoplePresentation(
            profiles = listOf(trish),
            rooms = emptyList(),
            currentAccountUid = "peter-uid",
            searchQuery = "ＴＲＩ",
        )

        assertEquals(listOf(trish), presentation.directory)
    }

    @Test
    fun roomListPrioritizesPinnedThenActiveBeforeArchived() {
        val activeRoom = groupRoom("active", 'a', isPinned = false, isArchived = false)
        val archivedRoom = groupRoom("archived", 'b', isPinned = false, isArchived = true)
        val pinnedRoom = groupRoom("pinned", 'c', isPinned = true, isArchived = false)

        assertEquals(
            listOf(pinnedRoom, activeRoom, archivedRoom),
            orderRemoteRoomsForList(listOf(archivedRoom, activeRoom, pinnedRoom)),
        )
    }

    @Test
    fun roomPresentationSearchesPeersAndAppliesUnreadArchiveFilters() {
        val trish = profile("trish-uid", "Trish")
        val direct = room(trish.profileUid, NOW).copy(unreadCount = 2)
        val archived = groupRoom("Archived project", 'b', isPinned = false, isArchived = true)

        assertEquals(
            listOf(direct),
            buildRemoteRoomPresentation(
                rooms = listOf(archived, direct),
                profiles = listOf(trish),
                searchQuery = "ＴＲＩ",
                unreadOnly = true,
                showArchived = false,
            ),
        )
        assertEquals(
            listOf(archived),
            buildRemoteRoomPresentation(
                rooms = listOf(archived, direct),
                profiles = listOf(trish),
                searchQuery = "project",
                unreadOnly = false,
                showArchived = true,
            ),
        )
    }

    @Test
    fun loginSubmissionRequiresEveryVisibleRegistrationField() {
        assertTrue(
            remoteLoginSubmissionEnabled(
                showRegistration = false,
                username = "peter",
                displayName = "",
                password = "password",
                confirmPassword = "",
                invitationCode = "",
                isActionRunning = false,
            ),
        )
        assertFalse(
            remoteLoginSubmissionEnabled(
                showRegistration = true,
                username = "peter",
                displayName = "Peter",
                password = "password",
                confirmPassword = "password",
                invitationCode = "",
                isActionRunning = false,
            ),
        )
        assertTrue(
            remoteLoginSubmissionEnabled(
                showRegistration = true,
                username = "peter",
                displayName = "Peter",
                password = "password",
                confirmPassword = "password",
                invitationCode = "invite-code",
                isActionRunning = false,
            ),
        )
    }

    @Test
    fun messageSearchPresentationDistinguishesHiddenLoadingEmptyAndResults() {
        val result = RemoteMessageSearchResult(
            roomId = RemoteRoomId("room-1"),
            messageId = RemoteMessageId("message-1"),
            excerpt = "Matching text",
        )

        assertEquals(
            RemoteMessageSearchPresentationState.HIDDEN,
            remoteMessageSearchPresentationState("", isSearching = false, results = emptyList()),
        )
        assertEquals(
            RemoteMessageSearchPresentationState.SEARCHING,
            remoteMessageSearchPresentationState("match", isSearching = true, results = emptyList()),
        )
        assertEquals(
            RemoteMessageSearchPresentationState.EMPTY,
            remoteMessageSearchPresentationState("match", isSearching = false, results = emptyList()),
        )
        assertEquals(
            RemoteMessageSearchPresentationState.RESULTS,
            remoteMessageSearchPresentationState("match", isSearching = false, results = listOf(result)),
        )
    }

    @Test
    fun richMessagePresentationUsesServerTimeAndExplicitDeliveryLabels() {
        val serverTime = NOW.plusSeconds(10)
        assertEquals(serverTime, remoteMessage(RemoteMessageDeliveryState.SENT, serverTime).displayInstant())
        assertEquals("Sending…", remoteMessageDeliveryLabel(remoteMessage(RemoteMessageDeliveryState.PENDING)))
        assertEquals("Sent", remoteMessageDeliveryLabel(remoteMessage(RemoteMessageDeliveryState.SENT)))
        assertEquals("Delivered", remoteMessageDeliveryLabel(remoteMessage(RemoteMessageDeliveryState.DELIVERED)))
        assertEquals("Read", remoteMessageDeliveryLabel(remoteMessage(RemoteMessageDeliveryState.READ)))
        assertEquals(
            "Network unavailable",
            remoteMessageDeliveryLabel(
                remoteMessage(RemoteMessageDeliveryState.FAILED).copy(failureReason = "Network unavailable"),
            ),
        )
    }

    @Test
    fun directComposerRequiresTextOrOnlyReadyAttachments() {
        assertFalse(
            remoteComposerCanSend(
                "",
                emptyList(),
                isRecordingVoiceNote = false,
                isActionRunning = false,
            ),
        )
        assertTrue(
            remoteComposerCanSend(
                "Hello",
                emptyList(),
                isRecordingVoiceNote = false,
                isActionRunning = false,
            ),
        )
        assertTrue(
            remoteComposerCanSend(
                composerText = "",
                attachmentStates = listOf(RemoteAttachmentTransferState.READY),
                isRecordingVoiceNote = false,
                isActionRunning = false,
            ),
        )
        assertFalse(
            remoteComposerCanSend(
                composerText = "",
                attachmentStates = listOf(
                    RemoteAttachmentTransferState.READY,
                    RemoteAttachmentTransferState.UPLOADING,
                ),
                isRecordingVoiceNote = false,
                isActionRunning = false,
            ),
        )
        assertFalse(
            remoteComposerCanSend(
                "Hello",
                emptyList(),
                isRecordingVoiceNote = true,
                isActionRunning = false,
            ),
        )
        assertFalse(
            remoteComposerCanSend(
                "Hello",
                emptyList(),
                isRecordingVoiceNote = false,
                isActionRunning = true,
            ),
        )
    }

    @Test
    fun directComposerPickerOffersGIFsSeparatelyFromFilesAndAudio() {
        assertTrue("image/gif" in REMOTE_PHOTO_AND_GIF_MIME_TYPES)
        assertFalse(REMOTE_FILE_AND_AUDIO_MIME_TYPES.any { mimeType -> mimeType.startsWith("image/") })
        assertTrue(REMOTE_FILE_AND_AUDIO_MIME_TYPES.any { mimeType -> mimeType.startsWith("audio/") })
    }

    private fun profile(
        isOnline: Boolean,
        lastSeenAt: Instant?,
    ) = RemoteCachedProfile(
        accountUid = RemoteAccountUid("peter-uid"),
        profileUid = RemoteProfileUid("peter-uid"),
        username = "Peter",
        displayName = "Peter",
        bio = "",
        avatarUrl = null,
        isAllowed = true,
        isOnline = isOnline,
        lastSeenAt = lastSeenAt,
        remoteUpdatedAt = NOW,
    )

    private fun profile(uid: String, displayName: String) = RemoteCachedProfile(
        accountUid = RemoteAccountUid("peter-uid"),
        profileUid = RemoteProfileUid(uid),
        username = displayName.lowercase(),
        displayName = displayName,
        bio = "",
        avatarUrl = null,
        isAllowed = true,
        isOnline = false,
        lastSeenAt = null,
        remoteUpdatedAt = NOW,
    )

    private fun room(peerUid: RemoteProfileUid, updatedAt: Instant) = RemoteCachedRoom(
        accountUid = RemoteAccountUid("peter-uid"),
        roomId = RemoteRoomId("room-${peerUid.raw}"),
        kind = RemoteRoomKind.DIRECT,
        directKey = "peter-uid:${peerUid.raw}",
        peerUid = peerUid,
        title = peerUid.raw,
        avatarObjectPath = null,
        unreadCount = 0,
        latestMessagePreview = null,
        latestMessageSenderUid = null,
        currentMemberRole = RemoteRoomMemberRole.MEMBER,
        notificationsEnabled = true,
        isMuted = false,
        isArchived = false,
        isPinned = false,
        joinedAt = updatedAt,
        lastReadAt = null,
        remoteUpdatedAt = updatedAt,
    )

    private fun groupRoom(
        title: String,
        identifierCharacter: Char,
        isPinned: Boolean,
        isArchived: Boolean,
    ) = RemoteCachedRoom(
        accountUid = RemoteAccountUid("peter-uid"),
        roomId = RemoteRoomId("group_${identifierCharacter.toString().repeat(32)}"),
        kind = RemoteRoomKind.GROUP,
        directKey = null,
        peerUid = null,
        title = title,
        avatarObjectPath = null,
        unreadCount = 0,
        latestMessagePreview = null,
        latestMessageSenderUid = null,
        currentMemberRole = RemoteRoomMemberRole.MEMBER,
        notificationsEnabled = true,
        isMuted = false,
        isArchived = isArchived,
        isPinned = isPinned,
        joinedAt = NOW,
        lastReadAt = null,
        remoteUpdatedAt = NOW,
    )

    private fun remoteMessage(
        deliveryState: RemoteMessageDeliveryState,
        serverCreatedAt: Instant? = null,
    ) = RemoteCachedMessage(
        accountUid = RemoteAccountUid("peter-uid"),
        roomId = RemoteRoomId("direct_${"a".repeat(64)}"),
        messageId = RemoteMessageId("message-1"),
        idempotencyKey = RemoteIdempotencyKey("message-1"),
        senderUid = RemoteProfileUid("peter-uid"),
        authorKind = "HUMAN",
        body = "Hello",
        replyToMessageId = null,
        editedAt = null,
        deletedAt = null,
        revision = 1,
        reactionCounts = emptyMap(),
        deliveredToCount = 0,
        readByCount = 0,
        deliveryState = deliveryState,
        clientCreatedAt = NOW,
        serverCreatedAt = serverCreatedAt,
        failureReason = null,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-13T08:00:00Z")
    }
}
