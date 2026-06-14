package app.synapse.localllm.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.synapse.localllm.domain.settings.DEFAULT_SYSTEM_PROMPT
import app.synapse.localllm.domain.settings.SynapseSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.synapseSettingsDataStore by preferencesDataStore(name = "synapse_settings")

class SynapseSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.synapseSettingsDataStore

    val settingsFlow: Flow<SynapseSettings> =
        dataStore.data.map { preferences ->
            SynapseSettings(
                baseUrl = preferences[BASE_URL] ?: "http://127.0.0.1:8080",
                modelName = preferences[MODEL_NAME] ?: "local-llama",
                systemPrompt = preferences[SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT,
                temperature = preferences[TEMPERATURE] ?: 0.7,
                maxTokens = preferences[MAX_TOKENS] ?: 768,
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
        baseUrl: String,
        modelName: String,
        systemPrompt: String,
        temperature: Double,
        maxTokens: Int,
    ) {
        dataStore.edit { preferences ->
            preferences[BASE_URL] = baseUrl.trim().removeSuffix("/")
            preferences[MODEL_NAME] = modelName.trim().ifBlank { "local-llama" }
            preferences[SYSTEM_PROMPT] = systemPrompt.trim().ifBlank { DEFAULT_SYSTEM_PROMPT }
            preferences[TEMPERATURE] = temperature.coerceIn(0.0, 2.0)
            preferences[MAX_TOKENS] = maxTokens.coerceIn(64, 4096)
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

    private companion object {
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL_NAME = stringPreferencesKey("model_name")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val TEMPERATURE = doublePreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val MEMORY_WRITES_ENABLED = booleanPreferencesKey("memory_writes_enabled")
        val SPEECH_PLAYBACK_ENABLED = booleanPreferencesKey("speech_playback_enabled")
        val MEMORY_DATABASE_WARNING_BYTES = longPreferencesKey("memory_database_warning_bytes")
        val ATTACHMENT_CACHE_WARNING_BYTES = longPreferencesKey("attachment_cache_warning_bytes")
        val MINIMUM_FREE_STORAGE_BYTES = longPreferencesKey("minimum_free_storage_bytes")
    }
}
