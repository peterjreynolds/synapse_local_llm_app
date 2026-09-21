package app.synapse.privatechat.data.call

import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.domain.call.PrivateCallEndReason
import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.call.PrivateCallMediaSignal
import app.synapse.privatechat.domain.call.PrivateCallSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PrivateCallPayloadCodecTest {
    @Test
    fun roundTripsEveryKindWithAllRoutingBindings() {
        val signals =
            listOf(
                PrivateCallSignal.Media(PrivateCallMediaSignal.Offer(CALL_TEST_SDP)),
                PrivateCallSignal.Media(PrivateCallMediaSignal.Answer(CALL_TEST_SDP)),
                PrivateCallSignal.Media(PrivateCallMediaSignal.IceCandidate("audio", 0, "candidate:1 1 udp 123 192.0.2.1 5000 typ host")),
                PrivateCallSignal.End(PrivateCallEndReason.ENDED),
            )
        signals.forEach { signal ->
            val payload = callTestPayload().copy(signal = signal)
            assertEquals(payload, PrivateCallPayloadCodec.decode(PrivateCallPayloadCodec.encode(payload)))
        }
    }

    @Test
    fun rejectsTruncationTrailingBytesAndUnknownDomain() {
        val encoded = PrivateCallPayloadCodec.encode(callTestPayload())
        listOf(encoded.copyOf(5), encoded + byteArrayOf(0), "chat message".encodeToByteArray()).forEach { malformed ->
            assertThrows(Exception::class.java) { PrivateCallPayloadCodec.decode(malformed) }
        }
    }

    @Test
    fun rejectsUnencryptedOrOversizedSdpAndInvalidSequence() {
        val payload = callTestPayload()
        listOf(CALL_TEST_SDP.replace("UDP/TLS/RTP/SAVPF", "RTP/AVP"), CALL_TEST_SDP + "x".repeat(65_536)).forEach { sdp ->
            assertThrows(IllegalArgumentException::class.java) {
                PrivateCallPayloadCodec.encode(payload.copy(signal = PrivateCallSignal.Media(PrivateCallMediaSignal.Offer(sdp))))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { PrivateCallPayloadCodec.encode(payload.copy(sequence = -1)) }
        assertThrows(IllegalArgumentException::class.java) { PrivateCallPayloadCodec.encode(payload.copy(sequence = 4_097)) }
    }

    @Test
    fun rejectsSelfSignalsAndNeverPrintsSdp() {
        val payload = callTestPayload()
        assertThrows(IllegalArgumentException::class.java) { PrivateCallPayloadCodec.encode(payload.copy(recipient = payload.sender)) }
        assertFalse(payload.toString().contains("fingerprint"))
        assertFalse(payload.signal.toString().contains("fingerprint"))
    }
}

internal val CALL_TEST_TIME: Instant = Instant.parse("2026-09-20T06:00:00Z")
internal val CALL_TEST_SDP: String =
    "v=0\r\na=fingerprint:sha-256 ${List(32) { "AB" }.joinToString(":")}\r\na=setup:actpass\r\n" +
        "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=sendrecv\r\n"

internal fun callTestAddress(): SignalDeviceAddress = SignalDeviceAddress(UUID.randomUUID(), UUID.randomUUID(), SignalDeviceId.fromWire(1))

internal fun callTestPayload(): PrivateCallPayload =
    PrivateCallPayload(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        callTestAddress(),
        callTestAddress(),
        0,
        CALL_TEST_TIME.plusSeconds(60),
        PrivateCallMediaKind.VOICE,
        PrivateCallSignal.Media(PrivateCallMediaSignal.Offer(CALL_TEST_SDP)),
    )
