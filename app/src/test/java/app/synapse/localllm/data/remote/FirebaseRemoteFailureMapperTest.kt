package app.synapse.localllm.data.remote

import com.google.firebase.functions.FirebaseFunctionsException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirebaseRemoteFailureMapperTest {
    @Test
    fun unavailableFunctionReportsAServiceOutageInsteadOfASettingsFailure() {
        val mapped = firebaseFunctionsFailureMessage(
            FirebaseFunctionsException.Code.UNAVAILABLE,
            "load privacy settings",
        )

        assertEquals(
            "Synapse Chat services are temporarily offline. Try again later.",
            mapped,
        )
    }
}
