package app.synapse.localllm.data.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.domain.remote.CacheRemoteMessagesCommand
import app.synapse.localllm.domain.remote.CacheRemoteProfilesCommand
import app.synapse.localllm.domain.remote.CacheRemoteRoomsCommand
import app.synapse.localllm.domain.remote.EnqueueRemoteMessageCommand
import app.synapse.localllm.domain.remote.RemoteAccountSessionResource
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteCacheMutation
import app.synapse.localllm.domain.remote.RemoteCachedDirectRoom
import app.synapse.localllm.domain.remote.RemoteCachedMembership
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteIdempotencyKey
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteMessageOutboxOperation
import app.synapse.localllm.domain.remote.RemoteOutboxState
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomRemoteChatCacheRepositoryTest {
    private lateinit var database: SynapseDatabase
    private lateinit var sessionCoordinator: RemoteAccountSessionCoordinator
    private lateinit var applicationScope: CoroutineScope
    private lateinit var repository: RoomRemoteChatCacheRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SynapseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionCoordinator = RemoteAccountSessionCoordinator()
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repository = RoomRemoteChatCacheRepository(
            database = database,
            remoteChatCacheDao = database.remoteChatCacheDao(),
            sessionController = sessionCoordinator,
            clock = FixedClock,
            applicationScope = applicationScope,
        )
    }

    @After
    fun tearDown() {
        applicationScope.cancel()
        database.close()
    }

    @Test
    fun accountSwitchScopesEveryQueryWithoutDeletingPriorAccountRows() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        repository.cacheProfiles(
            CacheRemoteProfilesCommand(
                accountUid = PETER_ACCOUNT,
                profiles = listOf(remoteProfile(PETER_ACCOUNT, TRISH_PROFILE, "Trish")),
            ),
        )
        cacheRoom(PETER_ACCOUNT, TRISH_PROFILE)

        repository.activateAccount(TRISH_ACCOUNT)
        repository.cacheProfiles(
            CacheRemoteProfilesCommand(
                accountUid = TRISH_ACCOUNT,
                profiles = listOf(remoteProfile(TRISH_ACCOUNT, PETER_PROFILE, "Peter")),
            ),
        )
        cacheRoom(TRISH_ACCOUNT, PETER_PROFILE)

        assertEquals(listOf("Peter"), repository.observeProfiles().first().map { profile -> profile.username })
        assertEquals(PETER_PROFILE, repository.observeDirectRooms().first().single().peerUid)

        repository.activateAccount(PETER_ACCOUNT)
        assertEquals(listOf("Trish"), repository.observeProfiles().first().map { profile -> profile.username })
        assertEquals(TRISH_PROFILE, repository.observeDirectRooms().first().single().peerUid)

        repository.clearActiveAccount()
        assertTrue(repository.observeProfiles().first().isEmpty())
        assertTrue(repository.observeDirectRooms().first().isEmpty())

        repository.activateAccount(TRISH_ACCOUNT)
        assertEquals(listOf("Peter"), repository.observeProfiles().first().map { profile -> profile.username })
    }

    @Test
    fun idempotencyKeyPreventsDuplicateCachedMessageAndOutboxRows() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        cacheRoom(PETER_ACCOUNT, TRISH_PROFILE)
        val command = enqueueMessageCommand(PETER_ACCOUNT, "message-1", "idempotency-1")

        val firstReceipt = repository.enqueueMessage(command)
        val duplicateReceipt = repository.enqueueMessage(command)

        assertEquals(RemoteCacheMutation.MESSAGE_ENQUEUED, firstReceipt.mutation)
        assertEquals(2, firstReceipt.affectedRows)
        assertEquals(RemoteCacheMutation.MESSAGE_ALREADY_ENQUEUED, duplicateReceipt.mutation)
        assertEquals(0, duplicateReceipt.affectedRows)
        assertEquals(1, repository.observeMessages(ROOM_ID).first().size)
        assertEquals(1, repository.observePendingOutbox().first().size)

        repository.activateAccount(TRISH_ACCOUNT)
        assertTrue(repository.observeMessages(ROOM_ID).first().isEmpty())
        assertTrue(repository.observePendingOutbox().first().isEmpty())
    }

    @Test
    fun tiedRemoteTimestampsUseMessageIdAsDeterministicOrderingKey() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        cacheRoom(PETER_ACCOUNT, TRISH_PROFILE)
        val tiedTimestamp = Instant.parse("2026-07-13T05:00:00Z")
        val messages = listOf(
            remoteMessage(PETER_ACCOUNT, "message-b", "key-b", tiedTimestamp),
            remoteMessage(PETER_ACCOUNT, "message-a", "key-a", tiedTimestamp),
        )

        repository.cacheMessages(CacheRemoteMessagesCommand(PETER_ACCOUNT, messages))

        assertEquals(
            listOf("message-a", "message-b"),
            repository.observeMessages(ROOM_ID).first().map { message -> message.messageId.raw },
        )
    }

    @Test
    fun accountSwitchAndLogoutCancelRegisteredSessionResources() = runTest {
        val peterResource = RecordingSessionResource()
        val peterToken = sessionCoordinator.beginSession(PETER_ACCOUNT)
        sessionCoordinator.registerResource(peterToken, peterResource)

        val trishResource = RecordingSessionResource()
        val trishToken = sessionCoordinator.beginSession(TRISH_ACCOUNT)
        assertTrue(peterResource.wasCancelled)
        sessionCoordinator.registerResource(trishToken, trishResource)

        val staleResource = RecordingSessionResource()
        val staleRegistration = runCatching {
            sessionCoordinator.registerResource(peterToken, staleResource)
        }
        assertTrue(staleRegistration.isFailure)
        assertTrue(staleResource.wasCancelled)

        sessionCoordinator.endSession()
        assertTrue(trishResource.wasCancelled)
        assertNull(sessionCoordinator.activeSession.value)
    }

    private suspend fun cacheRoom(
        accountUid: RemoteAccountUid,
        peerUid: RemoteProfileUid,
    ) {
        repository.cacheRooms(
            CacheRemoteRoomsCommand(
                accountUid = accountUid,
                rooms = listOf(
                    RemoteCachedDirectRoom(
                        accountUid = accountUid,
                        roomId = ROOM_ID,
                        directKey = "peter-uid:trish-uid",
                        peerUid = peerUid,
                        title = "Peter, Trish",
                        unreadCount = 0,
                        latestMessagePreview = null,
                        latestMessageSenderUid = null,
                        remoteUpdatedAt = FixedClock.now(),
                    ),
                ),
                memberships = listOf(
                    RemoteCachedMembership(
                        accountUid = accountUid,
                        roomId = ROOM_ID,
                        memberUid = RemoteProfileUid(accountUid.raw),
                        role = "MEMBER",
                        isActive = true,
                        joinedAt = FixedClock.now(),
                        lastReadAt = null,
                    ),
                ),
            ),
        )
    }

    private fun remoteProfile(
        accountUid: RemoteAccountUid,
        profileUid: RemoteProfileUid,
        username: String,
    ): RemoteCachedProfile =
        RemoteCachedProfile(
            accountUid = accountUid,
            profileUid = profileUid,
            username = username,
            displayName = username,
            bio = "",
            avatarUrl = null,
            isAllowed = true,
            isOnline = false,
            lastSeenAt = null,
            remoteUpdatedAt = FixedClock.now(),
        )

    private fun enqueueMessageCommand(
        accountUid: RemoteAccountUid,
        messageId: String,
        idempotencyKey: String,
    ): EnqueueRemoteMessageCommand {
        val cachedMessage = remoteMessage(accountUid, messageId, idempotencyKey, null)
        return EnqueueRemoteMessageCommand(
            message = cachedMessage,
            outboxOperation = RemoteMessageOutboxOperation(
                accountUid = accountUid,
                operationId = "operation-$messageId",
                roomId = ROOM_ID,
                messageId = cachedMessage.messageId,
                idempotencyKey = cachedMessage.idempotencyKey,
                senderUid = PETER_PROFILE,
                body = cachedMessage.body,
                state = RemoteOutboxState.PENDING,
                attemptCount = 0,
                createdAt = cachedMessage.clientCreatedAt,
                lastAttemptAt = null,
                failureReason = null,
            ),
        )
    }

    private fun remoteMessage(
        accountUid: RemoteAccountUid,
        messageId: String,
        idempotencyKey: String,
        serverCreatedAt: Instant?,
    ): RemoteCachedMessage =
        RemoteCachedMessage(
            accountUid = accountUid,
            roomId = ROOM_ID,
            messageId = RemoteMessageId(messageId),
            idempotencyKey = RemoteIdempotencyKey(idempotencyKey),
            senderUid = PETER_PROFILE,
            authorKind = "HUMAN",
            body = "Hello",
            deliveryState = if (serverCreatedAt == null) {
                RemoteMessageDeliveryState.PENDING
            } else {
                RemoteMessageDeliveryState.SENT
            },
            clientCreatedAt = FixedClock.now(),
            serverCreatedAt = serverCreatedAt,
            failureReason = null,
        )

    private class RecordingSessionResource : RemoteAccountSessionResource {
        var wasCancelled = false
            private set

        override fun cancel() {
            wasCancelled = true
        }
    }

    private object FixedClock : SynapseClock {
        override fun now(): Instant = Instant.parse("2026-07-13T04:00:00Z")
    }

    private companion object {
        val PETER_ACCOUNT = RemoteAccountUid("peter-uid")
        val TRISH_ACCOUNT = RemoteAccountUid("trish-uid")
        val PETER_PROFILE = RemoteProfileUid("peter-uid")
        val TRISH_PROFILE = RemoteProfileUid("trish-uid")
        val ROOM_ID = RemoteRoomId("direct-room")
    }
}
