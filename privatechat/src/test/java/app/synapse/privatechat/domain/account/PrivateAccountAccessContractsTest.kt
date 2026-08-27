package app.synapse.privatechat.domain.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class PrivateAccountAccessContractsTest {
    @Test
    fun `invite registration normalizes account identity before transport`() {
        val validation =
            validatePrivateAccountAccessDraft(
                PrivateAccountAccessDraft.RegisterWithInvite(
                    displayNameInput = "  Peter  ",
                    usernameInput = "  Peter_01  ",
                    passwordInput = VALID_PASSWORD,
                    passwordConfirmationInput = VALID_PASSWORD,
                    invitationCodeInput = "  ${"A".repeat(43)}  ",
                ),
            )

        val command =
            when (validation) {
                is PrivateAccountAccessValidation.Accepted -> validation.command
                is PrivateAccountAccessValidation.Rejected ->
                    fail("Expected accepted registration, got ${validation.userMessage}")
            }
        when (command) {
            is PrivateAccountAccessCommand.RegisterWithInvite -> {
                assertEquals("Peter", command.displayName.canonical)
                assertEquals("peter_01", command.username.canonical)
                assertEquals("A".repeat(43), command.invitationCode.canonical)
                assertEquals(VALID_PASSWORD, command.password.exposeForAuthentication())
            }

            is PrivateAccountAccessCommand.SignIn -> fail("Expected invite registration command")
        }
    }

    @Test
    fun `invite registration rejects mismatched passwords before transport`() {
        val validation =
            validatePrivateAccountAccessDraft(
                PrivateAccountAccessDraft.RegisterWithInvite(
                    displayNameInput = "Peter",
                    usernameInput = "peter_01",
                    passwordInput = VALID_PASSWORD,
                    passwordConfirmationInput = "a-different-password",
                    invitationCodeInput = "A".repeat(43),
                ),
            )

        when (validation) {
            is PrivateAccountAccessValidation.Accepted -> fail("Expected rejected registration")
            is PrivateAccountAccessValidation.Rejected -> {
                assertEquals(PrivateAccountInputField.PASSWORD_CONFIRMATION, validation.field)
                assertEquals("The passwords do not match.", validation.userMessage)
            }
        }
    }

    @Test
    fun `account password does not reveal credentials through string rendering`() {
        val password = PrivateAccountPassword(VALID_PASSWORD)

        assertFalse(password.toString().contains(VALID_PASSWORD))
        assertEquals("PrivateAccountPassword([REDACTED])", password.toString())
    }

    @Test
    fun `account boundary rejects invite and password shapes the backend rejects`() {
        val shortInvite = registrationDraft(invitationCode = "A".repeat(42))
        val oversizedUtf8Password = "🙂".repeat(33)
        val oversizedPassword =
            registrationDraft(
                password = oversizedUtf8Password,
                invitationCode = "A".repeat(43),
            )
        val controlPassword =
            registrationDraft(
                password = "correct-horse\n-battery",
                invitationCode = "A".repeat(43),
            )

        assertRejectedField(shortInvite, PrivateAccountInputField.INVITATION_CODE)
        assertRejectedField(oversizedPassword, PrivateAccountInputField.PASSWORD)
        assertRejectedField(controlPassword, PrivateAccountInputField.PASSWORD)
    }

    @Test
    fun `account drafts and commands redact invitation and password secrets`() {
        val invitationCode = "A".repeat(43)
        val draft =
            PrivateAccountAccessDraft.RegisterWithInvite(
                displayNameInput = "Peter",
                usernameInput = "peter_01",
                passwordInput = VALID_PASSWORD,
                passwordConfirmationInput = VALID_PASSWORD,
                invitationCodeInput = invitationCode,
            )
        val commandRendering =
            PrivateAccountAccessCommand
                .RegisterWithInvite(
                    displayName = PrivateDisplayName("Peter"),
                    username = PrivateUsername("peter_01"),
                    password = PrivateAccountPassword(VALID_PASSWORD),
                    invitationCode = PrivateInvitationCode(invitationCode),
                ).toString()

        assertFalse(draft.toString().contains(VALID_PASSWORD))
        assertFalse(draft.toString().contains(invitationCode))
        assertFalse(commandRendering.contains(VALID_PASSWORD))
        assertFalse(commandRendering.contains(invitationCode))
    }

    @Test
    fun `account denial rejects unbounded or unsafe server messages`() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivateAccountAccessOutcome.Denied("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateAccountAccessOutcome.Denied("x".repeat(201))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateAccountAccessOutcome.Denied("unsafe\nmessage")
        }
    }

    private companion object {
        const val VALID_PASSWORD = "correct-horse-battery"

        fun registrationDraft(
            password: String = VALID_PASSWORD,
            invitationCode: String,
        ): PrivateAccountAccessDraft.RegisterWithInvite =
            PrivateAccountAccessDraft.RegisterWithInvite(
                displayNameInput = "Peter",
                usernameInput = "peter_01",
                passwordInput = password,
                passwordConfirmationInput = password,
                invitationCodeInput = invitationCode,
            )

        fun assertRejectedField(
            draft: PrivateAccountAccessDraft,
            expectedField: PrivateAccountInputField,
        ) {
            when (val validation = validatePrivateAccountAccessDraft(draft)) {
                is PrivateAccountAccessValidation.Accepted -> fail("Expected rejected account draft")
                is PrivateAccountAccessValidation.Rejected -> assertEquals(expectedField, validation.field)
            }
        }
    }
}
