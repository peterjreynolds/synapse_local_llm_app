package app.synapse.privatechat.crypto

/**
 * Owns the sole adapter allowed to operate on one encrypted Signal repository. The local address
 * is learned from registration, then every later caller must resolve to that exact device.
 */
internal class SignalProtocolAdapterOwner(
    private val stateRepository: SignalProtocolStateRepository,
) {
    private val monitor = Any()
    private var ownedAdapter: OwnedSignalProtocolAdapter? = null

    fun storedLocalAddress(): SignalDeviceAddress? = synchronized(monitor) { stateRepository.loadLocalIdentity()?.address }

    fun adapterFor(address: SignalDeviceAddress): SignalProtocolAdapter =
        synchronized(monitor) {
            val storedAddress = stateRepository.loadLocalIdentity()?.address
            if (storedAddress != null && storedAddress != address) {
                throw SignalProtocolStateCorruptedException(
                    "Stored Signal identity is bound to a different authenticated device",
                )
            }
            ownedAdapter?.let { owned ->
                if (owned.address != address) {
                    throw SignalProtocolStateCorruptedException(
                        "Signal adapter owner is already bound to a different authenticated device",
                    )
                }
                return@synchronized owned.adapter
            }
            SignalProtocolAdapter(address, stateRepository).also { adapter ->
                ownedAdapter = OwnedSignalProtocolAdapter(address, adapter)
            }
        }

    fun requireAdapterForStoredIdentity(): SignalProtocolAdapter {
        val address =
            storedLocalAddress()
                ?: throw SignalProtocolStateCorruptedException("Local Signal identity has not been initialized")
        return adapterFor(address)
    }

    private data class OwnedSignalProtocolAdapter(
        val address: SignalDeviceAddress,
        val adapter: SignalProtocolAdapter,
    )
}
