package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAiProvenance
import app.synapse.localllm.domain.remote.RemoteAssistantAvailability
import app.synapse.localllm.domain.remote.RemoteAssistantConversationCatalog
import app.synapse.localllm.domain.remote.RemoteAssistantConversationEndpoint
import app.synapse.localllm.domain.remote.RemoteAssistantConversationGateway
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteIdempotencyKey
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteMessageSendReceipt
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.SendRemoteMessageCommand
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.tasks.await

class FirebaseCinderConversationGateway internal constructor(
    private val currentFirebaseUid: () -> String?,
    private val callableTransport: CinderCallableTransport,
    private val sessionController: RemoteAccountSessionController,
    private val currentTimeMillis: () -> Long,
    private val availabilityPollMillis: Long,
    private val messagePollMillis: Long,
) : RemoteAssistantConversationGateway {
    constructor(
        firebaseAuth: FirebaseAuth,
        firebaseFunctions: FirebaseFunctions,
        sessionController: RemoteAccountSessionController,
    ) : this(
        currentFirebaseUid = { firebaseAuth.currentUser?.uid },
        callableTransport = FirebaseCinderCallableTransport(firebaseFunctions),
        sessionController = sessionController,
        currentTimeMillis = System::currentTimeMillis,
        availabilityPollMillis = CINDER_AVAILABILITY_POLL_MILLIS,
        messagePollMillis = CINDER_MESSAGE_POLL_MILLIS,
    )

    @Volatile
    private var lastAvailability: RemoteAssistantAvailability = CINDER_CHECKING_AVAILABILITY
    private val availableUntilMillis = AtomicLong(0L)

    init {
        require(availabilityPollMillis > 0L && messagePollMillis > 0L) {
            "Cinder polling intervals must be positive."
        }
    }

    override fun availability(endpoint: RemoteAssistantConversationEndpoint): RemoteAssistantAvailability {
        requireCinderEndpoint(endpoint)
        val availability = lastAvailability
        return if (
            availability == RemoteAssistantAvailability.Available &&
            availableUntilMillis.get() > currentTimeMillis()
        ) {
            availability
        } else if (availability == RemoteAssistantAvailability.Available) {
            CINDER_OFFLINE_AVAILABILITY.also { lastAvailability = it }
        } else {
            availability
        }
    }

    override fun observeAvailability(
        accountUid: RemoteAccountUid,
        endpoint: RemoteAssistantConversationEndpoint,
    ): Flow<RemoteAssistantAvailability> = flow {
        requireCinderEndpoint(endpoint)
        while (currentCoroutineContext().isActive) {
            val observedAvailability = try {
                requireAuthenticatedUid(accountUid)
                val receipt = callableTransport.call(CINDER_AVAILABILITY_CALLABLE, emptyMap())
                    .requireCinderMap("availability")
                    .toCinderAvailabilityReceipt()
                availableUntilMillis.set(receipt.availableUntilMillis ?: 0L)
                if (receipt.available) {
                    RemoteAssistantAvailability.Available
                } else {
                    CINDER_OFFLINE_AVAILABILITY
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                availableUntilMillis.set(0L)
                CINDER_STATUS_UNAVAILABLE
            }
            lastAvailability = observedAvailability
            emit(observedAvailability)
            delay(availabilityPollMillis)
        }
    }.distinctUntilChanged()

    override fun observeMessages(
        accountUid: RemoteAccountUid,
        endpoint: RemoteAssistantConversationEndpoint,
    ): Flow<List<RemoteCachedMessage>> = flow {
        requireCinderEndpoint(endpoint)
        val messagesById = linkedMapOf<RemoteMessageId, RemoteCachedMessage>()
        var nextSequence = 0L
        while (currentCoroutineContext().isActive) {
            requireAuthenticatedUid(accountUid)
            val page = try {
                callableTransport.call(
                    CINDER_SYNC_CALLABLE,
                    mapOf(
                        "afterSequence" to nextSequence,
                        "limit" to CINDER_SYNC_PAGE_SIZE,
                    ),
                ).requireCinderMap("message sync").toCinderMessageSyncPage(accountUid, endpoint, nextSequence)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: RemoteChatException) {
                throw exception
            } catch (exception: Exception) {
                throw exception.toRemoteChatFailure("synchronize the Cinder conversation")
            }
            page.messages.forEach { message ->
                val existing = messagesById[message.messageId]
                if (existing != null && existing != message) {
                    throw RemoteChatException("Cinder returned conflicting message history.")
                }
                messagesById[message.messageId] = message
            }
            nextSequence = page.nextSequence
            emit(messagesById.values.sortedBy { message -> requireNotNull(message.serverSequence) })
            if (!page.hasMore) delay(messagePollMillis)
        }
    }.distinctUntilChanged()

    override suspend fun sendMessage(
        endpoint: RemoteAssistantConversationEndpoint,
        command: SendRemoteMessageCommand,
    ): RemoteMessageSendReceipt {
        requireCinderEndpoint(endpoint)
        val message = command.message
        requireAuthenticatedUid(message.accountUid)
        require(message.roomId == endpoint.roomId) {
            "Assistant message command does not match the selected endpoint."
        }
        require(message.senderUid.raw == message.accountUid.raw && message.authorKind == HUMAN_AUTHOR_KIND) {
            "Only the authenticated human account can submit a Cinder message."
        }
        require(message.messageId.raw == message.idempotencyKey.raw) {
            "Cinder message ID and idempotency key must match."
        }
        require(message.attachments.isEmpty()) {
            "Cinder attachments are not supported by this authenticated transport yet."
        }
        require(message.replyToMessageId == null) {
            "Cinder replies are not supported by this authenticated transport yet."
        }
        val normalizedBody = message.body.trim()
        require(normalizedBody.isNotEmpty() && normalizedBody.length <= CINDER_MESSAGE_BODY_LIMIT) {
            "Cinder messages must contain 1-$CINDER_MESSAGE_BODY_LIMIT characters."
        }
        val currentAvailability = availability(endpoint)
        if (currentAvailability is RemoteAssistantAvailability.Unavailable) {
            throw RemoteChatException(currentAvailability.userMessage)
        }
        val receipt = try {
            callableTransport.call(
                CINDER_SUBMIT_CALLABLE,
                mapOf(
                    "assistantId" to endpoint.assistantId.raw,
                    "attachmentIds" to emptyList<String>(),
                    "body" to normalizedBody,
                    "clientCreatedAtMillis" to message.clientCreatedAt.toEpochMilli(),
                    "idempotencyKey" to message.idempotencyKey.raw,
                    "messageId" to message.messageId.raw,
                    "replyToMessageId" to null,
                    "roomId" to endpoint.roomId.raw,
                ),
            ).requireCinderMap("submission")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: FirebaseFunctionsException) {
            if (exception.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION) {
                availableUntilMillis.set(0L)
                lastAvailability = CINDER_OFFLINE_AVAILABILITY
                throw RemoteChatException(CINDER_OFFLINE_AVAILABILITY.userMessage, exception)
            }
            throw exception.toRemoteChatFailure("send the Cinder message")
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("send the Cinder message")
        }
        receipt.requireCinderSubmissionReceipt(endpoint, message)
        return RemoteMessageSendReceipt(message.accountUid, endpoint.roomId, message.messageId)
    }

    private fun requireAuthenticatedUid(accountUid: RemoteAccountUid) {
        sessionController.requireActiveToken(accountUid)
        if (currentFirebaseUid() != accountUid.raw) {
            throw RemoteChatException("The Firebase account session changed. Sign in again.")
        }
    }
}

internal interface CinderCallableTransport {
    suspend fun call(
        callableName: String,
        payload: Map<String, Any?>,
    ): Any?
}

private class FirebaseCinderCallableTransport(
    private val firebaseFunctions: FirebaseFunctions,
) : CinderCallableTransport {
    override suspend fun call(
        callableName: String,
        payload: Map<String, Any?>,
    ): Any? = firebaseFunctions.getHttpsCallable(callableName).call(payload).await().data
}

internal data class CinderAvailabilityReceipt(
    val available: Boolean,
    val availableUntilMillis: Long?,
)

internal data class CinderMessageSyncPage(
    val hasMore: Boolean,
    val messages: List<RemoteCachedMessage>,
    val nextSequence: Long,
)

internal fun Map<*, *>.toCinderAvailabilityReceipt(): CinderAvailabilityReceipt {
    val available = requireCinderBoolean("available")
    val availableUntilMillis = optionalCinderLong("availableUntilMillis")
    val checkedAtMillis = requireCinderLong("checkedAtMillis")
    if (
        requireCinderLong("protocolVersion") != CINDER_PROTOCOL_VERSION ||
        (available && (availableUntilMillis == null || availableUntilMillis <= checkedAtMillis)) ||
        (!available && availableUntilMillis != null && availableUntilMillis < 0L)
    ) {
        malformedCinderResponse()
    }
    return CinderAvailabilityReceipt(available, availableUntilMillis)
}

internal fun Map<*, *>.toCinderMessageSyncPage(
    accountUid: RemoteAccountUid,
    endpoint: RemoteAssistantConversationEndpoint,
    afterSequence: Long,
): CinderMessageSyncPage {
    requireCinderEndpoint(endpoint)
    val hasMore = requireCinderBoolean("hasMore")
    val messages = (this["messages"] as? List<*>)?.map { rawMessage ->
        rawMessage.requireCinderMap("message").toCinderMessage(accountUid, endpoint)
    } ?: malformedCinderResponse()
    if (
        messages.size > CINDER_SYNC_PAGE_SIZE ||
        messages.any { message -> requireNotNull(message.serverSequence) <= afterSequence } ||
        messages.zipWithNext().any { (first, second) ->
            requireNotNull(first.serverSequence) >= requireNotNull(second.serverSequence)
        }
    ) {
        malformedCinderResponse()
    }
    val nextSequence = requireCinderLong("nextSequence")
    val expectedNextSequence = messages.lastOrNull()?.serverSequence ?: afterSequence
    if (nextSequence != expectedNextSequence || (hasMore && messages.isEmpty())) malformedCinderResponse()
    return CinderMessageSyncPage(hasMore, messages, nextSequence)
}

private fun Map<*, *>.toCinderMessage(
    accountUid: RemoteAccountUid,
    endpoint: RemoteAssistantConversationEndpoint,
): RemoteCachedMessage {
    val messageId = requireCinderString("messageId")
    val idempotencyKey = requireCinderString("idempotencyKey")
    val authorKind = requireCinderString("authorKind")
    val senderUid = requireCinderString("senderUid")
    val assistantId = requireCinderString("assistantId")
    val roomId = requireCinderString("roomId")
    val aiParticipantId = optionalCinderString("aiParticipantId")
    val aiProvenance = optionalCinderString("aiProvenance")
    val aiProvider = optionalCinderString("aiProvider")
    val sourceMessageId = optionalCinderString("sourceMessageId")
    val humanAttributionIsValid = authorKind == HUMAN_AUTHOR_KIND &&
        senderUid == accountUid.raw &&
        aiParticipantId == null &&
        aiProvenance == null &&
        aiProvider == null &&
        sourceMessageId == null
    val remoteAttributionIsValid = authorKind == REMOTE_AI_AUTHOR_KIND &&
        senderUid == endpoint.participantId.raw &&
        aiParticipantId == endpoint.participantId.raw &&
        aiProvenance == RemoteAiProvenance.REMOTE_HOSTED.name &&
        aiProvider == CINDER_PROVIDER &&
        sourceMessageId != null
    if (
        assistantId != endpoint.assistantId.raw ||
        roomId != endpoint.roomId.raw ||
        (!humanAttributionIsValid && !remoteAttributionIsValid)
    ) {
        malformedCinderResponse()
    }
    val body = requireCinderString("body")
    if (body.length > CINDER_MESSAGE_BODY_LIMIT) malformedCinderResponse()
    return RemoteCachedMessage(
        accountUid = accountUid,
        roomId = endpoint.roomId,
        messageId = RemoteMessageId(messageId),
        idempotencyKey = RemoteIdempotencyKey(idempotencyKey),
        senderUid = RemoteProfileUid(senderUid),
        authorKind = authorKind,
        body = body,
        replyToMessageId = sourceMessageId?.let(::RemoteMessageId),
        editedAt = null,
        deletedAt = null,
        revision = requireCinderPositiveLong("revision"),
        reactionCounts = emptyMap(),
        deliveredToCount = 0,
        readByCount = 0,
        deliveryState = RemoteMessageDeliveryState.SENT,
        clientCreatedAt = Instant.ofEpochMilli(requireCinderLong("clientCreatedAtMillis")),
        serverCreatedAt = Instant.ofEpochMilli(requireCinderLong("createdAtMillis")),
        failureReason = null,
        aiParticipantId = aiParticipantId,
        aiProvenance = aiProvenance?.let(RemoteAiProvenance::valueOf),
        serverSequence = requireCinderPositiveLong("sequence"),
    )
}

private fun Map<*, *>.requireCinderSubmissionReceipt(
    endpoint: RemoteAssistantConversationEndpoint,
    message: RemoteCachedMessage,
) {
    val acceptance = requireCinderString("acceptance")
    if (
        acceptance != "ACCEPTED" && acceptance != "ALREADY_ACCEPTED" ||
        requireCinderString("messageId") != message.messageId.raw ||
        requireCinderString("roomId") != endpoint.roomId.raw ||
        requireCinderPositiveLong("revision") != 1L ||
        requireCinderPositiveLong("sequence") < 1L
    ) {
        malformedCinderResponse()
    }
}

private fun requireCinderEndpoint(endpoint: RemoteAssistantConversationEndpoint) {
    if (endpoint != RemoteAssistantConversationCatalog.cinder) {
        throw RemoteChatException("The selected remote assistant is not supported.")
    }
}

private fun Any?.requireCinderMap(owner: String): Map<*, *> =
    this as? Map<*, *> ?: throw RemoteChatException("Firebase returned malformed Cinder $owner state.")

private fun Map<*, *>.requireCinderString(fieldName: String): String =
    (this[fieldName] as? String)?.takeIf(String::isNotBlank) ?: malformedCinderResponse()

private fun Map<*, *>.optionalCinderString(fieldName: String): String? = when (val value = this[fieldName]) {
    null -> null
    is String -> value.takeIf(String::isNotBlank) ?: malformedCinderResponse()
    else -> malformedCinderResponse()
}

private fun Map<*, *>.requireCinderBoolean(fieldName: String): Boolean =
    this[fieldName] as? Boolean ?: malformedCinderResponse()

private fun Map<*, *>.requireCinderLong(fieldName: String): Long =
    (this[fieldName] as? Number)?.toDouble()?.let { number ->
        number.takeIf(Double::isFinite)?.toLong()?.takeIf { integer ->
            integer >= 0L && integer.toDouble() == number
        }
    } ?: malformedCinderResponse()

private fun Map<*, *>.requireCinderPositiveLong(fieldName: String): Long =
    requireCinderLong(fieldName).takeIf { value -> value >= 1L } ?: malformedCinderResponse()

private fun Map<*, *>.optionalCinderLong(fieldName: String): Long? =
    if (this[fieldName] == null) null else requireCinderLong(fieldName)

private fun malformedCinderResponse(): Nothing =
    throw RemoteChatException("Firebase returned malformed Cinder state.")

private const val CINDER_AVAILABILITY_CALLABLE = "getCinderAvailability"
private const val CINDER_AVAILABILITY_POLL_MILLIS = 30_000L
private const val CINDER_MESSAGE_BODY_LIMIT = 4_000
private const val CINDER_MESSAGE_POLL_MILLIS = 5_000L
private const val CINDER_PROTOCOL_VERSION = 1L
private const val CINDER_PROVIDER = "OPENCLAW_CINDER"
private const val CINDER_SUBMIT_CALLABLE = "submitCinderMessage"
private const val CINDER_SYNC_CALLABLE = "syncCinderMessages"
private const val CINDER_SYNC_PAGE_SIZE = 100
private const val HUMAN_AUTHOR_KIND = "HUMAN"
private const val REMOTE_AI_AUTHOR_KIND = "REMOTE_AI"
private val CINDER_CHECKING_AVAILABILITY = RemoteAssistantAvailability.Unavailable(
    "Checking whether Cinder is connected…",
)
private val CINDER_OFFLINE_AVAILABILITY = RemoteAssistantAvailability.Unavailable(
    "Cinder is offline right now. Your draft will stay here.",
)
private val CINDER_STATUS_UNAVAILABLE = RemoteAssistantAvailability.Unavailable(
    "Cinder status could not be checked. Check your connection and try again.",
)
