package app.synapse.localllm.data.calling

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import app.synapse.localllm.domain.calling.DirectCallRingtoneMutationReceipt
import app.synapse.localllm.domain.calling.DirectCallRingtoneRepository
import app.synapse.localllm.domain.calling.DirectCallRingtoneSelection
import app.synapse.localllm.domain.calling.DirectCallRingtoneSource
import app.synapse.localllm.domain.calling.PHONE_DEFAULT_RINGTONE_DISPLAY_NAME
import app.synapse.localllm.domain.time.SynapseClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidDirectCallRingtoneRepository private constructor(
    context: Context,
    private val preferences: SharedPreferences,
    private val clock: SynapseClock,
) : DirectCallRingtoneRepository {
    private val applicationContext = context.applicationContext
    private val contentResolver = applicationContext.contentResolver

    constructor(
        context: Context,
        clock: SynapseClock,
    ) : this(
        context = context,
        preferences = context.applicationContext.getSharedPreferences(
            DIRECT_CALL_RINGTONE_PREFERENCES,
            Context.MODE_PRIVATE,
        ),
        clock = clock,
    )

    internal constructor(
        context: Context,
        clock: SynapseClock,
        preferencesName: String,
    ) : this(
        context = context,
        preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
        clock = clock,
    )

    override fun currentSelection(): DirectCallRingtoneSelection {
        val source = preferences.getString(RINGTONE_SOURCE_KEY, null)
            ?.let { storedSource -> runCatching { DirectCallRingtoneSource.valueOf(storedSource) }.getOrNull() }
            ?: return DirectCallRingtoneSelection()
        if (source == DirectCallRingtoneSource.PHONE_DEFAULT) return DirectCallRingtoneSelection()
        val uri = preferences.getString(RINGTONE_URI_KEY, null)
            ?.takeIf { storedUri -> isStructurallyValidRingtoneUri(storedUri, source) }
            ?: return DirectCallRingtoneSelection()
        val displayName = preferences.getString(RINGTONE_DISPLAY_NAME_KEY, null)
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() && value.length <= MAXIMUM_RINGTONE_DISPLAY_NAME_LENGTH }
            ?: return DirectCallRingtoneSelection()
        return DirectCallRingtoneSelection(source, uri, displayName)
    }

    override suspend fun usePhoneDefaultRingtone(): DirectCallRingtoneMutationReceipt =
        persistSelection(DirectCallRingtoneSelection())

    override suspend fun selectPhoneRingtone(uri: String): DirectCallRingtoneMutationReceipt =
        withContext(Dispatchers.IO) {
            val parsedUri = parseSelectedUri(uri, allowAndroidResource = true)
            val ringtone = runCatching { RingtoneManager.getRingtone(applicationContext, parsedUri) }
                .getOrNull()
                ?: throw IllegalArgumentException("The selected phone ringtone is unavailable.")
            val title = runCatching { ringtone.getTitle(applicationContext) }
                .getOrNull()
                .toRingtoneDisplayName("Selected phone ringtone")
            persistSelection(
                DirectCallRingtoneSelection(
                    source = DirectCallRingtoneSource.PHONE_RINGTONE,
                    uri = parsedUri.toString(),
                    displayName = title,
                ),
            )
        }

    override suspend fun selectAudioFile(uri: String): DirectCallRingtoneMutationReceipt =
        withContext(Dispatchers.IO) {
            val parsedUri = parseSelectedUri(uri, allowAndroidResource = false)
            val mimeType = contentResolver.getType(parsedUri)
                ?.lowercase()
                ?.takeIf { value -> value.startsWith("audio/") }
                ?: throw IllegalArgumentException("Choose a readable audio file for the ringtone.")
            check(mimeType.length <= MAXIMUM_MIME_TYPE_LENGTH) { "The selected audio type is malformed." }
            val descriptor = runCatching { contentResolver.openAssetFileDescriptor(parsedUri, "r") }
                .getOrElse { throw IllegalArgumentException("The selected audio file cannot be read.", it) }
                ?: throw IllegalArgumentException("The selected audio file cannot be read.")
            descriptor.use { Unit }
            val displayName = queryDisplayName(parsedUri).toRingtoneDisplayName("Custom ringtone")
            val previousSelection = currentSelection()
            try {
                contentResolver.takePersistableUriPermission(
                    parsedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (exception: SecurityException) {
                throw IllegalArgumentException("Android could not retain access to the selected audio file.", exception)
            }
            try {
                persistSelection(
                    DirectCallRingtoneSelection(
                        source = DirectCallRingtoneSource.AUDIO_FILE,
                        uri = parsedUri.toString(),
                        displayName = displayName,
                    ),
                )
            } catch (exception: Exception) {
                if (previousSelection.uri != parsedUri.toString()) {
                    releasePersistedAudioPermission(parsedUri)
                }
                throw exception
            }
        }

    private suspend fun persistSelection(
        selection: DirectCallRingtoneSelection,
    ): DirectCallRingtoneMutationReceipt = withContext(Dispatchers.IO) {
        val previousSelection = currentSelection()
        val persisted = preferences.edit()
            .putString(RINGTONE_SOURCE_KEY, selection.source.name)
            .apply {
                if (selection.uri == null) {
                    remove(RINGTONE_URI_KEY)
                } else {
                    putString(RINGTONE_URI_KEY, selection.uri)
                }
                putString(RINGTONE_DISPLAY_NAME_KEY, selection.displayName)
            }
            .commit()
        check(persisted) { "Android could not persist the ringtone selection." }
        if (
            previousSelection.source == DirectCallRingtoneSource.AUDIO_FILE &&
            previousSelection.uri != selection.uri
        ) {
            previousSelection.uri?.let(Uri::parse)?.let(::releasePersistedAudioPermission)
        }
        DirectCallRingtoneMutationReceipt(selection, clock.now())
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (columnIndex < 0) null else cursor.getString(columnIndex)
        }
    }.getOrNull()

    private fun releasePersistedAudioPermission(uri: Uri) {
        runCatching {
            contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private companion object {
        const val DIRECT_CALL_RINGTONE_PREFERENCES = "synapse_direct_call_ringtone"
        const val RINGTONE_SOURCE_KEY = "ringtone_source_v1"
        const val RINGTONE_URI_KEY = "ringtone_uri_v1"
        const val RINGTONE_DISPLAY_NAME_KEY = "ringtone_display_name_v1"
        const val MAXIMUM_RINGTONE_URI_LENGTH = 4_096
        const val MAXIMUM_MIME_TYPE_LENGTH = 128

        fun parseSelectedUri(
            rawUri: String,
            allowAndroidResource: Boolean,
        ): Uri {
            if (rawUri.isBlank() || rawUri.length > MAXIMUM_RINGTONE_URI_LENGTH) {
                throw IllegalArgumentException("The selected ringtone address is invalid.")
            }
            val parsedUri = rawUri.toUri()
            val validScheme = parsedUri.scheme == "content" ||
                (allowAndroidResource && parsedUri.scheme == "android.resource")
            if (!validScheme || parsedUri.authority.isNullOrBlank()) {
                throw IllegalArgumentException("The selected ringtone address is invalid.")
            }
            return parsedUri
        }

        fun isStructurallyValidRingtoneUri(
            rawUri: String,
            source: DirectCallRingtoneSource,
        ): Boolean = runCatching {
            parseSelectedUri(
                rawUri,
                allowAndroidResource = source == DirectCallRingtoneSource.PHONE_RINGTONE,
            )
        }.isSuccess
    }
}

private fun String?.toRingtoneDisplayName(fallback: String): String =
    this
        ?.replace(DISPLAY_NAME_CONTROL_CHARACTERS, " ")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(MAXIMUM_RINGTONE_DISPLAY_NAME_LENGTH)
        ?: fallback

private const val MAXIMUM_RINGTONE_DISPLAY_NAME_LENGTH = 128
private val DISPLAY_NAME_CONTROL_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]")
