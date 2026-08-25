package app.synapse.privatechat.data.account

import app.synapse.privatechat.data.session.PrivateInstallationId
import app.synapse.privatechat.domain.account.PrivateInvitationCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

class PrivateRegistrationRedemptionIdFactoryTest {
    @Test
    fun derivesStableVersionEightReceiptIdWithoutPersistingTheInvite() {
        val installationId =
            PrivateInstallationId.fromGeneratedUuid(
                UUID.fromString("20000000-0000-4000-8000-000000000002"),
            )
        val invite = PrivateInvitationCode("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")

        val first = PrivateRegistrationRedemptionIdFactory.derive(installationId, invite)
        val second = PrivateRegistrationRedemptionIdFactory.derive(installationId, invite)

        assertEquals(first, second)
        assertEquals(8, first.version())
        assertEquals(2, first.variant())
        assertNotEquals(
            first,
            PrivateRegistrationRedemptionIdFactory.derive(
                installationId,
                PrivateInvitationCode("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"),
            ),
        )
    }
}
