package app.synapse.privatechat.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateActivitySharingPreferences
import app.synapse.privatechat.domain.chat.PrivatePresenceSharingState
import app.synapse.privatechat.domain.chat.PrivateProfileSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomFeedSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateSocialSnapshot
import app.synapse.privatechat.ui.account.PrivateAccountSignOutUiState
import app.synapse.privatechat.ui.theme.SynapsePrivateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateChatNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val accountId = PrivateAccountId("navigation-test-account")
    private val chatState =
        mutableStateOf(
            PrivateChatUiState(
                session = PrivateChatSessionUiState.Active(accountId),
                social =
                    PrivateSocialUiState.Available(
                        PrivateSocialSnapshot(
                            accountId = accountId,
                            profile = PrivateProfileSnapshot(accountId, "Test Friend", "testfriend"),
                            presenceSharing = PrivatePresenceSharingState.DISABLED,
                            visiblePresence = emptyList(),
                        ),
                    ),
                roomFeed =
                    PrivateRoomFeedUiState.Available(
                        PrivateRoomFeedSnapshot(accountId, emptyList(), PrivateActivitySharingPreferences()),
                    ),
            ),
        )
    private var invitationRequests = 0

    @Test
    fun menuOpensProfileAndBackReturnsToChatListWithSearchPreserved() {
        renderChat()
        compose.onNodeWithText("Search conversations").performTextInput("weekend")
        openMenu()
        compose.onNodeWithText("Profile & privacy").performClick()
        compose.onNodeWithContentDescription("Back to chats").assertIsDisplayed().performClick()
        compose.onNodeWithText("weekend").assertIsDisplayed()
        compose.onNodeWithContentDescription("New chat. Start or join a conversation").assertIsDisplayed()
    }

    @Test
    fun menuAndNewChatButtonOpenTheSameConversationChooser() {
        renderChat()
        openMenu()
        compose.onNodeWithText("New conversation").performClick()
        assertConversationChoices()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("New chat. Start or join a conversation").assertIsDisplayed().performClick()
        assertConversationChoices()
    }

    @Test
    fun inviteRequestsOnlyOnceWhilePendingAndSurfacesARefusal() {
        renderChat()
        openMenu()
        compose.onNodeWithText("Invite a friend").performClick()
        compose.runOnIdle { assertEquals(1, invitationRequests) }
        openMenu()
        compose.onNodeWithText("Invite a friend").assertIsNotEnabled()
        compose.runOnIdle {
            chatState.value = chatState.value.copy(accountInvitation = PrivateAccountInvitationUiState.Rejected("Test refusal"))
        }
        compose.onNodeWithText("Invitation not created").assertIsDisplayed()
        compose.onNodeWithText("Test refusal").assertIsDisplayed()
    }

    @Test
    fun inviteIsDisabledWhileProfileIsUnavailable() {
        chatState.value = chatState.value.copy(social = PrivateSocialUiState.TransportUnavailable)
        renderChat()
        openMenu()
        compose.onNodeWithText("Invite a friend").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, invitationRequests) }
        compose.onNodeWithText("Profile & privacy").performClick()
        compose.onNodeWithText("Your profile is temporarily unavailable.").assertIsDisplayed()
    }

    @Test
    fun backClosesMenuWithoutLeavingChatOrClearingSearch() {
        renderChat()
        compose.onNodeWithText("Search conversations").performTextInput("friends")
        openMenu()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("friends").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(PrivateChatOverlay.HIDDEN, chatState.value.overlay)
            assertEquals(false, compose.activity.isFinishing)
        }
    }

    @Test
    fun selectedConversationStillReturnsToChatsOnBack() {
        chatState.value =
            chatState.value.copy(
                selectedRoomId = PrivateRoomId("selected-room"),
                conversation = PrivateConversationUiState.TransportUnavailable,
            )
        renderChat()
        compose.onNodeWithText("Conversation unavailable").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("New chat. Start or join a conversation").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, chatState.value.selectedRoomId) }
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Invite a friend").assertIsDisplayed()
    }

    private fun assertConversationChoices() {
        compose.onNodeWithText("New direct chat").assertIsDisplayed()
        compose.onNodeWithText("New group").assertIsDisplayed()
        compose.onNodeWithText("Join with code").assertIsDisplayed()
    }

    private fun renderChat() {
        compose.setContent {
            SynapsePrivateTheme {
                // Keep phone navigation under test even when the runner uses a tablet emulator.
                Box(modifier = Modifier.width(360.dp)) {
                    PrivateChatScreen(
                        state = chatState.value,
                        accountSessionActions = PrivateAccountSessionUiActions(PrivateAccountSignOutUiState.Idle) {},
                        navigationActions =
                            PrivateChatNavigationActions(
                                selectRoom = { roomId -> chatState.value = chatState.value.copy(selectedRoomId = roomId) },
                                showRoomList = {
                                    chatState.value = chatState.value.copy(selectedRoomId = null)
                                },
                                showProfile = { showOverlay(PrivateChatOverlay.PROFILE) },
                                showCreateConversation = { showOverlay(PrivateChatOverlay.CREATE_CONVERSATION) },
                                showGroupManagement = { showOverlay(PrivateChatOverlay.MANAGE_GROUP) },
                                dismissOverlay = { showOverlay(PrivateChatOverlay.HIDDEN) },
                            ),
                        messageActions =
                            PrivateMessageUiActions(
                                changeComposerText = {},
                                submitComposer = {},
                                beginReply = {},
                                beginEdit = {},
                                cancelComposerContext = {},
                                toggleReaction = { _, _ -> },
                                deleteForEveryone = {},
                            ),
                        roomActions =
                            PrivateRoomUiActions(
                                changeRetention = {},
                                changeArchiveState = {},
                                changePinState = {},
                                changeMuteState = {},
                                createOneUseInvitation = {},
                                dismissInvitation = {},
                            ),
                        socialActions =
                            PrivateSocialUiActions(
                                changeReadReceiptSharing = {},
                                changeTypingIndicatorSharing = {},
                                saveProfile = {},
                                createRoom = { _, _, _ -> },
                                redeemRoomInvitation = {},
                                changePresenceSharing = {},
                                changeGroupMemberRole = { _, _ -> },
                                removeGroupMember = {},
                                createOneUseAccountInvitation = {
                                    invitationRequests += 1
                                    chatState.value = chatState.value.copy(accountInvitation = PrivateAccountInvitationUiState.Creating)
                                },
                                dismissAccountInvitation = {},
                            ),
                        onDismissOperationNotice = {},
                    )
                }
            }
        }
    }

    private fun showOverlay(overlay: PrivateChatOverlay) {
        chatState.value = chatState.value.copy(overlay = overlay)
    }
}
