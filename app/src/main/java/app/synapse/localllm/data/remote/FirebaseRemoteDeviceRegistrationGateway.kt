package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RegisterRemoteDeviceInstallationCommand
import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteDeviceId
import app.synapse.localllm.domain.remote.RemoteDeviceMutation
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationGateway
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationReceipt
import app.synapse.localllm.domain.remote.RemoteRegisteredDevice
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import java.security.MessageDigest
import kotlinx.coroutines.tasks.await

class FirebaseRemoteDeviceRegistrationGateway(
    private val firebaseAuth: FirebaseAuth,
    private val firebaseInstallations: FirebaseInstallations,
    private val firebaseMessaging: FirebaseMessaging,
    private val firebaseFunctions: FirebaseFunctions,
    private val sessionController: RemoteAccountSessionController,
) : RemoteDeviceRegistrationGateway {
    override suspend fun listOwnDevices(
        accountUid: RemoteAccountUid,
    ): List<RemoteRegisteredDevice> {
        requireAuthenticatedUid(accountUid)
        val currentDeviceId = currentDeviceId()
        val response = try {
            firebaseFunctions.getHttpsCallable("listOwnDevices").call(emptyMap<String, Any>()).await().data
                as? Map<*, *> ?: malformedDeviceResponse()
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("load registered devices")
        }
        return parseRemoteRegisteredDevices(response, currentDeviceId)
    }

    override suspend fun registerCurrentDevice(
        accountUid: RemoteAccountUid,
    ): RemoteDeviceRegistrationReceipt {
        requireAuthenticatedUid(accountUid)
        val installationId = try {
            firebaseMessaging.register().await()
            firebaseInstallations.id.await()
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("prepare this device for notifications")
        }
        return registerRefreshedInstallation(
            RegisterRemoteDeviceInstallationCommand(accountUid, installationId),
        )
    }

    override suspend fun registerRefreshedInstallation(
        command: RegisterRemoteDeviceInstallationCommand,
    ): RemoteDeviceRegistrationReceipt {
        requireAuthenticatedUid(command.accountUid)
        validateFirebaseInstallationId(command.installationId)
        val deviceId = buildFirebaseDeviceDocumentId(command.installationId)
        val response = try {
            firebaseFunctions.getHttpsCallable("registerOwnDevice")
                .call(mapOf("installationId" to command.installationId))
                .await()
                .data as? Map<*, *> ?: malformedDeviceResponse()
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("register this device for notifications")
        }
        if (response["deviceId"] != deviceId.raw || response["registered"] != true) {
            malformedDeviceResponse()
        }
        return RemoteDeviceRegistrationReceipt(
            accountUid = command.accountUid,
            deviceId = deviceId,
            mutation = RemoteDeviceMutation.REGISTERED,
            affectedDevices = 1,
        )
    }

    override suspend fun removeCurrentDevice(
        accountUid: RemoteAccountUid,
    ): RemoteDeviceRegistrationReceipt {
        requireAuthenticatedUid(accountUid)
        val installationId = try {
            firebaseInstallations.id.await()
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("prepare notification logout")
        }
        validateFirebaseInstallationId(installationId)
        val deviceId = buildFirebaseDeviceDocumentId(installationId)
        return removeOwnDevice(accountUid, deviceId)
    }

    override suspend fun removeOwnDevice(
        accountUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
    ): RemoteDeviceRegistrationReceipt {
        requireAuthenticatedUid(accountUid)
        val response = try {
            firebaseFunctions.getHttpsCallable("removeOwnDevice")
                .call(mapOf("deviceId" to deviceId.raw))
                .await()
                .data as? Map<*, *> ?: malformedDeviceResponse()
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("remove this registered device")
        }
        if (response["deviceId"] != deviceId.raw || response["removed"] !is Boolean) {
            malformedDeviceResponse()
        }
        return RemoteDeviceRegistrationReceipt(
            accountUid = accountUid,
            deviceId = deviceId,
            mutation = RemoteDeviceMutation.REMOVED,
            affectedDevices = if (response["removed"] == true) 1 else 0,
        )
    }

    private suspend fun currentDeviceId(): RemoteDeviceId {
        val installationId = try {
            firebaseInstallations.id.await()
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("identify this device")
        }
        return buildFirebaseDeviceDocumentId(installationId)
    }

    private fun requireAuthenticatedUid(accountUid: RemoteAccountUid) {
        sessionController.requireActiveToken(accountUid)
        if (firebaseAuth.currentUser?.uid != accountUid.raw) {
            throw RemoteChatException("The Firebase account session changed. Sign in again.")
        }
    }
}

internal fun parseRemoteRegisteredDevices(
    response: Map<*, *>,
    currentDeviceId: RemoteDeviceId,
): List<RemoteRegisteredDevice> {
    if (response.keys != setOf("devices")) malformedDeviceResponse()
    val devices = response["devices"] as? List<*> ?: malformedDeviceResponse()
    return devices.map { value ->
        val device = value as? Map<*, *> ?: malformedDeviceResponse()
        if (device.keys != REGISTERED_DEVICE_RESPONSE_KEYS) malformedDeviceResponse()
        if (device["platform"] != "ANDROID") malformedDeviceResponse()
        val deviceId = RemoteDeviceId(
            (device["deviceId"] as? String)
                ?.takeIf { id -> DEVICE_DOCUMENT_ID_PATTERN.matches(id) }
                ?: malformedDeviceResponse(),
        )
        val active = device["active"] as? Boolean ?: malformedDeviceResponse()
        val updatedAtMillis = when (val timestamp = device["updatedAtMillis"]) {
            null -> null
            is Number -> timestamp.toExactDeviceTimestamp()
            else -> malformedDeviceResponse()
        }
        RemoteRegisteredDevice(
            deviceId = deviceId,
            active = active,
            isCurrentDevice = deviceId == currentDeviceId,
            updatedAtMillis = updatedAtMillis,
        )
    }.also { parsed ->
        if (parsed.map { device -> device.deviceId }.toSet().size != parsed.size) {
            malformedDeviceResponse()
        }
    }
}

private fun Number.toExactDeviceTimestamp(): Long {
    val serialized = toDouble()
    val exact = toLong()
    if (!serialized.isFinite() || serialized != exact.toDouble() || exact < 0L) {
        malformedDeviceResponse()
    }
    return exact
}

private fun malformedDeviceResponse(): Nothing =
    throw RemoteChatException("Synapse returned an invalid registered-device response.")

internal fun buildFirebaseDeviceDocumentId(installationId: String): RemoteDeviceId {
    validateFirebaseInstallationId(installationId)
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(installationId.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return RemoteDeviceId(digest)
}

private fun validateFirebaseInstallationId(installationId: String) {
    require(installationId.length in MINIMUM_FID_LENGTH..MAXIMUM_FID_LENGTH) {
        "Firebase Messaging returned an invalid installation ID."
    }
}

private const val MINIMUM_FID_LENGTH = 16
private const val MAXIMUM_FID_LENGTH = 256
private val DEVICE_DOCUMENT_ID_PATTERN = Regex("^[a-f0-9]{64}$")
private val REGISTERED_DEVICE_RESPONSE_KEYS =
    setOf("active", "deviceId", "platform", "updatedAtMillis")
