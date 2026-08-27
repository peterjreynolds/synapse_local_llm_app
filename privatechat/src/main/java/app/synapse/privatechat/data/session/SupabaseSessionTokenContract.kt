package app.synapse.privatechat.data.session

internal object SupabaseSessionTokenContract {
    fun requireValidAccessToken(accessToken: String) {
        require(
            accessToken.length in ACCESS_TOKEN_LENGTH_RANGE &&
                ACCESS_TOKEN_PATTERN.matches(accessToken),
        ) {
            "Supabase access token is malformed"
        }
    }

    fun requireValidRefreshToken(refreshToken: String) {
        require(
            refreshToken.length in REFRESH_TOKEN_LENGTH_RANGE &&
                REFRESH_TOKEN_PATTERN.matches(refreshToken),
        ) {
            "Supabase refresh token is malformed"
        }
    }

    private val ACCESS_TOKEN_LENGTH_RANGE = 20..8_192
    // Supabase refresh tokens are opaque handles; current hosted projects can issue 12-character tokens.
    private val REFRESH_TOKEN_LENGTH_RANGE = 1..8_192
    private val ACCESS_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
    private val REFRESH_TOKEN_PATTERN = Regex("^[A-Za-z0-9._~-]+$")
}
