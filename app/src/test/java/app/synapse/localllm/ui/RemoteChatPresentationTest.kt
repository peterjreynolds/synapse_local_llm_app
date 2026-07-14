package app.synapse.localllm.ui

import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteCachedRoom
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteRoomKind
import app.synapse.localllm.domain.remote.RemoteRoomMemberRole
import app.synapse.localllm.domain.update.AvailableAppUpdate
import java.time.Instant
import org.junit.Assert.assertEquals
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
    fun roomListPrioritizesPinnedThenActiveBeforeArchived() {
        val activeRoom = groupRoom("active", 'a', isPinned = false, isArchived = false)
        val archivedRoom = groupRoom("archived", 'b', isPinned = false, isArchived = true)
        val pinnedRoom = groupRoom("pinned", 'c', isPinned = true, isArchived = false)

        assertEquals(
            listOf(pinnedRoom, activeRoom, archivedRoom),
            orderRemoteRoomsForList(listOf(archivedRoom, activeRoom, pinnedRoom)),
        )
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

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-13T08:00:00Z")
    }
}
