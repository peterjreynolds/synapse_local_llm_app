package app.synapse.privatechat.data.session

import app.synapse.privatechat.crypto.SignalDeviceId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

internal data class PrivateSessionVaultState(
    val installationId: PrivateInstallationId,
    val registeredSession: RegisteredPrivateAccountSession?,
) {
    init {
        require(registeredSession == null || registeredSession.installationId == installationId) {
            "Registered session belongs to a different installation"
        }
    }

    fun copyForStorage(): PrivateSessionVaultState =
        PrivateSessionVaultState(
            installationId = installationId,
            registeredSession = registeredSession?.copyForStorage(),
        )
}

internal data class PrivateSessionVaultDecodeResult(
    val state: PrivateSessionVaultState,
    val migrationRequired: Boolean,
)

internal object PrivateSessionVaultCodec {
    internal const val MAX_PLAINTEXT_BYTES = 20 * 1_024
    private const val MAGIC = 0x53504131
    private const val VERSION = 2
    private const val LEGACY_VERSION_WITHOUT_USERNAME = 1
    private const val MAX_ACCESS_TOKEN_BYTES = 8_192
    private const val MAX_REFRESH_TOKEN_BYTES = 8_192
    private const val MAX_AUTHENTICATION_USERNAME_BYTES = 32
    private const val MAX_DISPLAY_NAME_BYTES = 512

    fun encode(state: PrivateSessionVaultState): ByteArray {
        val output = PrivateSessionBoundedOutputStream(MAX_PLAINTEXT_BYTES)
        DataOutputStream(output).use { encoded ->
            encoded.writeInt(MAGIC)
            encoded.writeInt(VERSION)
            encoded.writeUuid(state.installationId.uuid)
            encoded.writeBoolean(state.registeredSession != null)
            state.registeredSession?.let { session ->
                encoded.writeUuid(session.accountId)
                encoded.writeInt(session.signalDeviceId.raw)
                encoded.writeLong(session.expiresAt.epochSecond)
                encoded.writeBoundedUtf8(session.accessTokenForAuthorization(), MAX_ACCESS_TOKEN_BYTES)
                encoded.writeBoundedUtf8(session.refreshTokenForRenewal(), MAX_REFRESH_TOKEN_BYTES)
                encoded.writeBoundedUtf8(session.authenticationUsername, MAX_AUTHENTICATION_USERNAME_BYTES)
                encoded.writeBoundedUtf8(session.pseudonymousDisplayName, MAX_DISPLAY_NAME_BYTES)
            }
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): PrivateSessionVaultState = decodeVersioned(bytes).state

    fun decodeVersioned(bytes: ByteArray): PrivateSessionVaultDecodeResult {
        if (bytes.size > MAX_PLAINTEXT_BYTES) corrupt("Private session state exceeds the size limit")
        try {
            val input = ByteArrayInputStream(bytes)
            val encoded = DataInputStream(input)
            if (encoded.readInt() != MAGIC) corrupt("Private session state header is invalid")
            val result =
                when (encoded.readInt()) {
                    VERSION -> PrivateSessionVaultDecodeResult(readCurrentState(encoded), migrationRequired = false)
                    LEGACY_VERSION_WITHOUT_USERNAME ->
                        PrivateSessionVaultDecodeResult(
                            state = readLegacyStateWithoutUsername(encoded),
                            migrationRequired = true,
                        )

                    else -> corrupt("Private session state version is unsupported")
                }
            if (input.available() != 0) corrupt("Private session state contains trailing bytes")
            return result
        } catch (error: PrivateSessionStateUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw PrivateSessionStateUnavailableException("Private session state is malformed", error)
        }
    }

    private fun readCurrentState(encoded: DataInputStream): PrivateSessionVaultState {
        val installationId = PrivateInstallationId.fromPersistence(encoded.readUuid())
        val registeredSession =
            if (encoded.readBoolean()) {
                RegisteredPrivateAccountSession.fromPersistence(
                    accountId = encoded.readUuid(),
                    installationId = installationId,
                    signalDeviceId = SignalDeviceId.fromWire(encoded.readInt()),
                    expiresAt = Instant.ofEpochSecond(encoded.readLong()),
                    accessToken = encoded.readBoundedUtf8(MAX_ACCESS_TOKEN_BYTES),
                    refreshToken = encoded.readBoundedUtf8(MAX_REFRESH_TOKEN_BYTES),
                    authenticationUsername = encoded.readBoundedUtf8(MAX_AUTHENTICATION_USERNAME_BYTES),
                    pseudonymousDisplayName = encoded.readBoundedUtf8(MAX_DISPLAY_NAME_BYTES),
                )
            } else {
                null
            }
        return PrivateSessionVaultState(installationId, registeredSession)
    }

    private fun readLegacyStateWithoutUsername(encoded: DataInputStream): PrivateSessionVaultState {
        val installationId = PrivateInstallationId.fromPersistence(encoded.readUuid())
        if (encoded.readBoolean()) {
            RegisteredPrivateAccountSession.validateLegacyPersistence(
                accountId = encoded.readUuid(),
                installationId = installationId,
                signalDeviceId = SignalDeviceId.fromWire(encoded.readInt()),
                expiresAt = Instant.ofEpochSecond(encoded.readLong()),
                accessToken = encoded.readBoundedUtf8(MAX_ACCESS_TOKEN_BYTES),
                refreshToken = encoded.readBoundedUtf8(MAX_REFRESH_TOKEN_BYTES),
                pseudonymousDisplayName = encoded.readBoundedUtf8(MAX_DISPLAY_NAME_BYTES),
            )
        }
        return PrivateSessionVaultState(installationId, registeredSession = null)
    }

    private fun DataOutputStream.writeUuid(uuid: UUID) {
        writeLong(uuid.mostSignificantBits)
        writeLong(uuid.leastSignificantBits)
    }

    private fun DataInputStream.readUuid(): UUID = UUID(readLong(), readLong())

    private fun DataOutputStream.writeBoundedUtf8(
        text: String,
        maximumBytes: Int,
    ) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        try {
            require(bytes.isNotEmpty() && bytes.size <= maximumBytes) {
                "Private session text exceeds its size limit"
            }
            writeInt(bytes.size)
            write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataInputStream.readBoundedUtf8(maximumBytes: Int): String {
        val size = readInt()
        if (size !in 1..maximumBytes) corrupt("Private session text size is invalid")
        val bytes = ByteArray(size).also(::readFully)
        return try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } finally {
            bytes.fill(0)
        }
    }

    private fun corrupt(message: String): Nothing = throw PrivateSessionStateUnavailableException(message)
}

private class PrivateSessionBoundedOutputStream(
    private val maximumBytes: Int,
) : ByteArrayOutputStream() {
    @Synchronized
    override fun write(value: Int) {
        requireCapacityFor(1)
        super.write(value)
    }

    @Synchronized
    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        requireCapacityFor(length)
        super.write(bytes, offset, length)
    }

    private fun requireCapacityFor(additionalBytes: Int) {
        require(additionalBytes >= 0 && count <= maximumBytes - additionalBytes) {
            "Private session state exceeds the size limit"
        }
    }
}
