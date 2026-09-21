package app.synapse.privatechat.domain.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivateCallSessionDescriptionPolicyTest {
    @Test fun `accepts authenticated DTLS SRTP voice description`() {
        PrivateCallSessionDescriptionPolicy.requireEncryptedMedia(voiceSdp, PrivateCallMediaKind.VOICE)
    }

    @Test fun `rejects absent or weak DTLS fingerprints`() {
        listOf(
            voiceSdp.replace(fingerprint, ""),
            voiceSdp.replace("sha-256", "sha-1"),
            voiceSdp.replace(fingerprint, fingerprint.dropLast(3)),
        ).forEach { sdp ->
            assertThrows(IllegalArgumentException::class.java) {
                PrivateCallSessionDescriptionPolicy.requireEncryptedMedia(sdp, PrivateCallMediaKind.VOICE)
            }
        }
    }

    @Test fun `rejects plaintext transport and SDES keying`() {
        listOf(voiceSdp.replace("UDP/TLS/RTP/SAVPF", "RTP/AVP"), voiceSdp + "\r\na=crypto:1 unsafe\r\n")
            .forEach { sdp ->
                assertThrows(IllegalArgumentException::class.java) {
                    PrivateCallSessionDescriptionPolicy.requireEncryptedMedia(sdp, PrivateCallMediaKind.VOICE)
                }
            }
    }

    @Test fun `video cannot be negotiated inside voice consent`() {
        val videoSdp = voiceSdp + "\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n$fingerprint\r\na=setup:actpass\r\n"
        assertThrows(IllegalArgumentException::class.java) {
            PrivateCallSessionDescriptionPolicy.requireEncryptedMedia(videoSdp, PrivateCallMediaKind.VOICE)
        }
        PrivateCallSessionDescriptionPolicy.requireEncryptedMedia(videoSdp, PrivateCallMediaKind.VIDEO)
    }

    @Test fun `session fingerprint can bind media but contradictory fingerprint fails`() {
        val sessionFingerprint = "v=0\r\n$fingerprint\r\n" + voiceSdp.removePrefix("v=0\r\n").replace("$fingerprint\r\n", "")
        PrivateCallSessionDescriptionPolicy.requireEncryptedMedia(sessionFingerprint, PrivateCallMediaKind.VOICE)
        assertThrows(IllegalArgumentException::class.java) {
            PrivateCallSessionDescriptionPolicy.requireEncryptedMedia(
                voiceSdp + "\r\na=fingerprint:sha-1 11\r\n",
                PrivateCallMediaKind.VOICE,
            )
        }
    }

    @Test fun `ICE is bounded and excludes injected line breaks`() {
        val candidate = PrivateCallMediaSignal.IceCandidate("0", 0, "candidate:1 1 udp 1 192.0.2.1 12345 typ host")
        PrivateCallSessionDescriptionPolicy.requireValidCandidate(candidate)
        listOf(
            candidate.copy(candidate = candidate.candidate + "\r\na=bad"),
            candidate.copy(sdpMLineIndex = -1),
            candidate.copy(sdpMid = "a".repeat(65)),
        ).forEach { malformed ->
            assertThrows(IllegalArgumentException::class.java) { PrivateCallSessionDescriptionPolicy.requireValidCandidate(malformed) }
        }
    }

    @Test fun `signal diagnostic rendering excludes addresses and session descriptions`() {
        assertEquals("Offer(sdp=[REDACTED])", PrivateCallMediaSignal.Offer(voiceSdp).toString())
        assertEquals("Answer(sdp=[REDACTED])", PrivateCallMediaSignal.Answer(voiceSdp).toString())
        assertFalse(PrivateCallMediaSignal.IceCandidate("0", 0, "192.0.2.1").toString().contains("192.0.2.1"))
    }

    private val fingerprint = "a=fingerprint:sha-256 " + List(32) { "A1" }.joinToString(":")
    private val voiceSdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n$fingerprint\r\na=setup:actpass\r\n"
}
