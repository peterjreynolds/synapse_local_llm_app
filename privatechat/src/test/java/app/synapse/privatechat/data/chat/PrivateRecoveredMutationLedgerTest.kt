package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalDeviceId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class PrivateRecoveredMutationLedgerTest {
    @Test
    fun recoveryReceiptsRemainAvailableToMultipleObserversUntilInvalidation() {
        val ledger = PrivateRecoveredMutationLedger()
        val recoveredMutationId = UUID.fromString("10000000-0000-4000-8000-000000000001")

        assertEquals(
            setOf(recoveredMutationId),
            ledger.recordAndSnapshot(LOCAL_DEVICE, setOf(recoveredMutationId)),
        )
        assertEquals(
            setOf(recoveredMutationId),
            ledger.recordAndSnapshot(LOCAL_DEVICE, emptySet()),
        )
        assertEquals(
            setOf(recoveredMutationId),
            ledger.recordAndSnapshot(LOCAL_DEVICE, emptySet()),
        )

        ledger.clear()

        assertEquals(emptySet<UUID>(), ledger.recordAndSnapshot(LOCAL_DEVICE, emptySet()))
    }

    @Test
    fun authenticatedDeviceSwitchCannotInheritRecoveryReceipts() {
        val ledger = PrivateRecoveredMutationLedger()
        val recoveredMutationId = UUID.fromString("10000000-0000-4000-8000-000000000001")
        ledger.recordAndSnapshot(LOCAL_DEVICE, setOf(recoveredMutationId))

        val switchedDevice =
            SignalDeviceAddress(
                accountId = LOCAL_DEVICE.accountId,
                transportDeviceId = UUID.fromString("30000000-0000-4000-8000-000000000003"),
                protocolDeviceId = SignalDeviceId.fromWire(8),
            )

        assertEquals(emptySet<UUID>(), ledger.recordAndSnapshot(switchedDevice, emptySet()))
    }
}

private val LOCAL_DEVICE =
    SignalDeviceAddress(
        accountId = UUID.fromString("20000000-0000-4000-8000-000000000002"),
        transportDeviceId = UUID.fromString("30000000-0000-4000-8000-000000000004"),
        protocolDeviceId = SignalDeviceId.fromWire(7),
    )
