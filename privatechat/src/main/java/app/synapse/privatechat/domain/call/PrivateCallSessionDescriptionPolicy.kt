package app.synapse.privatechat.domain.call

internal object PrivateCallSessionDescriptionPolicy {
    fun requireEncryptedMedia(
        sdp: String,
        mediaKind: PrivateCallMediaKind,
    ) {
        require(sdp.length in 1..65_536 && '\u0000' !in sdp) { "Call description is invalid." }
        val lines =
            sdp
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()
        require(lines.firstOrNull() == "v=0") { "Call description version is unsupported." }
        require(lines.none { it.startsWith("a=crypto:") }) { "Calls require DTLS-SRTP." }
        lines.filter { it.startsWith("a=fingerprint:") }.forEach(::requireFingerprint)
        val sessionLines = lines.takeWhile { !it.startsWith("m=") }
        val sessionFingerprint = sessionLines.firstOrNull { it.startsWith("a=fingerprint:") }
        if (sessionFingerprint != null) requireFingerprint(sessionFingerprint)
        val mediaSections = mutableListOf<MutableList<String>>()
        lines.forEach { line ->
            if (line.startsWith("m=")) mediaSections.add(mutableListOf())
            mediaSections.lastOrNull()?.add(line)
        }
        require(mediaSections.isNotEmpty()) { "Call description contains no media." }
        var activeAudio = false
        mediaSections.forEach { section ->
            val header = section.first().split(' ')
            require(header.size >= 4 && header[0] in setOf("m=audio", "m=video")) { "Call media is unsupported." }
            require(header[2] == "UDP/TLS/RTP/SAVPF") { "Unencrypted call media is forbidden." }
            if (header[0] == "m=video") require(mediaKind == PrivateCallMediaKind.VIDEO) { "Video requires explicit consent." }
            if (header[1] != "0") {
                if (header[0] == "m=audio") activeAudio = true
                val fingerprint = section.firstOrNull { it.startsWith("a=fingerprint:") } ?: sessionFingerprint
                requireFingerprint(fingerprint)
                require(
                    section.any { it in DTLS_ROLES } || sessionLines.any { it in DTLS_ROLES },
                ) {
                    "Call description has no DTLS role."
                }
            }
        }
        require(activeAudio) { "Call description has no active audio." }
    }

    fun requireValidCandidate(signal: PrivateCallMediaSignal.IceCandidate) {
        require(signal.candidate.length in 1..4_096 && signal.candidate.startsWith("candidate:")) { "Call route is invalid." }
        require(signal.candidate.none(Char::isISOControl)) { "Call route contains control characters." }
        require(signal.sdpMLineIndex in 0..7) { "Call route media index is invalid." }
        require(signal.sdpMid == null || (signal.sdpMid.length in 1..64 && signal.sdpMid.none(Char::isISOControl))) {
            "Call route media identifier is invalid."
        }
    }

    private fun requireFingerprint(fingerprint: String?) {
        require(fingerprint != null && fingerprint.matches(SHA256_FINGERPRINT)) { "Call requires a SHA-256 DTLS fingerprint." }
    }

    private val SHA256_FINGERPRINT = Regex("a=fingerprint:sha-256 [0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){31}")
    private val DTLS_ROLES = setOf("a=setup:actpass", "a=setup:active", "a=setup:passive")
}
