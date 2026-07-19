package app.synapse.localllm.data.calling

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.domain.calling.DirectCallRingtoneSource
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidDirectCallRingtoneRepositoryTest {
    @Test
    fun phoneDefaultMutationPersistsAReceiptWithoutAnExternalUri() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AndroidDirectCallRingtoneRepository(
            context = context,
            clock = FixedClock,
            preferencesName = "ringtone-test-${UUID.randomUUID()}",
        )

        val receipt = repository.usePhoneDefaultRingtone()

        assertEquals(DirectCallRingtoneSource.PHONE_DEFAULT, receipt.selection.source)
        assertEquals(null, receipt.selection.uri)
        assertEquals(FIXED_INSTANT, receipt.persistedAt)
        assertEquals(receipt.selection, repository.currentSelection())
    }

    @Test
    fun audioSelectionRejectsNonContentPathsBeforePersistence() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AndroidDirectCallRingtoneRepository(
            context = context,
            clock = FixedClock,
            preferencesName = "ringtone-test-${UUID.randomUUID()}",
        )

        val failure = runCatching {
            repository.selectAudioFile("file:///sdcard/Download/untrusted.mp3")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(DirectCallRingtoneSource.PHONE_DEFAULT, repository.currentSelection().source)
    }

    private object FixedClock : SynapseClock {
        override fun now(): Instant = FIXED_INSTANT
    }

    private companion object {
        val FIXED_INSTANT: Instant = Instant.parse("2026-07-19T12:00:00Z")
    }
}
