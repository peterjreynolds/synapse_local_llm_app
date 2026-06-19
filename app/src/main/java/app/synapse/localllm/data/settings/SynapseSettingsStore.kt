package app.synapse.localllm.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.synapse.localllm.domain.runtime.ModelPromptProfile
import app.synapse.localllm.domain.settings.DEFAULT_CUSTOM_INSTRUCTIONS
import app.synapse.localllm.domain.settings.DEFAULT_PERSONA
import app.synapse.localllm.domain.settings.InferenceRuntimeBackend
import app.synapse.localllm.domain.settings.LEGACY_RAW_DATA_SYSTEM_PROMPT
import app.synapse.localllm.domain.settings.SynapseSettings
import app.synapse.localllm.domain.settings.composeSystemPrompt
import app.synapse.localllm.domain.settings.extractEditableCustomInstructions
import app.synapse.localllm.domain.settings.normalizeCustomInstructions
import app.synapse.localllm.domain.settings.normalizePersona
import app.synapse.localllm.domain.settings.normalizeSystemPrompt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.synapseSettingsDataStore by preferencesDataStore(name = "synapse_settings")

class SynapseSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.synapseSettingsDataStore

    val settingsFlow: Flow<SynapseSettings> =
        dataStore.data.map { preferences ->
            val persona = normalizePersona(preferences[PERSONA])
            val customInstructions = resolveCustomInstructions(
                persistedCustomInstructions = preferences[CUSTOM_INSTRUCTIONS],
                legacySystemPrompt = preferences[SYSTEM_PROMPT],
            )
            SynapseSettings(
                runtimeBackend = parseRuntimeBackend(preferences[RUNTIME_BACKEND]),
                baseUrl = preferences[BASE_URL] ?: "http://127.0.0.1:8080",
                modelName = preferences[MODEL_NAME] ?: "local-llama",
                embeddedModelPath = preferences[EMBEDDED_MODEL_PATH],
                embeddedModelDisplayName = preferences[EMBEDDED_MODEL_DISPLAY_NAME],
                embeddedModelByteCount = preferences[EMBEDDED_MODEL_BYTE_COUNT],
                modelPromptProfile = parseModelPromptProfile(preferences[MODEL_PROMPT_PROFILE]),
                persona = persona,
                customInstructions = customInstructions,
                systemPrompt = composeSystemPrompt(persona, customInstructions),
                temperature = preferences[TEMPERATURE] ?: 0.7,
                maxTokens = preferences[MAX_TOKENS] ?: 256,
                memoryWritesEnabled = preferences[MEMORY_WRITES_ENABLED] ?: true,
                speechPlaybackEnabled = preferences[SPEECH_PLAYBACK_ENABLED] ?: true,
                memoryDatabaseWarningBytes = preferences[MEMORY_DATABASE_WARNING_BYTES]
                    ?: 512L * 1024L * 1024L,
                attachmentCacheWarningBytes = preferences[ATTACHMENT_CACHE_WARNING_BYTES]
                    ?: 1024L * 1024L * 1024L,
                minimumFreeStorageBytes = preferences[MINIMUM_FREE_STORAGE_BYTES]
                    ?: 2L * 1024L * 1024L * 1024L,
            )
        }

    suspend fun updateRuntimeSettings(
        runtimeBackend: InferenceRuntimeBackend,
        baseUrl: String,
        modelName: String,
        persona: String,
        customInstructions: String,
        modelPromptProfile: ModelPromptProfile,
        temperature: Double,
        maxTokens: Int,
    ) {
        val normalizedPersona = normalizePersona(persona)
        val normalizedCustomInstructions = normalizeCustomInstructions(customInstructions)
        dataStore.edit { preferences ->
            preferences[RUNTIME_BACKEND] = runtimeBackend.name
            preferences[BASE_URL] = baseUrl.trim().removeSuffix("/")
            preferences[MODEL_NAME] = modelName.trim().ifBlank { "local-llama" }
            preferences[PERSONA] = normalizedPersona
            preferences[CUSTOM_INSTRUCTIONS] = normalizedCustomInstructions
            preferences[MODEL_PROMPT_PROFILE] = modelPromptProfile.name
            preferences[SYSTEM_PROMPT] = composeSystemPrompt(
                normalizedPersona,
                normalizedCustomInstructions,
            )
            preferences[TEMPERATURE] = temperature.coerceIn(0.0, 2.0)
            preferences[MAX_TOKENS] = maxTokens.coerceIn(1, 4096)
        }
    }

    suspend fun updateEmbeddedModel(
        modelPath: String,
        displayName: String,
        byteCount: Long?,
        modelPromptProfile: ModelPromptProfile? = null,
    ) {
        dataStore.edit { preferences ->
            preferences[RUNTIME_BACKEND] = InferenceRuntimeBackend.EMBEDDED_LLAMA.name
            preferences[EMBEDDED_MODEL_PATH] = modelPath
            preferences[EMBEDDED_MODEL_DISPLAY_NAME] = displayName
            if (byteCount == null) {
                preferences.remove(EMBEDDED_MODEL_BYTE_COUNT)
            } else {
                preferences[EMBEDDED_MODEL_BYTE_COUNT] = byteCount
            }
            preferences[MODEL_NAME] = displayName.removeSuffix(".gguf").ifBlank { "embedded-gguf" }
            preferences[MODEL_PROMPT_PROFILE] = (modelPromptProfile ?: inferModelPromptProfile(displayName)).name
        }
    }

    suspend fun updateMemoryWritesEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[MEMORY_WRITES_ENABLED] = enabled
        }
    }

    suspend fun updateSpeechPlaybackEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SPEECH_PLAYBACK_ENABLED] = enabled
        }
    }

    private fun parseRuntimeBackend(rawBackend: String?): InferenceRuntimeBackend =
        rawBackend
            ?.let { backend -> runCatching { InferenceRuntimeBackend.valueOf(backend) }.getOrNull() }
            ?: InferenceRuntimeBackend.EMBEDDED_LLAMA

    private fun parseModelPromptProfile(rawProfile: String?): ModelPromptProfile =
        rawProfile
            ?.let { profile -> runCatching { ModelPromptProfile.valueOf(profile) }.getOrNull() }
            ?: ModelPromptProfile.AUTO

    private fun inferModelPromptProfile(displayName: String): ModelPromptProfile {
        val normalizedName = displayName.lowercase()
        return when {
            "qwen" in normalizedName -> ModelPromptProfile.QWEN_CHATML
            "llama" in normalizedName -> ModelPromptProfile.AUTO
            else -> ModelPromptProfile.AUTO
        }
    }

    private fun resolveCustomInstructions(
        persistedCustomInstructions: String?,
        legacySystemPrompt: String?,
    ): String {
        if (!persistedCustomInstructions.isNullOrBlank()) {
            return normalizeCustomInstructions(persistedCustomInstructions)
        }

        val trimmedLegacyPrompt = legacySystemPrompt?.trim().orEmpty()
        val extractedLegacyInstructions = extractEditableCustomInstructions(trimmedLegacyPrompt)
        return when {
            trimmedLegacyPrompt.isBlank() -> DEFAULT_CUSTOM_INSTRUCTIONS
            trimmedLegacyPrompt == LEGACY_RAW_DATA_SYSTEM_PROMPT -> DEFAULT_CUSTOM_INSTRUCTIONS
            extractedLegacyInstructions != null -> extractedLegacyInstructions

            normalizeSystemPrompt(trimmedLegacyPrompt) == composeSystemPrompt(
                DEFAULT_PERSONA,
                DEFAULT_CUSTOM_INSTRUCTIONS,
            ) -> DEFAULT_CUSTOM_INSTRUCTIONS
            else -> trimmedLegacyPrompt
        }
    }

    private companion object {
        val RUNTIME_BACKEND = stringPreferencesKey("runtime_backend")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL_NAME = stringPreferencesKey("model_name")
        val EMBEDDED_MODEL_PATH = stringPreferencesKey("embedded_model_path")
        val EMBEDDED_MODEL_DISPLAY_NAME = stringPreferencesKey("embedded_model_display_name")
        val EMBEDDED_MODEL_BYTE_COUNT = longPreferencesKey("embedded_model_byte_count")
        val MODEL_PROMPT_PROFILE = stringPreferencesKey("model_prompt_profile")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val PERSONA = stringPreferencesKey("persona")
        val CUSTOM_INSTRUCTIONS = stringPreferencesKey("custom_instructions")
        val TEMPERATURE = doublePreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val MEMORY_WRITES_ENABLED = booleanPreferencesKey("memory_writes_enabled")
        val SPEECH_PLAYBACK_ENABLED = booleanPreferencesKey("speech_playback_enabled")
        val MEMORY_DATABASE_WARNING_BYTES = longPreferencesKey("memory_database_warning_bytes")
        val ATTACHMENT_CACHE_WARNING_BYTES = longPreferencesKey("attachment_cache_warning_bytes")
        val MINIMUM_FREE_STORAGE_BYTES = longPreferencesKey("minimum_free_storage_bytes")
    }
}
