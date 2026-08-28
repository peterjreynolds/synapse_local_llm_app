package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalDeviceAddress
import java.util.UUID

/**
 * Retains bounded recovery receipts for every observer on the active device. Receipts remain until
 * session invalidation or an authenticated-device switch so one polling observer cannot consume
 * another UI owner's recovery confirmation.
 */
internal class PrivateRecoveredMutationLedger(
    private val maximumReceiptCount: Int = MAXIMUM_RECOVERED_MUTATION_RECEIPTS,
) {
    private var owner: SignalDeviceAddress? = null
    private val mutationIds = linkedSetOf<UUID>()

    init {
        require(maximumReceiptCount > 0) { "Recovered mutation receipt capacity must be positive" }
    }

    fun recordAndSnapshot(
        owner: SignalDeviceAddress,
        recoveredMutationIds: Set<UUID>,
    ): Set<UUID> {
        if (this.owner != owner) {
            mutationIds.clear()
            this.owner = owner
        }
        mutationIds.addAll(recoveredMutationIds)
        while (mutationIds.size > maximumReceiptCount) {
            mutationIds.iterator().run {
                next()
                remove()
            }
        }
        return mutationIds.toSet()
    }

    fun snapshot(owner: SignalDeviceAddress): Set<UUID> = recordAndSnapshot(owner, emptySet())

    fun clear() {
        owner = null
        mutationIds.clear()
    }
}

private const val MAXIMUM_RECOVERED_MUTATION_RECEIPTS = 128
