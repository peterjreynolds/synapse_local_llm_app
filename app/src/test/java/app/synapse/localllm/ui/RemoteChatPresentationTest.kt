package app.synapse.localllm.ui

import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteProfileUid
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

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-13T08:00:00Z")
    }
}
