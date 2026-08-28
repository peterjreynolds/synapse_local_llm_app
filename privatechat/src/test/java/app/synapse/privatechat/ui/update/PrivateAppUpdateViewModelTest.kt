package app.synapse.privatechat.ui.update

import app.synapse.privatechat.data.update.testUpdate
import app.synapse.privatechat.domain.update.PrivateAppInstallerLaunchOutcome
import app.synapse.privatechat.domain.update.PrivateAppUpdateCheckOutcome
import app.synapse.privatechat.domain.update.PrivateAppUpdateDownloadEvent
import app.synapse.privatechat.domain.update.PrivateAppUpdateDownloadReceipt
import app.synapse.privatechat.domain.update.PrivateAppUpdateDownloader
import app.synapse.privatechat.domain.update.PrivateAppUpdateRepository
import app.synapse.privatechat.domain.update.PrivateAvailableAppUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateAppUpdateViewModelTest {
    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `checks once on app open and prompts only for a newer compatible version`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = StubPrivateAppUpdateRepository(PrivateAppUpdateCheckOutcome.Available(testUpdate()))
            val viewModel = PrivateAppUpdateViewModel(repository, StubPrivateAppUpdateDownloader())

            viewModel.checkOnceOnAppOpen()
            viewModel.checkOnceOnAppOpen()
            runCurrent()

            assertEquals(1, repository.checkCount)
            assertTrue(viewModel.uiState.value is PrivateAppUpdateUiState.Available)
        }

    @Test
    fun `automatic offline failure remains silent and does not create blocking update UI`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository =
                StubPrivateAppUpdateRepository(
                    PrivateAppUpdateCheckOutcome.Failed("The update server could not be reached."),
                )
            val viewModel = PrivateAppUpdateViewModel(repository, StubPrivateAppUpdateDownloader())

            viewModel.checkOnceOnAppOpen()
            runCurrent()

            assertSame(PrivateAppUpdateUiState.Idle, viewModel.uiState.value)
        }

    @Test
    fun `download progress ends in a verified installer request with explicit permission guidance`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val update = testUpdate()
            val receipt =
                PrivateAppUpdateDownloadReceipt(
                    installerUri =
                        "content://app.synapse.privatechat.updateprovider/verified_app_updates/" +
                            "Synapse-Private-2038.apk",
                    displayName = "Synapse-Private-2038.apk",
                    byteCount = update.apkByteCount,
                    sha256 = update.apkSha256,
                    versionCode = update.versionCode,
                )
            val downloader =
                StubPrivateAppUpdateDownloader(
                    flowOf(
                        PrivateAppUpdateDownloadEvent.Progress(update, 4_096),
                        PrivateAppUpdateDownloadEvent.Verifying(update),
                        PrivateAppUpdateDownloadEvent.Completed(update, receipt),
                    ),
                )
            val viewModel =
                PrivateAppUpdateViewModel(
                    StubPrivateAppUpdateRepository(PrivateAppUpdateCheckOutcome.Available(update)),
                    downloader,
                )
            viewModel.checkOnceOnAppOpen()
            runCurrent()

            viewModel.downloadUpdate()
            runCurrent()

            val ready = viewModel.uiState.value as PrivateAppUpdateUiState.ReadyToInstall
            assertTrue(ready.installerLaunchPending)
            viewModel.markInstallerLaunchStarted(ready.installerRequestId)
            assertFalse((viewModel.uiState.value as PrivateAppUpdateUiState.ReadyToInstall).installerLaunchPending)
            viewModel.recordInstallerLaunchOutcome(PrivateAppInstallerLaunchOutcome.PermissionRequired)
            assertTrue(
                (viewModel.uiState.value as PrivateAppUpdateUiState.ReadyToInstall)
                    .userMessage
                    .contains("Allow Synapse Private"),
            )
        }

    @Test
    fun `download verification failure stays visible and can be retried`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val update = testUpdate()
            val downloader =
                StubPrivateAppUpdateDownloader(
                    flow {
                        throw IOException("Update APK signing certificate is untrusted.")
                    },
                )
            val viewModel =
                PrivateAppUpdateViewModel(
                    StubPrivateAppUpdateRepository(PrivateAppUpdateCheckOutcome.Available(update)),
                    downloader,
                )
            viewModel.checkOnceOnAppOpen()
            runCurrent()

            viewModel.downloadUpdate()
            runCurrent()

            val failure = viewModel.uiState.value as PrivateAppUpdateUiState.Failed
            assertTrue(failure.userMessage.contains("signing certificate"))
            assertEquals(update, failure.update)
        }
}

private class StubPrivateAppUpdateRepository(
    private val outcome: PrivateAppUpdateCheckOutcome,
) : PrivateAppUpdateRepository {
    var checkCount = 0

    override suspend fun checkForNewerCompatibleUpdate(): PrivateAppUpdateCheckOutcome {
        checkCount += 1
        return outcome
    }
}

private class StubPrivateAppUpdateDownloader(
    private val events: Flow<PrivateAppUpdateDownloadEvent> = flowOf(),
) : PrivateAppUpdateDownloader {
    override fun downloadAndVerifyUpdate(update: PrivateAvailableAppUpdate): Flow<PrivateAppUpdateDownloadEvent> = events
}
