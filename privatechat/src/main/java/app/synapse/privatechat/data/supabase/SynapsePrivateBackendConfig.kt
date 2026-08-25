package app.synapse.privatechat.data.supabase

import java.net.URI

class SynapsePrivateBackendConfig private constructor(
    val projectUri: URI,
    val publishableKey: String,
) {
    val projectOrigin: String = projectUri.toASCIIString()

    companion object {
        fun requireValid(
            projectUrl: String,
            publishableKey: String,
        ): SynapsePrivateBackendConfig {
            val parsedUri =
                try {
                    URI(projectUrl)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("Supabase project URL is invalid", error)
                }
            require(
                parsedUri.scheme == "https" &&
                    parsedUri.rawUserInfo == null &&
                    parsedUri.port == -1 &&
                    parsedUri.rawPath.isEmpty() &&
                    parsedUri.rawQuery == null &&
                    parsedUri.rawFragment == null &&
                    SUPABASE_HOST_PATTERN.matches(parsedUri.host.orEmpty()),
            ) {
                "Supabase project URL must be a canonical HTTPS project origin"
            }
            require(SUPABASE_PUBLISHABLE_KEY_PATTERN.matches(publishableKey)) {
                "Supabase client key must be a publishable key"
            }
            return SynapsePrivateBackendConfig(
                projectUri = parsedUri,
                publishableKey = publishableKey,
            )
        }
    }
}

private val SUPABASE_HOST_PATTERN = Regex("^[a-z0-9]+[.]supabase[.]co$")
private val SUPABASE_PUBLISHABLE_KEY_PATTERN = Regex("^sb_publishable_[A-Za-z0-9_-]{20,}$")
