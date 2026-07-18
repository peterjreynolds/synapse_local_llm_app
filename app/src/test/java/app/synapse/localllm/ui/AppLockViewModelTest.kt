package app.synapse.localllm.ui

import app.synapse.localllm.domain.security.AppLockConfiguration
import app.synapse.localllm.domain.security.AppLockPin
import app.synapse.localllm.domain.security.AppLockRepository
import app.synapse.localllm.domain.security.AppLockVerificationOutcome
import app.synapse.localllm.domain.security.AppLockVerificationReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppLockViewModelTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun configuredLockStartsLockedAndRelocksWhenAppLeavesScreen() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = RecordingAppLockRepository(enabled = true)
        val viewModel = AppLockViewModel(repository)
        runCurrent()

        assertTrue(viewModel.uiState.value.isEnabled)
        assertFalse(viewModel.uiState.value.isUnlocked)

        viewModel.unlock("1234")
        runCurrent()
        assertTrue(viewModel.uiState.value.isUnlocked)

        viewModel.lock()
        assertFalse(viewModel.uiState.value.isUnlocked)
    }

    @Test
    fun pinConfirmationIsRequiredBeforeEnablingOrChanging() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = RecordingAppLockRepository(enabled = false)
        val viewModel = AppLockViewModel(repository)
        runCurrent()

        viewModel.enable("1234", "4321")
        runCurrent()
        assertFalse(repository.configurationState.value.enabled)
        assertTrue(viewModel.uiState.value.notice?.contains("do not match") == true)

        viewModel.enable("1234", "1234")
        runCurrent()
        assertTrue(repository.configurationState.value.enabled)
        assertTrue(viewModel.uiState.value.isUnlocked)
    }

    @Test
    fun pinInputKeepsOnlyFourDigits() {
        assertTrue(normalizeAppLockPinInput("1a2-345") == "1234")
        assertTrue(appLockPinFieldsComplete("1234", "5678", "5678"))
        assertFalse(appLockPinFieldsComplete("123", "5678", "5678"))
    }

    private class RecordingAppLockRepository(enabled: Boolean) : AppLockRepository {
        val configurationState = MutableStateFlow(
            AppLockConfiguration(enabled = enabled, credentialAvailable = true),
        )
        private var pin = AppLockPin.parse("1234")
        override val configuration: Flow<AppLockConfiguration> = configurationState

        override suspend fun enable(pin: AppLockPin) {
            this.pin = pin
            configurationState.value = AppLockConfiguration(enabled = true, credentialAvailable = true)
        }

        override suspend fun verify(pin: AppLockPin): AppLockVerificationReceipt =
            verification(pin)

        override suspend fun changePin(
            currentPin: AppLockPin,
            newPin: AppLockPin,
        ): AppLockVerificationReceipt = verification(currentPin).also { receipt ->
            if (receipt.outcome == AppLockVerificationOutcome.VERIFIED) pin = newPin
        }

        override suspend fun disable(pin: AppLockPin): AppLockVerificationReceipt =
            verification(pin).also { receipt ->
                if (receipt.outcome == AppLockVerificationOutcome.VERIFIED) {
                    configurationState.value = AppLockConfiguration(enabled = false, credentialAvailable = true)
                }
            }

        private fun verification(suppliedPin: AppLockPin): AppLockVerificationReceipt =
            AppLockVerificationReceipt(
                if (suppliedPin == pin) {
                    AppLockVerificationOutcome.VERIFIED
                } else {
                    AppLockVerificationOutcome.INVALID_PIN
                },
            )
    }
}
