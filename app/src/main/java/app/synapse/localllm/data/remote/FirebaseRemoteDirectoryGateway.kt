package app.synapse.localllm.data.remote

import android.content.Context
import androidx.core.net.toUri
import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteDirectoryGateway
import app.synapse.localllm.domain.remote.RemoteProfileMutation
import app.synapse.localllm.domain.remote.RemoteProfileMutationReceipt
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.UpdateRemoteProfileCommand
import app.synapse.localllm.domain.remote.UploadRemoteAvatarCommand
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class FirebaseRemoteDirectoryGateway(
    context: Context,
    private val firebaseAuth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val sessionController: RemoteAccountSessionController,
) : RemoteDirectoryGateway {
    private val applicationContext = context.applicationContext

    override fun observeAllowedProfiles(accountUid: RemoteAccountUid): Flow<List<RemoteCachedProfile>> =
        callbackFlow {
            val token = sessionController.requireActiveToken(accountUid)
            requireAuthenticatedUid(accountUid)
            val registration = firestore.collection(PROFILES_COLLECTION)
                .whereEqualTo("allowed", true)
                .orderBy("usernameNormalized", Query.Direction.ASCENDING)
                .addSnapshotListener { snapshot, exception ->
                    if (exception != null) {
                        close(exception.toRemoteChatFailure("load the people directory"))
                        return@addSnapshotListener
                    }
                    trySend(snapshot.toProfiles(accountUid))
                }
            val registrationJob = launch {
                runCatching { sessionController.registerListener(token, registration) }
                    .onFailure(::close)
            }
            awaitClose {
                registrationJob.cancel()
                registration.remove()
            }
        }

    override suspend fun updateProfile(command: UpdateRemoteProfileCommand): RemoteProfileMutationReceipt {
        val displayName = command.displayName.trim()
        val bio = command.bio.trim()
        require(displayName.isNotEmpty() && displayName.length <= DISPLAY_NAME_LIMIT) {
            "Display name must contain 1-$DISPLAY_NAME_LIMIT characters."
        }
        require(bio.length <= PROFILE_BIO_LIMIT) { "Bio must contain at most $PROFILE_BIO_LIMIT characters." }
        requireAuthenticatedUid(command.accountUid)
        try {
            firestore.collection(PROFILES_COLLECTION).document(command.accountUid.raw).update(
                mapOf(
                    "bio" to bio,
                    "displayName" to displayName,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "online" to true,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("update the profile")
        }
        return RemoteProfileMutationReceipt(command.accountUid, RemoteProfileMutation.PROFILE_UPDATED)
    }

    override suspend fun updatePresence(
        accountUid: RemoteAccountUid,
        online: Boolean,
    ): RemoteProfileMutationReceipt {
        requireAuthenticatedUid(accountUid)
        try {
            firestore.collection(PROFILES_COLLECTION).document(accountUid.raw).update(
                mapOf(
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "online" to online,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("update account presence")
        }
        return RemoteProfileMutationReceipt(accountUid, RemoteProfileMutation.PRESENCE_UPDATED)
    }

    override suspend fun uploadAvatar(command: UploadRemoteAvatarCommand): RemoteProfileMutationReceipt {
        requireAuthenticatedUid(command.accountUid)
        val extension = allowedAvatarMimeTypes[command.mimeType]
            ?: throw IllegalArgumentException("Choose a JPEG, PNG, or WebP image.")
        val sourceUri = command.sourceUri.toUri()
        if (sourceUri.scheme != "content") {
            throw IllegalArgumentException("Avatar source must be an Android content URI.")
        }
        val avatarReference = storage.reference.child(
            "avatars/${command.accountUid.raw}/avatar.$extension",
        )
        try {
            val inputStream = applicationContext.contentResolver.openInputStream(sourceUri)
                ?: throw RemoteChatException("Android could not open the selected avatar.")
            inputStream.use { stream ->
                avatarReference.putStream(
                    stream,
                    StorageMetadata.Builder().setContentType(command.mimeType).build(),
                ).await()
            }
            val avatarUrl = avatarReference.downloadUrl.await().toString()
            firestore.collection(PROFILES_COLLECTION).document(command.accountUid.raw).update(
                mapOf(
                    "avatarUrl" to avatarUrl,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "online" to true,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("upload the avatar")
        }
        return RemoteProfileMutationReceipt(command.accountUid, RemoteProfileMutation.AVATAR_UPDATED)
    }

    private fun requireAuthenticatedUid(accountUid: RemoteAccountUid) {
        sessionController.requireActiveToken(accountUid)
        if (firebaseAuth.currentUser?.uid != accountUid.raw) {
            throw RemoteChatException("The Firebase account session changed. Sign in again.")
        }
    }

    private fun QuerySnapshot?.toProfiles(accountUid: RemoteAccountUid): List<RemoteCachedProfile> =
        this?.documents.orEmpty().mapNotNull { document ->
            val username = document.getString("username") ?: return@mapNotNull null
            val displayName = document.getString("displayName") ?: return@mapNotNull null
            val bio = document.getString("bio") ?: return@mapNotNull null
            val updatedAt = document.getTimestamp("updatedAt") ?: return@mapNotNull null
            if (username.isBlank() || displayName.isBlank() || document.getBoolean("allowed") != true) {
                return@mapNotNull null
            }
            RemoteCachedProfile(
                accountUid = accountUid,
                profileUid = RemoteProfileUid(document.id),
                username = username,
                displayName = displayName,
                bio = bio,
                avatarUrl = document.getString("avatarUrl"),
                isAllowed = true,
                isOnline = document.getBoolean("online") == true,
                lastSeenAt = document.getTimestamp("lastSeenAt")?.toInstant(),
                remoteUpdatedAt = updatedAt.toInstant(),
            )
        }

    private companion object {
        const val DISPLAY_NAME_LIMIT = 64
        const val PROFILE_BIO_LIMIT = 160
        const val PROFILES_COLLECTION = "profiles"
        val allowedAvatarMimeTypes = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
        )
    }
}
