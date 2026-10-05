package app.synapse.privatechat.data.call

import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.domain.call.PrivateCallEndReason
import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.call.PrivateCallMediaSignal
import app.synapse.privatechat.domain.call.PrivateCallSessionDescriptionPolicy
import app.synapse.privatechat.domain.call.PrivateCallSignal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.UUID

internal data class PrivateCallPayload(
    val callId: UUID,
    val roomId: UUID,
    val membershipEpoch: Int,
    val sender: SignalDeviceAddress,
    val recipient: SignalDeviceAddress,
    val sequence: Int,
    val expiresAt: Instant,
    val mediaKind: PrivateCallMediaKind,
    val signal: PrivateCallSignal,
) {
    override fun toString(): String = "PrivateCallPayload([REDACTED])"
}

/** Versioned call-only domain separation; no payload is readable as a chat message. */
internal object PrivateCallPayloadCodec {
    fun encode(payload: PrivateCallPayload): ByteArray {
        validate(payload)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF("SYNAPSE_PRIVATE_CALL_V1")
                output.writeUuid(payload.callId)
                output.writeUuid(payload.roomId)
                output.writeInt(payload.membershipEpoch)
                output.writeAddress(payload.sender)
                output.writeAddress(payload.recipient)
                output.writeInt(payload.sequence)
                output.writeLong(payload.expiresAt.epochSecond)
                output.writeUTF(payload.mediaKind.name)
                when (val signal = payload.signal) {
                    is PrivateCallSignal.End -> {
                        output.writeUTF("END")
                        output.writeUTF(signal.reason.name)
                    }
                    is PrivateCallSignal.Media ->
                        when (val media = signal.mediaSignal) {
                            is PrivateCallMediaSignal.Offer -> {
                                output.writeUTF("OFFER")
                                output.writeUTF(media.sdp)
                            }
                            is PrivateCallMediaSignal.Answer -> {
                                output.writeUTF("ANSWER")
                                output.writeUTF(media.sdp)
                            }
                            is PrivateCallMediaSignal.IceCandidate -> {
                                output.writeUTF("ICE")
                                output.writeBoolean(media.sdpMid != null)
                                media.sdpMid?.let(output::writeUTF)
                                output.writeInt(media.sdpMLineIndex)
                                output.writeUTF(media.candidate)
                            }
                        }
                }
            }
            bytes.toByteArray().also { require(it.size <= MAXIMUM_CALL_PAYLOAD_BYTES) }
        }
    }

    fun decode(encoded: ByteArray): PrivateCallPayload {
        require(encoded.size in 1..MAXIMUM_CALL_PAYLOAD_BYTES) { "Call payload size is invalid" }
        return DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            require(input.readUTF() == "SYNAPSE_PRIVATE_CALL_V1") { "Call payload version is invalid" }
            val callId = input.readUuid()
            val roomId = input.readUuid()
            val epoch = input.readInt()
            val sender = input.readAddress()
            val recipient = input.readAddress()
            val sequence = input.readInt()
            val expiresAt = Instant.ofEpochSecond(input.readLong())
            val mediaKind = PrivateCallMediaKind.valueOf(input.readUTF())
            val signal =
                when (input.readUTF()) {
                    "OFFER" -> PrivateCallSignal.Media(PrivateCallMediaSignal.Offer(input.readUTF()))
                    "ANSWER" -> PrivateCallSignal.Media(PrivateCallMediaSignal.Answer(input.readUTF()))
                    "ICE" ->
                        PrivateCallSignal.Media(
                            PrivateCallMediaSignal.IceCandidate(
                                sdpMid =
                                    when (input.readUnsignedByte()) {
                                        0 -> null
                                        1 -> input.readUTF()
                                        else -> error("Call media identifier presence is malformed")
                                    },
                                sdpMLineIndex = input.readInt(),
                                candidate = input.readUTF(),
                            ),
                        )
                    "END" -> PrivateCallSignal.End(PrivateCallEndReason.valueOf(input.readUTF()))
                    else -> error("Call signal kind is unsupported")
                }
            require(input.available() == 0) { "Call payload has trailing bytes" }
            PrivateCallPayload(callId, roomId, epoch, sender, recipient, sequence, expiresAt, mediaKind, signal)
                .also(::validate)
        }
    }

    private fun validate(payload: PrivateCallPayload) {
        require(payload.callId != NIL_UUID && payload.roomId != NIL_UUID)
        require(payload.membershipEpoch > 0 && payload.sequence in 0..MAXIMUM_CALL_SEQUENCE)
        require(payload.sender.accountId != payload.recipient.accountId)
        require(payload.sender.transportDeviceId != payload.recipient.transportDeviceId)
        require(payload.expiresAt.nano == 0 && payload.expiresAt.epochSecond in 1..253_402_300_799L)
        when (val signal = payload.signal) {
            is PrivateCallSignal.End -> Unit
            is PrivateCallSignal.Media ->
                when (val media = signal.mediaSignal) {
                    is PrivateCallMediaSignal.Offer -> requireSecureSdp(media.sdp, payload.mediaKind)
                    is PrivateCallMediaSignal.Answer -> requireSecureSdp(media.sdp, payload.mediaKind)
                    is PrivateCallMediaSignal.IceCandidate -> PrivateCallSessionDescriptionPolicy.requireValidCandidate(media)
                }
        }
    }

    private fun requireSecureSdp(
        sdp: String,
        mediaKind: PrivateCallMediaKind,
    ) {
        require(sdp.toByteArray(Charsets.UTF_8).size in 1..48 * 1_024)
        require(sdp.none { it.isISOControl() && it !in "\r\n\t" })
        PrivateCallSessionDescriptionPolicy.requireEncryptedMedia(sdp, mediaKind)
    }
}

private fun DataOutputStream.writeUuid(uuid: UUID) {
    writeLong(uuid.mostSignificantBits)
    writeLong(uuid.leastSignificantBits)
}

private fun DataInputStream.readUuid(): UUID = UUID(readLong(), readLong())

private fun DataOutputStream.writeAddress(address: SignalDeviceAddress) {
    writeUuid(address.accountId)
    writeUuid(address.transportDeviceId)
    writeInt(address.protocolDeviceId.raw)
}

private fun DataInputStream.readAddress(): SignalDeviceAddress =
    SignalDeviceAddress(readUuid(), readUuid(), SignalDeviceId.fromWire(readInt()))

internal const val MAXIMUM_CALL_SEQUENCE = 4_096
private const val MAXIMUM_CALL_PAYLOAD_BYTES = 64 * 1_024
private val NIL_UUID = UUID(0L, 0L)
