package app.synapse.privatechat.ui.call

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.call.PrivateCallPeerSafetyNumber
import app.synapse.privatechat.domain.call.PrivateCallPreparation
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import app.synapse.privatechat.domain.chat.PrivateRoomSummary
import app.synapse.privatechat.ui.chat.PrivateChatNavigationActions
import app.synapse.privatechat.ui.chat.PrivateConversationHeader
import app.synapse.privatechat.ui.chat.PrivateRoomUiActions
import app.synapse.privatechat.ui.theme.SynapsePrivateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PrivateCallEntryUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val room = mutableStateOf(directRoom())
    private val mutationEnabled = mutableStateOf(true)
    private val callsAvailable = mutableStateOf(true)
    private val requests = mutableListOf<PrivateCallMediaKind>()
    private var confirmations = 0

    @Test
    fun directConversationOffersVoiceAndVideoWithOneCallbackPerTap() {
        renderHeader()
        compose.onNodeWithContentDescription("Voice call").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(PrivateCallMediaKind.VOICE), requests) }
        compose.onNodeWithContentDescription("Video call").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(PrivateCallMediaKind.VOICE, PrivateCallMediaKind.VIDEO), requests) }
    }

    @Test
    fun waitingDirectRoomsAndGroupsDoNotExposeCallButtons() {
        room.value = room.value.copy(participantCount = 1)
        renderHeader()
        assertNoCallButtons()
        compose.runOnIdle { room.value = room.value.copy(kind = PrivateRoomKind.GROUP, participantCount = 3) }
        assertNoCallButtons()
        compose.runOnIdle { assertEquals(0, requests.size) }
    }

    @Test
    fun disconnectedTransportOrExistingCallDisablesBothButtons() {
        callsAvailable.value = false
        renderHeader()
        assertCallButtonsDisabled()
        compose.runOnIdle {
            callsAvailable.value = true
            mutationEnabled.value = false
        }
        assertCallButtonsDisabled()
        compose.runOnIdle { assertEquals(0, requests.size) }
    }

    @Test
    fun noCaptureConfirmationUntilDirectModeAndIdentityAreBothExplicitlyAccepted() {
        renderConsent(safetyNumbers())
        compose.onNodeWithText("Start call").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, confirmations) }
        compose.onNodeWithContentDescription("Accept direct calling").performScrollTo().performClick()
        compose.onNodeWithText("Start call").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Verify safety numbers").performScrollTo().performClick()
        compose.onNodeWithText("Start call").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, confirmations) }
    }

    @Test
    fun acceptingOnlyIdentityCannotStartCapture() {
        renderConsent(safetyNumbers())
        compose.onNodeWithContentDescription("Verify safety numbers").performScrollTo().performClick()
        compose.onNodeWithText("Start call").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, confirmations) }
    }

    @Test
    fun missingPeerIdentityFailsClosedEvenAfterBothCheckboxes() {
        renderConsent(emptyList())
        compose.onNodeWithContentDescription("Accept direct calling").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Verify safety numbers").performScrollTo().performClick()
        compose.onNodeWithText("Start call").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, confirmations) }
    }

    private fun assertNoCallButtons() {
        compose.onNodeWithContentDescription("Voice call").assertDoesNotExist()
        compose.onNodeWithContentDescription("Video call").assertDoesNotExist()
    }

    private fun assertCallButtonsDisabled() {
        compose.onNodeWithContentDescription("Voice call").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Video call").assertIsNotEnabled()
    }

    private fun renderHeader() {
        compose.setContent {
            SynapsePrivateTheme {
                Box(Modifier.width(360.dp)) {
                    PrivateConversationHeader(
                        room = room.value,
                        showBackButton = true,
                        mutationEnabled = mutationEnabled.value,
                        invitationCreating = false,
                        navigationActions = PrivateChatNavigationActions({}, {}, {}, {}, {}, {}),
                        roomActions = PrivateRoomUiActions({}, {}, {}, {}, {}, {}),
                        callActions = PrivateCallUiActions(callsAvailable.value) { _, kind -> requests += kind },
                    )
                }
            }
        }
    }

    private fun renderConsent(safetyNumbers: List<PrivateCallPeerSafetyNumber>) {
        val request =
            PrivateCallConsentRequest.Outgoing(
                room = room.value,
                preparation = PrivateCallPreparation(room.value.roomId, 1, safetyNumbers),
                mediaKind = PrivateCallMediaKind.VOICE,
            )
        compose.setContent {
            SynapsePrivateTheme {
                PrivateCallConsentDialog(
                    state = PrivateCallUiState.Consent(request, safetyNumbers),
                    onDismiss = {},
                    onConfirm = { confirmations += 1 },
                )
            }
        }
    }

    private fun safetyNumbers() =
        listOf(
            PrivateCallPeerSafetyNumber(
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                UUID.fromString("10000000-0000-4000-8000-000000000002"),
                List(12) { "12345" }.joinToString(" "),
            ),
        )

    private fun directRoom() =
        PrivateRoomSummary(
            roomId = PrivateRoomId("10000000-0000-4000-8000-000000000003"),
            kind = PrivateRoomKind.DIRECT,
            title = "Test friend",
            participantCount = 2,
            retention = PrivateMessageRetention.ONE_DAY,
            archiveState = PrivateRoomArchiveState.ACTIVE,
            pinState = PrivateRoomPinState.UNPINNED,
            muteState = PrivateRoomMuteState.AUDIBLE,
            unreadMessageCount = 0,
            latestMessagePreview = null,
        )
}
