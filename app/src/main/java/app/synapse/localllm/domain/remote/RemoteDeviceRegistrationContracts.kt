package app.synapse.localllm.domain.remote

@JvmInline
value class RemoteDeviceId(val raw: String) {
    init {
        require(raw.isNotBlank()) { "Remote device ID cannot be blank." }
    }
}

data class RegisterRemoteDeviceInstallationCommand(
    val accountUid: RemoteAccountUid,
    val installationId: String,
)

data class RemoteDeviceRegistrationReceipt(
    val accountUid: RemoteAccountUid,
    val deviceId: RemoteDeviceId,
    val mutation: RemoteDeviceMutation,
    val affectedDevices: Int,
)

enum class RemoteDeviceMutation {
    REGISTERED,
    REMOVED,
}

interface RemoteDeviceRegistrationGateway {
    suspend fun registerCurrentDevice(accountUid: RemoteAccountUid): RemoteDeviceRegistrationReceipt

    suspend fun registerRefreshedInstallation(
        command: RegisterRemoteDeviceInstallationCommand,
    ): RemoteDeviceRegistrationReceipt

    suspend fun removeCurrentDevice(accountUid: RemoteAccountUid): RemoteDeviceRegistrationReceipt
}
