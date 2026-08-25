package app.synapse.privatechat.data.account

import app.synapse.privatechat.data.session.PrivateInstallationId
import app.synapse.privatechat.domain.account.PrivateInvitationCode
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal object PrivateRegistrationRedemptionIdFactory {
    fun derive(
        installationId: PrivateInstallationId,
        invitationCode: PrivateInvitationCode,
    ): UUID {
        val invitationBytes = invitationCode.canonical.toByteArray(StandardCharsets.UTF_8)
        val digest =
            MessageDigest.getInstance("SHA-256").run {
                update(DOMAIN_SEPARATOR)
                update(uuidBytes(installationId.uuid))
                update(invitationBytes)
                digest()
            }
        invitationBytes.fill(0)
        val uuidBytes = digest.copyOfRange(0, 16)
        digest.fill(0)
        // RFC 9562 version 8 marks this as an application-defined, SHA-256-derived UUID.
        uuidBytes[6] = ((uuidBytes[6].toInt() and 0x0F) or 0x80).toByte()
        uuidBytes[8] = ((uuidBytes[8].toInt() and 0x3F) or 0x80).toByte()
        return ByteBuffer.wrap(uuidBytes).let { bytes -> UUID(bytes.long, bytes.long) }
    }

    private fun uuidBytes(uuid: UUID): ByteArray =
        ByteBuffer
            .allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
}

private val DOMAIN_SEPARATOR = "synapse.private/registration-redemption/v1\u0000".toByteArray(StandardCharsets.UTF_8)
