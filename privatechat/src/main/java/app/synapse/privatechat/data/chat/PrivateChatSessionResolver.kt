package app.synapse.privatechat.data.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import java.time.Clock

internal class PrivateChatSessionResolver(
    private val sessionProvider: PrivateChatAuthenticatedSessionProvider,
    private val payloadCache: PrivateDecryptedPayloadCacheRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun resolve(accountId: PrivateAccountId): PrivateChatAuthenticatedSession? {
        val session = sessionProvider.loadAuthenticatedSession()
        val now = clock.instant()
        if (
            session == null ||
            session.accountId != accountId ||
            !session.isUsableAt(now)
        ) {
            payloadCache.clearForSessionInvalidation()
            return null
        }
        return session
    }
}
