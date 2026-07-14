package app.synapse.localllm.data.remote

import android.content.Context
import androidx.core.net.toUri
import app.synapse.localllm.domain.remote.CreateRemoteGroupRoomCommand
import app.synapse.localllm.domain.remote.DeleteRemoteGroupRoomCommand
import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteGroupGateway
import app.synapse.localllm.domain.remote.RemoteGroupMember
import app.synapse.localllm.domain.remote.RemoteGroupMutation
import app.synapse.localllm.domain.remote.RemoteGroupMutationReceipt
import app.synapse.localllm.domain.remote.RemoteGroupRoomDetails
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteRoomMemberRole
import app.synapse.localllm.domain.remote.RenameRemoteGroupRoomCommand
import app.synapse.localllm.domain.remote.SetRemoteGroupAvatarCommand
import app.synapse.localllm.domain.remote.SetRemoteGroupMemberRoleCommand
import app.synapse.localllm.domain.remote.UpdateRemoteGroupMemberCommand
import app.synapse.localllm.domain.remote.UpdateRemoteGroupMembersCommand
import app.synapse.localllm.domain.remote.UpdateRemoteGroupPreferencesCommand
import app.synapse.localllm.domain.remote.isValidRemoteGroupRoomId
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

class FirebaseRemoteGroupGateway(
    context: Context,
    private val firebaseAuth: FirebaseAuth,
    private val functions: FirebaseFunctions,
    private val storage: FirebaseStorage,
    private val sessionController: RemoteAccountSessionController,
) : RemoteGroupGateway {
    private val applicationContext = context.applicationContext

    override suspend fun createGroupRoom(
        command: CreateRemoteGroupRoomCommand,
    ): RemoteGroupMutationReceipt {
        requireAuthenticatedUid(command.accountUid)
        require(command.memberUids.none { uid -> uid.raw == command.accountUid.raw }) {
            "Do not include yourself in the selected members."
        }
        require(command.memberUids.size in 1 until MAXIMUM_GROUP_MEMBERS) {
            "Choose between 1 and ${MAXIMUM_GROUP_MEMBERS - 1} group members."
        }
        val result = callGroupFunction(
            functionName = "createGroupRoom",
            payload = mapOf(
                "memberUids" to command.memberUids.map(RemoteProfileUid::raw).sorted(),
                "title" to normalizeGroupTitle(command.title),
            ),
            operation = "create the group",
        )
        val roomId = result.requireRoomId()
        return mutationReceipt(
            accountUid = command.accountUid,
            roomId = roomId,
            mutation = RemoteGroupMutation.CREATED,
            revision = result.requireLong("revision"),
        )
    }

    override suspend fun getGroupRoomDetails(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): RemoteGroupRoomDetails {
        requireAuthenticatedUid(accountUid)
        requireGroupRoomId(roomId)
        val result = callGroupFunction(
            functionName = "getGroupRoomDetails",
            payload = mapOf("roomId" to roomId.raw),
            operation = "load the group details",
        )
        check(result.requireRoomId() == roomId) { "Firebase returned a mismatched group identifier." }
        val members = result.requireList("members").map { memberValue ->
            val member = memberValue as? Map<*, *>
                ?: throw RemoteChatException("Firebase returned an invalid group member.")
            RemoteGroupMember(
                profileUid = RemoteProfileUid(member.requireString("uid")),
                role = member.requireRole("role"),
                joinedAt = Instant.ofEpochMilli(member.requireLong("joinedAtMillis")),
            )
        }
        val distinctMembers = members.distinctBy(RemoteGroupMember::profileUid)
        if (distinctMembers.size != members.size || members.size !in 1..MAXIMUM_GROUP_MEMBERS) {
            throw RemoteChatException("Firebase returned inconsistent group membership.")
        }
        val ownerUid = RemoteProfileUid(result.requireString("ownerUid"))
        if (members.count { member -> member.role == RemoteRoomMemberRole.OWNER } != 1 ||
            members.none { member -> member.profileUid == ownerUid && member.role == RemoteRoomMemberRole.OWNER }
        ) {
            throw RemoteChatException("Firebase returned inconsistent group ownership.")
        }
        val currentMembership = members.singleOrNull { member -> member.profileUid.raw == accountUid.raw }
            ?: throw RemoteChatException("Firebase did not confirm the active group membership.")
        val avatarObjectPath = result.optionalString("avatarObjectPath")
        avatarObjectPath?.let { path -> requireGroupAvatarObjectPath(roomId, path) }
        val avatarUrl = avatarObjectPath?.let { path -> resolveOptionalAvatarUrl(path) }
        return RemoteGroupRoomDetails(
            accountUid = accountUid,
            roomId = roomId,
            title = normalizeGroupTitle(result.requireString("title")),
            avatarObjectPath = avatarObjectPath,
            avatarUrl = avatarUrl,
            ownerUid = ownerUid,
            revision = result.requireLong("revision"),
            currentMemberRole = currentMembership.role,
            isArchived = result.requireBoolean("archived"),
            isMuted = result.requireBoolean("muted"),
            isPinned = result.requireBoolean("pinned"),
            members = members.sortedWith(
                compareBy<RemoteGroupMember> { member -> member.role.ordinal }
                    .thenBy { member -> member.joinedAt },
            ),
        )
    }

    override suspend fun addGroupMembers(
        command: UpdateRemoteGroupMembersCommand,
    ): RemoteGroupMutationReceipt =
        mutateMembers(
            command = command,
            functionName = "addGroupMembers",
            mutation = RemoteGroupMutation.MEMBERS_ADDED,
            operation = "add group members",
        )

    override suspend fun removeGroupMember(
        command: UpdateRemoteGroupMemberCommand,
    ): RemoteGroupMutationReceipt =
        mutateMember(
            command = command,
            functionName = "removeGroupMember",
            mutation = RemoteGroupMutation.MEMBER_REMOVED,
            operation = "remove the group member",
        )

    override suspend fun setGroupMemberRole(
        command: SetRemoteGroupMemberRoleCommand,
    ): RemoteGroupMutationReceipt {
        require(command.role == RemoteRoomMemberRole.ADMIN || command.role == RemoteRoomMemberRole.MEMBER) {
            "Choose the admin or member role."
        }
        return callRoomMutation(
            accountUid = command.accountUid,
            roomId = command.roomId,
            functionName = "setGroupMemberRole",
            mutation = RemoteGroupMutation.MEMBER_ROLE_CHANGED,
            operation = "change the group member role",
            payload = mapOf(
                "roomId" to command.roomId.raw,
                "targetUid" to command.targetUid.raw,
                "role" to command.role.name,
            ),
        )
    }

    override suspend fun transferGroupOwnership(
        command: UpdateRemoteGroupMemberCommand,
    ): RemoteGroupMutationReceipt =
        mutateMember(
            command = command,
            functionName = "transferGroupOwnership",
            mutation = RemoteGroupMutation.OWNERSHIP_TRANSFERRED,
            operation = "transfer group ownership",
        )

    override suspend fun leaveGroupRoom(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): RemoteGroupMutationReceipt =
        callRoomMutation(
            accountUid = accountUid,
            roomId = roomId,
            functionName = "leaveGroupRoom",
            mutation = RemoteGroupMutation.LEFT,
            operation = "leave the group",
            payload = mapOf("roomId" to roomId.raw),
        )

    override suspend fun renameGroupRoom(
        command: RenameRemoteGroupRoomCommand,
    ): RemoteGroupMutationReceipt =
        callRoomMutation(
            accountUid = command.accountUid,
            roomId = command.roomId,
            functionName = "renameGroupRoom",
            mutation = RemoteGroupMutation.RENAMED,
            operation = "rename the group",
            payload = mapOf(
                "roomId" to command.roomId.raw,
                "title" to normalizeGroupTitle(command.title),
            ),
        )

    override suspend fun setGroupAvatar(
        command: SetRemoteGroupAvatarCommand,
    ): RemoteGroupMutationReceipt {
        requireAuthenticatedUid(command.accountUid)
        requireGroupRoomId(command.roomId)
        command.previousAvatarObjectPath?.let { path ->
            requireGroupAvatarObjectPath(command.roomId, path)
        }
        val extension = allowedAvatarMimeTypes[command.mimeType]
            ?: throw IllegalArgumentException("Choose a JPEG, PNG, or WebP image.")
        val sourceUri = command.sourceUri.toUri()
        require(sourceUri.scheme == "content") { "Group avatar source must be an Android content URI." }
        val avatarBytes = applicationContext.contentResolver.openInputStream(sourceUri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(AVATAR_READ_BUFFER_BYTES)
            var byteCount = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                byteCount += read
                if (byteCount > MAXIMUM_AVATAR_BYTES) {
                    throw IllegalArgumentException("Group photos must be smaller than 5 MB.")
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw RemoteChatException("Android could not open the selected group photo.")
        require(avatarBytes.isNotEmpty()) { "The selected group photo is empty." }
        val avatarObjectPath = "groupAvatars/${command.roomId.raw}/avatar_" +
            "${UUID.randomUUID().toString().replace("-", "")}.$extension"
        val avatarReference = storage.reference.child(avatarObjectPath)
        val receipt = try {
            avatarReference.putBytes(
                avatarBytes,
                StorageMetadata.Builder().setContentType(command.mimeType).build(),
            ).await()
            callRoomMutation(
                accountUid = command.accountUid,
                roomId = command.roomId,
                functionName = "setGroupAvatar",
                mutation = RemoteGroupMutation.AVATAR_CHANGED,
                operation = "update the group photo",
                payload = mapOf(
                    "avatarObjectPath" to avatarObjectPath,
                    "roomId" to command.roomId.raw,
                ),
            )
        } catch (exception: CancellationException) {
            runCatching { avatarReference.delete().await() }
            throw exception
        } catch (exception: Exception) {
            runCatching { avatarReference.delete().await() }
            if (exception is RemoteChatException) throw exception
            throw exception.toGroupFailure("update the group photo")
        }
        deleteSupersededAvatar(command.previousAvatarObjectPath, avatarObjectPath)
        return receipt
    }

    override suspend fun clearGroupAvatar(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        previousAvatarObjectPath: String?,
    ): RemoteGroupMutationReceipt {
        previousAvatarObjectPath?.let { path -> requireGroupAvatarObjectPath(roomId, path) }
        val receipt = callRoomMutation(
            accountUid = accountUid,
            roomId = roomId,
            functionName = "setGroupAvatar",
            mutation = RemoteGroupMutation.AVATAR_CHANGED,
            operation = "remove the group photo",
            payload = mapOf("avatarObjectPath" to null, "roomId" to roomId.raw),
        )
        deleteSupersededAvatar(previousAvatarObjectPath, replacementPath = null)
        return receipt
    }

    override suspend fun updateGroupPreferences(
        command: UpdateRemoteGroupPreferencesCommand,
    ): RemoteGroupMutationReceipt =
        callRoomMutation(
            accountUid = command.accountUid,
            roomId = command.roomId,
            functionName = "updateGroupPreferences",
            mutation = RemoteGroupMutation.PREFERENCES_UPDATED,
            operation = "update the group preferences",
            payload = mapOf(
                "archived" to command.isArchived,
                "muted" to command.isMuted,
                "pinned" to command.isPinned,
                "roomId" to command.roomId.raw,
            ),
            expectsRevision = false,
        )

    override suspend fun deleteGroupRoom(
        command: DeleteRemoteGroupRoomCommand,
    ): RemoteGroupMutationReceipt =
        callRoomMutation(
            accountUid = command.accountUid,
            roomId = command.roomId,
            functionName = "deleteGroupRoom",
            mutation = RemoteGroupMutation.DELETED,
            operation = "delete the group",
            payload = mapOf(
                "confirmTitle" to command.confirmTitle.trim(),
                "roomId" to command.roomId.raw,
            ),
            expectsRevision = false,
        )

    private suspend fun mutateMembers(
        command: UpdateRemoteGroupMembersCommand,
        functionName: String,
        mutation: RemoteGroupMutation,
        operation: String,
    ): RemoteGroupMutationReceipt {
        require(command.memberUids.size in 1 until MAXIMUM_GROUP_MEMBERS) {
            "Choose between 1 and ${MAXIMUM_GROUP_MEMBERS - 1} group members."
        }
        return callRoomMutation(
            accountUid = command.accountUid,
            roomId = command.roomId,
            functionName = functionName,
            mutation = mutation,
            operation = operation,
            payload = mapOf(
                "memberUids" to command.memberUids.map(RemoteProfileUid::raw).sorted(),
                "roomId" to command.roomId.raw,
            ),
        )
    }

    private suspend fun mutateMember(
        command: UpdateRemoteGroupMemberCommand,
        functionName: String,
        mutation: RemoteGroupMutation,
        operation: String,
    ): RemoteGroupMutationReceipt =
        callRoomMutation(
            accountUid = command.accountUid,
            roomId = command.roomId,
            functionName = functionName,
            mutation = mutation,
            operation = operation,
            payload = mapOf(
                "roomId" to command.roomId.raw,
                "targetUid" to command.targetUid.raw,
            ),
        )

    private suspend fun callRoomMutation(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        functionName: String,
        mutation: RemoteGroupMutation,
        operation: String,
        payload: Map<String, Any?>,
        expectsRevision: Boolean = true,
    ): RemoteGroupMutationReceipt {
        requireAuthenticatedUid(accountUid)
        requireGroupRoomId(roomId)
        val result = callGroupFunction(functionName, payload, operation)
        check(result.requireRoomId() == roomId) { "Firebase returned a mismatched group identifier." }
        return mutationReceipt(
            accountUid = accountUid,
            roomId = roomId,
            mutation = mutation,
            revision = if (expectsRevision) result.requireLong("revision") else null,
        )
    }

    private suspend fun callGroupFunction(
        functionName: String,
        payload: Map<String, Any?>,
        operation: String,
    ): Map<*, *> =
        try {
            functions.getHttpsCallable(functionName).call(payload).await().data as? Map<*, *>
                ?: throw RemoteChatException("Firebase returned an invalid group receipt.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toGroupFailure(operation)
        }

    private suspend fun deleteSupersededAvatar(
        previousPath: String?,
        replacementPath: String?,
    ) {
        if (previousPath == null || previousPath == replacementPath) return
        // The callable is authoritative; stale-object cleanup must not roll back a committed avatar change.
        try {
            storage.reference.child(previousPath).delete().await()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return
        }
    }

    private suspend fun resolveOptionalAvatarUrl(objectPath: String): String? =
        try {
            storage.reference.child(objectPath).downloadUrl.await().toString()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }

    private fun requireAuthenticatedUid(accountUid: RemoteAccountUid) {
        sessionController.requireActiveToken(accountUid)
        if (firebaseAuth.currentUser?.uid != accountUid.raw) {
            throw RemoteChatException("The Firebase account session changed. Sign in again.")
        }
    }

    private companion object {
        const val AVATAR_READ_BUFFER_BYTES = 16 * 1024
        const val MAXIMUM_AVATAR_BYTES = 5 * 1024 * 1024
        const val MAXIMUM_GROUP_MEMBERS = 20
        val allowedAvatarMimeTypes = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
        )
    }
}

private fun Map<*, *>.requireRoomId(): RemoteRoomId =
    RemoteRoomId(requireString("roomId")).also(::requireGroupRoomId)

private fun Map<*, *>.requireString(fieldName: String): String =
    (this[fieldName] as? String)?.takeIf(String::isNotBlank)
        ?: throw RemoteChatException("Firebase returned an invalid $fieldName value.")

private fun Map<*, *>.optionalString(fieldName: String): String? {
    val field = this[fieldName] ?: return null
    return (field as? String)?.takeIf(String::isNotBlank)
        ?: throw RemoteChatException("Firebase returned an invalid $fieldName value.")
}

private fun Map<*, *>.requireBoolean(fieldName: String): Boolean =
    this[fieldName] as? Boolean
        ?: throw RemoteChatException("Firebase returned an invalid $fieldName value.")

private fun Map<*, *>.requireLong(fieldName: String): Long {
    val numericValue = this[fieldName] as? Number
        ?: throw RemoteChatException("Firebase returned an invalid $fieldName value.")
    val decimalValue = numericValue.toDouble()
    return numericValue.toLong().takeIf { value ->
        decimalValue.isFinite() && decimalValue == value.toDouble() && value >= 0L
    }
        ?: throw RemoteChatException("Firebase returned an invalid $fieldName value.")
}

private fun Map<*, *>.requireList(fieldName: String): List<*> =
    this[fieldName] as? List<*>
        ?: throw RemoteChatException("Firebase returned an invalid $fieldName value.")

private fun Map<*, *>.requireRole(fieldName: String): RemoteRoomMemberRole =
    requireString(fieldName).let { rawRole ->
        runCatching { RemoteRoomMemberRole.valueOf(rawRole) }.getOrNull()
            ?: throw RemoteChatException("Firebase returned an invalid $fieldName value.")
    }

private fun mutationReceipt(
    accountUid: RemoteAccountUid,
    roomId: RemoteRoomId,
    mutation: RemoteGroupMutation,
    revision: Long?,
): RemoteGroupMutationReceipt =
    RemoteGroupMutationReceipt(
        accountUid = accountUid,
        roomId = roomId,
        mutation = mutation,
        revision = revision,
    )

private fun requireGroupRoomId(roomId: RemoteRoomId) {
    require(isValidRemoteGroupRoomId(roomId.raw)) { "The group room identifier is invalid." }
}

private fun requireGroupAvatarObjectPath(
    roomId: RemoteRoomId,
    objectPath: String,
) {
    require(
        Regex("^groupAvatars/${roomId.raw}/avatar_[a-f0-9]{32}\\.(jpg|png|webp)$").matches(objectPath),
    ) { "The group avatar path is invalid." }
}

private fun normalizeGroupTitle(title: String): String {
    val normalizedTitle = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC).trim()
    require(
        normalizedTitle.isNotEmpty() &&
            normalizedTitle.length <= 80 &&
            normalizedTitle.none { character -> character.code < 32 || character.code == 127 },
    ) { "Group names must contain 1-80 visible characters." }
    return normalizedTitle
}

private fun Exception.toGroupFailure(operation: String): RemoteChatException {
    if (this is FirebaseFunctionsException &&
        code in setOf(
            FirebaseFunctionsException.Code.FAILED_PRECONDITION,
            FirebaseFunctionsException.Code.INVALID_ARGUMENT,
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
        )
    ) {
        return RemoteChatException(message ?: "Could not $operation.", this)
    }
    return toRemoteChatFailure(operation)
}
