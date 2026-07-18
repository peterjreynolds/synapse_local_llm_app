package app.synapse.localllm.data.appearance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.synapse.localllm.domain.appearance.ChatAppearance
import app.synapse.localllm.domain.appearance.ChatAppearanceMutationReceipt
import app.synapse.localllm.domain.appearance.ChatAppearanceRepository
import app.synapse.localllm.domain.appearance.ChatBackground
import app.synapse.localllm.domain.appearance.ChatBubblePaletteMutationReceipt
import app.synapse.localllm.domain.appearance.ChatBubblePalette
import app.synapse.localllm.domain.appearance.DEFAULT_CHAT_MESSAGE_SCALE
import app.synapse.localllm.domain.appearance.clampChatMessageScale
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.time.SynapseClock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

class AndroidChatAppearanceRepository private constructor(
    private val dataStore: DataStore<Preferences>,
    private val clock: SynapseClock,
) : ChatAppearanceRepository {
    constructor(
        context: Context,
        clock: SynapseClock,
    ) : this(
        dataStore = createChatAppearanceDataStore(context.applicationContext),
        clock = clock,
    )

    internal constructor(
        context: Context,
        clock: SynapseClock,
        storageFileName: String,
    ) : this(
        dataStore = createChatAppearanceDataStore(context.applicationContext, storageFileName),
        clock = clock,
    )

    override fun observeAccountBubblePalette(
        accountUid: RemoteAccountUid,
    ): Flow<ChatBubblePalette> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            decodeAccountBubblePalettes(preferences[ACCOUNT_BUBBLE_PALETTE_ENTRIES])
                .firstOrNull { entry -> entry.accountUid == accountUid.raw }
                ?.bubblePalette
                ?: ChatBubblePalette.SYNAPSE
        }

    override fun observeAppearance(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): Flow<ChatAppearance> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            val storedAppearance = decodeAppearanceEntries(preferences[CHAT_APPEARANCE_ENTRIES])
                .firstOrNull { entry -> entry.accountUid == accountUid.raw && entry.roomId == roomId.raw }
                ?.appearance
                ?: ChatAppearance()
            val accountBubblePalette = decodeAccountBubblePalettes(preferences[ACCOUNT_BUBBLE_PALETTE_ENTRIES])
                .firstOrNull { entry -> entry.accountUid == accountUid.raw }
                ?.bubblePalette
                ?: storedAppearance.bubblePalette
            storedAppearance.copy(bubblePalette = accountBubblePalette)
        }

    override suspend fun saveAppearance(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        appearance: ChatAppearance,
    ): ChatAppearanceMutationReceipt {
        val persistedAt = clock.now()
        var persistedAppearance = appearance
        dataStore.edit { preferences ->
            val accountBubblePalette = decodeAccountBubblePalettes(preferences[ACCOUNT_BUBBLE_PALETTE_ENTRIES])
                .firstOrNull { entry -> entry.accountUid == accountUid.raw }
                ?.bubblePalette
                ?: appearance.bubblePalette
            persistedAppearance = appearance.copy(bubblePalette = accountBubblePalette)
            val retainedEntries = decodeAppearanceEntries(preferences[CHAT_APPEARANCE_ENTRIES])
                .filterNot { entry -> entry.accountUid == accountUid.raw && entry.roomId == roomId.raw }
                .sortedByDescending(StoredChatAppearance::updatedAtEpochMillis)
                .take(MAXIMUM_STORED_APPEARANCES - 1)
            preferences[CHAT_APPEARANCE_ENTRIES] = encodeAppearanceEntries(
                listOf(
                    StoredChatAppearance(
                        accountUid = accountUid.raw,
                        roomId = roomId.raw,
                        appearance = persistedAppearance,
                        updatedAtEpochMillis = persistedAt.toEpochMilli(),
                    ),
                ) + retainedEntries,
            )
        }
        return ChatAppearanceMutationReceipt(accountUid, roomId, persistedAppearance, persistedAt)
    }

    override suspend fun resetAppearance(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): ChatAppearanceMutationReceipt {
        val persistedAt = clock.now()
        var resetAppearance = ChatAppearance()
        dataStore.edit { preferences ->
            val accountBubblePalette = decodeAccountBubblePalettes(preferences[ACCOUNT_BUBBLE_PALETTE_ENTRIES])
                .firstOrNull { entry -> entry.accountUid == accountUid.raw }
                ?.bubblePalette
                ?: ChatBubblePalette.SYNAPSE
            resetAppearance = ChatAppearance(bubblePalette = accountBubblePalette)
            val retainedEntries = decodeAppearanceEntries(preferences[CHAT_APPEARANCE_ENTRIES])
                .filterNot { entry -> entry.accountUid == accountUid.raw && entry.roomId == roomId.raw }
            if (retainedEntries.isEmpty()) {
                preferences.remove(CHAT_APPEARANCE_ENTRIES)
            } else {
                preferences[CHAT_APPEARANCE_ENTRIES] = encodeAppearanceEntries(retainedEntries)
            }
        }
        return ChatAppearanceMutationReceipt(accountUid, roomId, resetAppearance, persistedAt)
    }

    override suspend fun saveAccountBubblePalette(
        accountUid: RemoteAccountUid,
        bubblePalette: ChatBubblePalette,
    ): ChatBubblePaletteMutationReceipt {
        val persistedAt = clock.now()
        dataStore.edit { preferences ->
            val retainedEntries = decodeAccountBubblePalettes(preferences[ACCOUNT_BUBBLE_PALETTE_ENTRIES])
                .filterNot { entry -> entry.accountUid == accountUid.raw }
                .sortedByDescending(StoredAccountBubblePalette::updatedAtEpochMillis)
                .take(MAXIMUM_STORED_ACCOUNT_PALETTES - 1)
            preferences[ACCOUNT_BUBBLE_PALETTE_ENTRIES] = encodeAccountBubblePalettes(
                listOf(StoredAccountBubblePalette(accountUid.raw, bubblePalette, persistedAt.toEpochMilli())) +
                    retainedEntries,
            )
        }
        return ChatBubblePaletteMutationReceipt(accountUid, bubblePalette, persistedAt)
    }

    private companion object {
        const val MAXIMUM_STORED_ACCOUNT_PALETTES = 50
        const val MAXIMUM_STORED_APPEARANCES = 500
        val ACCOUNT_BUBBLE_PALETTE_ENTRIES = stringPreferencesKey("account_bubble_palette_entries_v1")
        val CHAT_APPEARANCE_ENTRIES = stringPreferencesKey("chat_appearance_entries_v1")
    }
}

private data class StoredChatAppearance(
    val accountUid: String,
    val roomId: String,
    val appearance: ChatAppearance,
    val updatedAtEpochMillis: Long,
)

private data class StoredAccountBubblePalette(
    val accountUid: String,
    val bubblePalette: ChatBubblePalette,
    val updatedAtEpochMillis: Long,
)

private fun encodeAppearanceEntries(entries: List<StoredChatAppearance>): String =
    JSONArray().apply {
        entries.forEach { entry ->
            put(
                JSONObject()
                    .put("accountUid", entry.accountUid)
                    .put("roomId", entry.roomId)
                    .put("bubblePalette", entry.appearance.bubblePalette.name)
                    .put("background", entry.appearance.background.name)
                    .put("messageScale", entry.appearance.messageScale.toDouble())
                    .put("updatedAtEpochMillis", entry.updatedAtEpochMillis),
            )
        }
    }.toString()

private fun decodeAppearanceEntries(encodedEntries: String?): List<StoredChatAppearance> {
    if (encodedEntries.isNullOrBlank()) return emptyList()
    return runCatching {
        val entries = JSONArray(encodedEntries)
        buildList {
            repeat(entries.length().coerceAtMost(MAXIMUM_DECODED_APPEARANCES)) { index ->
                val entry = entries.getJSONObject(index)
                val accountUid = entry.getString("accountUid")
                val roomId = entry.getString("roomId")
                if (accountUid.isBlank() || roomId.isBlank()) return@repeat
                val bubblePalette = runCatching {
                    ChatBubblePalette.valueOf(entry.getString("bubblePalette"))
                }.getOrNull() ?: return@repeat
                val background = runCatching {
                    ChatBackground.valueOf(entry.getString("background"))
                }.getOrNull() ?: return@repeat
                val messageScale = clampChatMessageScale(
                    entry.optDouble("messageScale", DEFAULT_CHAT_MESSAGE_SCALE.toDouble()).toFloat(),
                )
                add(
                    StoredChatAppearance(
                        accountUid = accountUid,
                        roomId = roomId,
                        appearance = ChatAppearance(bubblePalette, background, messageScale),
                        updatedAtEpochMillis = entry.optLong("updatedAtEpochMillis", 0L),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun encodeAccountBubblePalettes(entries: List<StoredAccountBubblePalette>): String =
    JSONArray().apply {
        entries.forEach { entry ->
            put(
                JSONObject()
                    .put("accountUid", entry.accountUid)
                    .put("bubblePalette", entry.bubblePalette.name)
                    .put("updatedAtEpochMillis", entry.updatedAtEpochMillis),
            )
        }
    }.toString()

private fun decodeAccountBubblePalettes(encodedEntries: String?): List<StoredAccountBubblePalette> {
    if (encodedEntries.isNullOrBlank()) return emptyList()
    return runCatching {
        val entries = JSONArray(encodedEntries)
        buildList {
            repeat(entries.length().coerceAtMost(MAXIMUM_DECODED_ACCOUNT_PALETTES)) { index ->
                val entry = entries.getJSONObject(index)
                val accountUid = entry.getString("accountUid")
                if (accountUid.isBlank()) return@repeat
                val bubblePalette = runCatching {
                    ChatBubblePalette.valueOf(entry.getString("bubblePalette"))
                }.getOrNull() ?: return@repeat
                add(
                    StoredAccountBubblePalette(
                        accountUid = accountUid,
                        bubblePalette = bubblePalette,
                        updatedAtEpochMillis = entry.optLong("updatedAtEpochMillis", 0L),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun createChatAppearanceDataStore(
    context: Context,
    storageFileName: String = "synapse_chat_appearance.preferences_pb",
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    produceFile = { File(context.filesDir, storageFileName) },
)

private const val MAXIMUM_DECODED_APPEARANCES = 500
private const val MAXIMUM_DECODED_ACCOUNT_PALETTES = 50
