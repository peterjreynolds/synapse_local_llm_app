package app.synapse.localllm.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.domain.security.AppLockPin
import app.synapse.localllm.domain.security.AppLockVerificationOutcome
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidAppLockRepositoryTest {
    @Test
    fun pinLifecyclePersistsOnlyDerivedCredentialAndRequiresCurrentPin() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageFileName = "app-lock-${UUID.randomUUID()}.preferences_pb"
        val repository = createRepository(context, storageFileName)

        repository.enable(AppLockPin.parse("1234"))

        assertTrue(repository.configuration.first().enabled)
        assertTrue(repository.configuration.first().credentialAvailable)
        assertTrue(
            repository.verify(AppLockPin.parse("0000")).outcome ==
                AppLockVerificationOutcome.INVALID_PIN,
        )
        assertTrue(
            repository.verify(AppLockPin.parse("1234")).outcome ==
                AppLockVerificationOutcome.VERIFIED,
        )
        assertTrue(
            repository.changePin(AppLockPin.parse("0000"), AppLockPin.parse("9876")).outcome ==
                AppLockVerificationOutcome.INVALID_PIN,
        )
        assertTrue(
            repository.changePin(AppLockPin.parse("1234"), AppLockPin.parse("9876")).outcome ==
                AppLockVerificationOutcome.VERIFIED,
        )
        assertTrue(
            repository.verify(AppLockPin.parse("1234")).outcome ==
                AppLockVerificationOutcome.INVALID_PIN,
        )
        assertTrue(
            repository.verify(AppLockPin.parse("9876")).outcome ==
                AppLockVerificationOutcome.VERIFIED,
        )

        val storedBytes = context.noBackupFilesDir.resolve(storageFileName).readBytes()
        assertFalse(storedBytes.toString(Charsets.UTF_8).contains("1234"))
        assertFalse(storedBytes.toString(Charsets.UTF_8).contains("9876"))

        assertTrue(
            repository.disable(AppLockPin.parse("9876")).outcome ==
                AppLockVerificationOutcome.VERIFIED,
        )
        assertFalse(repository.configuration.first().enabled)
    }

    @Test
    fun fiveFailedAttemptsTemporarilyBlockEvenTheCorrectPin() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var now = 1_000L
        val repository = createRepository(
            context = context,
            storageFileName = "app-lock-${UUID.randomUUID()}.preferences_pb",
            nowEpochMillis = { now },
        )
        repository.enable(AppLockPin.parse("1234"))

        repeat(4) {
            assertTrue(
                repository.verify(AppLockPin.parse("0000")).outcome ==
                    AppLockVerificationOutcome.INVALID_PIN,
            )
        }
        assertTrue(
            repository.verify(AppLockPin.parse("0000")).outcome ==
                AppLockVerificationOutcome.TEMPORARILY_BLOCKED,
        )
        assertTrue(
            repository.verify(AppLockPin.parse("1234")).outcome ==
                AppLockVerificationOutcome.TEMPORARILY_BLOCKED,
        )

        now += 30_000L

        assertTrue(
            repository.verify(AppLockPin.parse("1234")).outcome ==
                AppLockVerificationOutcome.VERIFIED,
        )
    }

    @Test
    fun accountReauthenticatedReplacementClearsForgottenPinLockout() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = createRepository(
            context = context,
            storageFileName = "app-lock-${UUID.randomUUID()}.preferences_pb",
        )
        repository.enable(AppLockPin.parse("1234"))

        repeat(5) { repository.verify(AppLockPin.parse("0000")) }
        assertTrue(
            repository.verify(AppLockPin.parse("1234")).outcome ==
                AppLockVerificationOutcome.TEMPORARILY_BLOCKED,
        )

        repository.replaceCredentialAfterAccountReauthentication(AppLockPin.parse("5678"))

        assertTrue(
            repository.verify(AppLockPin.parse("1234")).outcome ==
                AppLockVerificationOutcome.INVALID_PIN,
        )
        assertTrue(
            repository.verify(AppLockPin.parse("5678")).outcome ==
                AppLockVerificationOutcome.VERIFIED,
        )
    }

    private fun createRepository(
        context: Context,
        storageFileName: String,
        nowEpochMillis: () -> Long = { 1_000L },
    ): AndroidAppLockRepository = AndroidAppLockRepository(
        context = context,
        credentialHasher = AppLockCredentialHasher { salt, pin ->
            MessageDigest.getInstance("SHA-256").digest(salt + pin.digits.toByteArray())
        },
        nowEpochMillis = nowEpochMillis,
        createSalt = { ByteArray(32) { index -> (index + 1).toByte() } },
        storageFileName = storageFileName,
    )
}
