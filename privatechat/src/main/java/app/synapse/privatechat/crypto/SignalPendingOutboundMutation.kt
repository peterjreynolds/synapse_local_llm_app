package app.synapse.privatechat.crypto

import java.time.Instant
import java.util.UUID

data class SignalPendingOutboundMutationKey(
    val accountId: UUID,
    val transportDeviceId: UUID,
    val clientMutationId: UUID,
)

/**
 * Opaque encrypted-transport request committed in the same snapshot as its sender ratchet advance.
 * The request must contain ciphertext and routing metadata only, never message plaintext.
 */
class StoredSignalPendingOutboundMutation private constructor(
    val key: SignalPendingOutboundMutationKey,
    operationDigest: ByteArray,
    opaqueRequest: ByteArray,
    peerRecipients: List<SignalDeviceAddress>,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    private val immutableOperationDigest = operationDigest.copyOf()
    private val immutableOpaqueRequest = opaqueRequest.copyOf()
    private val immutablePeerRecipients = peerRecipients.toList()

    val operationDigest: ByteArray
        get() = immutableOperationDigest.copyOf()

    val opaqueRequest: ByteArray
        get() = immutableOpaqueRequest.copyOf()

    val peerRecipients: List<SignalDeviceAddress>
        get() = immutablePeerRecipients.toList()

    init {
        require(key.accountId != NIL_UUID && key.transportDeviceId != NIL_UUID && key.clientMutationId != NIL_UUID) {
            "Pending outbound mutation identifiers must be non-zero"
        }
        require(operationDigest.size == OPERATION_DIGEST_BYTES) {
            "Pending outbound mutation digest size is invalid"
        }
        require(opaqueRequest.isNotEmpty() && opaqueRequest.size <= MAX_OPAQUE_REQUEST_BYTES) {
            "Pending outbound mutation request size is invalid"
        }
        require(expiresAt.isAfter(createdAt) && !expiresAt.isAfter(createdAt.plusSeconds(MAX_LIFETIME_SECONDS))) {
            "Pending outbound mutation lifetime is invalid"
        }
        require(peerRecipients.size <= MAX_PEER_RECIPIENTS) {
            "Pending outbound mutation has too many peer recipients"
        }
        require(
            peerRecipients.none { recipient ->
                recipient.accountId == key.accountId && recipient.transportDeviceId == key.transportDeviceId
            },
        ) {
            "Pending outbound mutation peer recipients must not contain the local device"
        }
        require(peerRecipients.map(SignalDeviceAddress::transportDeviceId).distinct().size == peerRecipients.size) {
            "Pending outbound mutation peer transport devices must be unique"
        }
        require(
            peerRecipients.map { recipient -> recipient.accountId to recipient.protocolDeviceId }.distinct().size ==
                peerRecipients.size,
        ) {
            "Pending outbound mutation peer Signal addresses must be unique"
        }
    }

    internal fun copyForStorage(): StoredSignalPendingOutboundMutation =
        create(
            key = key,
            operationDigest = immutableOperationDigest,
            opaqueRequest = immutableOpaqueRequest,
            peerRecipients = immutablePeerRecipients,
            createdAt = createdAt,
            expiresAt = expiresAt,
        )

    internal fun hasSameContentAs(other: StoredSignalPendingOutboundMutation): Boolean =
        key == other.key &&
            immutableOperationDigest.contentEquals(other.immutableOperationDigest) &&
            immutableOpaqueRequest.contentEquals(other.immutableOpaqueRequest) &&
            immutablePeerRecipients == other.immutablePeerRecipients &&
            createdAt == other.createdAt &&
            expiresAt == other.expiresAt

    companion object {
        const val OPERATION_DIGEST_BYTES: Int = 32
        const val MAX_OPAQUE_REQUEST_BYTES: Int = 12 * 1_024 * 1_024
        const val MAX_LIFETIME_SECONDS: Long = 24 * 60 * 60
        const val MAX_PEER_RECIPIENTS: Int = 128

        fun create(
            key: SignalPendingOutboundMutationKey,
            operationDigest: ByteArray,
            opaqueRequest: ByteArray,
            peerRecipients: List<SignalDeviceAddress>,
            createdAt: Instant,
            expiresAt: Instant,
        ): StoredSignalPendingOutboundMutation =
            StoredSignalPendingOutboundMutation(
                key = key,
                operationDigest = operationDigest,
                opaqueRequest = opaqueRequest,
                peerRecipients = peerRecipients,
                createdAt = createdAt,
                expiresAt = expiresAt,
            )
    }
}

private val NIL_UUID = UUID(0L, 0L)
