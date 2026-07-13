package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RegisterRemoteDeviceInstallationCommand
import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteDeviceId
import app.synapse.localllm.domain.remote.RemoteDeviceMutation
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationGateway
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationReceipt
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import java.security.MessageDigest
import kotlinx.coroutines.tasks.await

class FirebaseRemoteDeviceRegistrationGateway(
    private val firebaseAuth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val firebaseInstallations: FirebaseInstallations,
    private val firebaseMessaging: FirebaseMessaging,
    private val sessionController: RemoteAccountSessionController,
) : RemoteDeviceRegistrationGateway {
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
        val deviceCollection = firestore.collection(DEVICES_COLLECTION)
        try {
            val ownedDeviceDocuments = deviceCollection
                .whereEqualTo("ownerUid", command.accountUid.raw)
                .get()
                .await()
                .documents
            val existingDevice = ownedDeviceDocuments.singleOrNull { document ->
                document.id == deviceId.raw
            }
            val deviceReference = deviceCollection.document(deviceId.raw)
            if (existingDevice == null) {
                deviceReference.set(
                    mapOf(
                        "active" to true,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "installationId" to command.installationId,
                        "ownerUid" to command.accountUid.raw,
                        "platform" to ANDROID_PLATFORM,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                ).await()
            } else {
                validateOwnedDevice(existingDevice, command.accountUid)
                deviceReference.update(
                    mapOf(
                        "active" to true,
                        "installationId" to command.installationId,
                        "platform" to ANDROID_PLATFORM,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                ).await()
            }
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("register this device for notifications")
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
        var affectedDevices = 0
        try {
            val existingDevice = firestore.collection(DEVICES_COLLECTION)
                .whereEqualTo("ownerUid", accountUid.raw)
                .get()
                .await()
                .documents
                .singleOrNull { document -> document.id == deviceId.raw }
            if (existingDevice != null) {
                validateOwnedDevice(existingDevice, accountUid)
                existingDevice.reference.delete().await()
                affectedDevices = 1
            }
            firebaseMessaging.unregister().await()
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("remove this device from notifications")
        }
        return RemoteDeviceRegistrationReceipt(
            accountUid = accountUid,
            deviceId = deviceId,
            mutation = RemoteDeviceMutation.REMOVED,
            affectedDevices = affectedDevices,
        )
    }

    private fun requireAuthenticatedUid(accountUid: RemoteAccountUid) {
        sessionController.requireActiveToken(accountUid)
        if (firebaseAuth.currentUser?.uid != accountUid.raw) {
            throw RemoteChatException("The Firebase account session changed. Sign in again.")
        }
    }

    private fun validateOwnedDevice(
        deviceDocument: DocumentSnapshot,
        accountUid: RemoteAccountUid,
    ) {
        if (deviceDocument.getString("ownerUid") != accountUid.raw) {
            throw RemoteChatException("The notification device belongs to another account.")
        }
    }

    private companion object {
        const val ANDROID_PLATFORM = "ANDROID"
        const val DEVICES_COLLECTION = "devices"
    }
}

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
