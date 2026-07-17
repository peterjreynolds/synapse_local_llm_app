package app.synapse.localllm.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatThreadEntity::class,
        ChatMessageEntity::class,
        ChatParticipantEntity::class,
        RoomMembershipEntity::class,
        ChatMessageAuthorEntity::class,
        AssistantGenerationTraceEntity::class,
        AttachmentEntity::class,
        LibraryArtifactEntity::class,
        LibraryArtifactWriteReceiptEntity::class,
        TraceEventEntity::class,
        MemoryObjectEntity::class,
        MemoryVersionEntity::class,
        MemorySupportEntity::class,
        MemoryWriteReceiptEntity::class,
        RetrievalReceiptEntity::class,
        RetrievedMemoryReceiptEntity::class,
        StorageHealthSnapshotEntity::class,
        SmsSenderThreadEntity::class,
        SmsAutoReplyReceiptEntity::class,
        RemoteProfileCacheEntity::class,
        RemoteRoomCacheEntity::class,
        RemoteMessageCacheEntity::class,
        RemoteMessageSearchEntity::class,
        RemoteMessageOutboxEntity::class,
        RemoteMessageDraftEntity::class,
        RemoteRoomLocalStateEntity::class,
        RemoteMessageLocalStateEntity::class,
        RemoteSyncCursorEntity::class,
    ],
    version = 16,
    exportSchema = false,
)
abstract class SynapseDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    abstract fun memoryDao(): MemoryDao

    abstract fun storageHealthDao(): StorageHealthDao

    abstract fun diagnosticsDao(): DiagnosticsDao

    abstract fun libraryDao(): LibraryDao

    abstract fun smsAutoReplyDao(): SmsAutoReplyDao

    abstract fun remoteChatCacheDao(): RemoteChatCacheDao
}

val SYNAPSE_DATABASE_MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chat_threads ADD COLUMN pinnedAtEpochMillis INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE chat_threads ADD COLUMN archivedAtEpochMillis INTEGER DEFAULT NULL")
            db.execSQL(
                """
                ALTER TABLE chat_threads
                ADD COLUMN titleEditedByUser INTEGER NOT NULL DEFAULT 0
                """.trimIndent(),
            )
        }
    }

val SYNAPSE_DATABASE_MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS assistant_generation_traces (
                    id TEXT NOT NULL PRIMARY KEY,
                    assistantMessageId TEXT NOT NULL,
                    backend TEXT NOT NULL,
                    modelName TEXT NOT NULL,
                    promptMessageCount INTEGER NOT NULL,
                    promptCharacterCount INTEGER NOT NULL,
                    retrievedMemoryCount INTEGER NOT NULL,
                    maxTokens INTEGER NOT NULL,
                    temperature REAL NOT NULL,
                    startedAtEpochMillis INTEGER NOT NULL,
                    completedAtEpochMillis INTEGER,
                    rawTokenEvents INTEGER NOT NULL,
                    rawCharacterCount INTEGER NOT NULL,
                    visibleCharacterCount INTEGER NOT NULL,
                    filteredCharacterCount INTEGER NOT NULL,
                    firstRawTokenAtEpochMillis INTEGER,
                    firstVisibleTokenAtEpochMillis INTEGER,
                    stopReason TEXT,
                    failureReason TEXT,
                    FOREIGN KEY(assistantMessageId) REFERENCES chat_messages(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_assistant_generation_traces_assistantMessageId
                ON assistant_generation_traces(assistantMessageId)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_assistant_generation_traces_startedAtEpochMillis
                ON assistant_generation_traces(startedAtEpochMillis)
                """.trimIndent(),
            )
            db.execSQL(
                """
                UPDATE chat_messages
                SET deliveryState = 'FAILED',
                    completedAtEpochMillis = COALESCE(completedAtEpochMillis, createdAtEpochMillis),
                    failureReason = 'Model returned no visible answer text after hidden reasoning/output filtering.'
                WHERE role = 'ASSISTANT'
                  AND deliveryState = 'COMPLETE'
                  AND length(trim(body)) = 0
                """.trimIndent(),
            )
        }
    }

val SYNAPSE_DATABASE_MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS library_artifacts (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    displayName TEXT NOT NULL,
                    relativePath TEXT NOT NULL,
                    mimeType TEXT NOT NULL,
                    artifactKind TEXT NOT NULL,
                    sourceKind TEXT NOT NULL,
                    sha256 TEXT NOT NULL,
                    byteCount INTEGER NOT NULL,
                    catalogSummary TEXT,
                    tagsCsv TEXT NOT NULL,
                    createdAtEpochMillis INTEGER NOT NULL,
                    updatedAtEpochMillis INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_library_artifacts_artifactKind
                ON library_artifacts(artifactKind)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_library_artifacts_sourceKind
                ON library_artifacts(sourceKind)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_library_artifacts_sha256
                ON library_artifacts(sha256)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_library_artifacts_updatedAtEpochMillis
                ON library_artifacts(updatedAtEpochMillis)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS library_artifact_write_receipts (
                    id TEXT NOT NULL PRIMARY KEY,
                    artifactId TEXT NOT NULL,
                    mutation TEXT NOT NULL,
                    writtenAtEpochMillis INTEGER NOT NULL,
                    reason TEXT NOT NULL,
                    byteCount INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    FOREIGN KEY(artifactId) REFERENCES library_artifacts(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_library_artifact_write_receipts_artifactId
                ON library_artifact_write_receipts(artifactId)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_library_artifact_write_receipts_writtenAtEpochMillis
                ON library_artifact_write_receipts(writtenAtEpochMillis)
                """.trimIndent(),
            )
        }
    }

val SYNAPSE_DATABASE_MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE memory_versions ADD COLUMN scope TEXT NOT NULL DEFAULT 'GLOBAL'")
            db.execSQL("ALTER TABLE memory_versions ADD COLUMN subject TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE memory_versions ADD COLUMN keywordsCsv TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_memory_versions_scope
                ON memory_versions(scope)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_memory_versions_subject
                ON memory_versions(subject)
                """.trimIndent(),
            )
        }
    }

val SYNAPSE_DATABASE_MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE memory_objects ADD COLUMN claimKey TEXT DEFAULT NULL")
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_memory_objects_claimKey
                ON memory_objects(claimKey)
                """.trimIndent(),
            )
            db.execSQL("ALTER TABLE retrieval_receipts ADD COLUMN retrievalIntent TEXT NOT NULL DEFAULT 'GENERAL'")
            db.execSQL("ALTER TABLE retrieved_memory_receipts ADD COLUMN rankScore REAL NOT NULL DEFAULT 0.0")
        }
    }

val SYNAPSE_DATABASE_MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE memory_versions ADD COLUMN domain TEXT NOT NULL DEFAULT 'GIST'")
            db.execSQL("ALTER TABLE memory_versions ADD COLUMN predicate TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE memory_versions ADD COLUMN valueText TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE memory_versions ADD COLUMN sourceQuote TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE memory_versions ADD COLUMN writeIntent TEXT NOT NULL DEFAULT 'EXPLICIT_SAVE'")
            db.execSQL("ALTER TABLE memory_versions ADD COLUMN durabilityScore REAL NOT NULL DEFAULT 1.0")
            db.execSQL("ALTER TABLE memory_versions ADD COLUMN futureUsefulnessScore REAL NOT NULL DEFAULT 1.0")
            db.execSQL("ALTER TABLE memory_versions ADD COLUMN sensitivity TEXT NOT NULL DEFAULT 'LOW'")
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_memory_versions_domain
                ON memory_versions(domain)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_memory_versions_predicate
                ON memory_versions(predicate)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_memory_versions_writeIntent
                ON memory_versions(writeIntent)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_memory_versions_sensitivity
                ON memory_versions(sensitivity)
                """.trimIndent(),
            )
        }
    }

val SYNAPSE_DATABASE_MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sms_sender_threads (
                    senderAddress TEXT NOT NULL PRIMARY KEY,
                    threadId TEXT NOT NULL,
                    createdAtEpochMillis INTEGER NOT NULL,
                    updatedAtEpochMillis INTEGER NOT NULL,
                    FOREIGN KEY(threadId) REFERENCES chat_threads(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_sms_sender_threads_threadId
                ON sms_sender_threads(threadId)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sms_auto_reply_receipts (
                    id TEXT NOT NULL PRIMARY KEY,
                    inboundMessageKey TEXT NOT NULL,
                    senderAddress TEXT NOT NULL,
                    inboundBodySha256 TEXT NOT NULL,
                    inboundCharacterCount INTEGER NOT NULL,
                    inboundReceivedAtEpochMillis INTEGER NOT NULL,
                    threadId TEXT,
                    userMessageId TEXT,
                    assistantMessageId TEXT,
                    state TEXT NOT NULL,
                    replyBodySha256 TEXT,
                    replyCharacterCount INTEGER NOT NULL,
                    smsPartCount INTEGER NOT NULL,
                    queuedAtEpochMillis INTEGER,
                    decidedAtEpochMillis INTEGER NOT NULL,
                    failureReason TEXT,
                    FOREIGN KEY(threadId) REFERENCES chat_threads(id)
                    ON UPDATE NO ACTION ON DELETE SET NULL,
                    FOREIGN KEY(userMessageId) REFERENCES chat_messages(id)
                    ON UPDATE NO ACTION ON DELETE SET NULL,
                    FOREIGN KEY(assistantMessageId) REFERENCES chat_messages(id)
                    ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS index_sms_auto_reply_receipts_inboundMessageKey
                ON sms_auto_reply_receipts(inboundMessageKey)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_sms_auto_reply_receipts_senderAddress
                ON sms_auto_reply_receipts(senderAddress)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_sms_auto_reply_receipts_threadId
                ON sms_auto_reply_receipts(threadId)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_sms_auto_reply_receipts_userMessageId
                ON sms_auto_reply_receipts(userMessageId)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_sms_auto_reply_receipts_assistantMessageId
                ON sms_auto_reply_receipts(assistantMessageId)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_sms_auto_reply_receipts_decidedAtEpochMillis
                ON sms_auto_reply_receipts(decidedAtEpochMillis)
                """.trimIndent(),
            )
        }
    }

val SYNAPSE_DATABASE_MIGRATION_8_9 =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            requireKnownLegacyConversationRoles(db)

            db.execSQL("ALTER TABLE chat_threads ADD COLUMN roomKind TEXT NOT NULL DEFAULT 'AI_CHAT'")
            db.execSQL("ALTER TABLE chat_threads ADD COLUMN remoteId TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE chat_threads ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chat_threads ADD COLUMN syncState TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_threads_roomKind ON chat_threads(roomKind)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_threads_remoteId ON chat_threads(remoteId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_threads_syncState ON chat_threads(syncState)")

            db.execSQL("ALTER TABLE chat_messages ADD COLUMN remoteId TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN syncState TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_remoteId ON chat_messages(remoteId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_syncState ON chat_messages(syncState)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chat_participants (
                    id TEXT NOT NULL PRIMARY KEY,
                    kind TEXT NOT NULL,
                    displayName TEXT NOT NULL,
                    avatarUri TEXT,
                    avatarColorArgb INTEGER,
                    remoteId TEXT DEFAULT NULL,
                    revision INTEGER NOT NULL DEFAULT 0,
                    syncState TEXT NOT NULL DEFAULT 'LOCAL_ONLY',
                    createdAtEpochMillis INTEGER NOT NULL,
                    updatedAtEpochMillis INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_participants_kind ON chat_participants(kind)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_participants_remoteId ON chat_participants(remoteId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_participants_syncState ON chat_participants(syncState)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS room_memberships (
                    roomId TEXT NOT NULL,
                    participantId TEXT NOT NULL,
                    role TEXT NOT NULL,
                    canPost INTEGER NOT NULL,
                    joinedAtEpochMillis INTEGER NOT NULL,
                    leftAtEpochMillis INTEGER,
                    aiResponsePolicy TEXT NOT NULL,
                    remoteId TEXT DEFAULT NULL,
                    revision INTEGER NOT NULL DEFAULT 0,
                    syncState TEXT NOT NULL DEFAULT 'LOCAL_ONLY',
                    PRIMARY KEY(roomId, participantId),
                    FOREIGN KEY(roomId) REFERENCES chat_threads(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(participantId) REFERENCES chat_participants(id)
                    ON UPDATE NO ACTION ON DELETE NO ACTION
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_room_memberships_participantId " +
                    "ON room_memberships(participantId)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_room_memberships_leftAtEpochMillis " +
                    "ON room_memberships(leftAtEpochMillis)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_room_memberships_syncState " +
                    "ON room_memberships(syncState)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chat_message_authors (
                    messageId TEXT NOT NULL PRIMARY KEY,
                    authorParticipantId TEXT NOT NULL,
                    FOREIGN KEY(messageId) REFERENCES chat_messages(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(authorParticipantId) REFERENCES chat_participants(id)
                    ON UPDATE NO ACTION ON DELETE NO ACTION
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_chat_message_authors_authorParticipantId " +
                    "ON chat_message_authors(authorParticipantId)",
            )

            db.execSQL("ALTER TABLE sms_sender_threads ADD COLUMN participantId TEXT DEFAULT NULL")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sms_sender_threads_participantId " +
                    "ON sms_sender_threads(participantId)",
            )

            insertBuiltInParticipants(db)
            db.execSQL("UPDATE chat_threads SET roomKind = 'AI_CHAT'")
            insertLegacyCoreRoomMemberships(db)
            insertLegacySystemRoomMemberships(db)
            insertLegacyMessageAuthors(db)
            insertLegacySmsParticipants(db)
            assertEveryLegacyMessageHasAuthor(db)
        }
    }

val SYNAPSE_DATABASE_MIGRATION_9_10 =
    object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS remote_profile_cache (
                    accountUid TEXT NOT NULL,
                    profileUid TEXT NOT NULL,
                    username TEXT NOT NULL,
                    usernameNormalized TEXT NOT NULL,
                    displayName TEXT NOT NULL,
                    bio TEXT NOT NULL,
                    avatarUrl TEXT,
                    isAllowed INTEGER NOT NULL,
                    isOnline INTEGER NOT NULL,
                    lastSeenAtEpochMillis INTEGER,
                    remoteUpdatedAtEpochMillis INTEGER NOT NULL,
                    cachedAtEpochMillis INTEGER NOT NULL,
                    PRIMARY KEY(accountUid, profileUid)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_profile_cache_accountUid " +
                    "ON remote_profile_cache(accountUid)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_remote_profile_cache_accountUid_usernameNormalized " +
                    "ON remote_profile_cache(accountUid, usernameNormalized)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS remote_direct_room_cache (
                    accountUid TEXT NOT NULL,
                    remoteRoomId TEXT NOT NULL,
                    directKey TEXT NOT NULL,
                    peerUid TEXT NOT NULL,
                    title TEXT NOT NULL,
                    unreadCount INTEGER NOT NULL,
                    latestMessagePreview TEXT,
                    latestMessageSenderUid TEXT,
                    remoteUpdatedAtEpochMillis INTEGER NOT NULL,
                    cachedAtEpochMillis INTEGER NOT NULL,
                    PRIMARY KEY(accountUid, remoteRoomId)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_direct_room_cache_accountUid " +
                    "ON remote_direct_room_cache(accountUid)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_direct_room_cache_accountUid_peerUid " +
                    "ON remote_direct_room_cache(accountUid, peerUid)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_direct_room_cache_accountUid_remoteUpdatedAtEpochMillis " +
                    "ON remote_direct_room_cache(accountUid, remoteUpdatedAtEpochMillis)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS remote_room_membership_cache (
                    accountUid TEXT NOT NULL,
                    remoteRoomId TEXT NOT NULL,
                    memberUid TEXT NOT NULL,
                    role TEXT NOT NULL,
                    isActive INTEGER NOT NULL,
                    joinedAtEpochMillis INTEGER NOT NULL,
                    lastReadAtEpochMillis INTEGER,
                    cachedAtEpochMillis INTEGER NOT NULL,
                    PRIMARY KEY(accountUid, remoteRoomId, memberUid),
                    FOREIGN KEY(accountUid, remoteRoomId)
                    REFERENCES remote_direct_room_cache(accountUid, remoteRoomId)
                    ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_room_membership_cache_accountUid_remoteRoomId " +
                    "ON remote_room_membership_cache(accountUid, remoteRoomId)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_room_membership_cache_accountUid_memberUid " +
                    "ON remote_room_membership_cache(accountUid, memberUid)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS remote_message_cache (
                    accountUid TEXT NOT NULL,
                    remoteRoomId TEXT NOT NULL,
                    remoteMessageId TEXT NOT NULL,
                    idempotencyKey TEXT NOT NULL,
                    senderUid TEXT NOT NULL,
                    authorKind TEXT NOT NULL,
                    body TEXT NOT NULL,
                    deliveryState TEXT NOT NULL,
                    clientCreatedAtEpochMillis INTEGER NOT NULL,
                    serverCreatedAtEpochMillis INTEGER,
                    failureReason TEXT,
                    cachedAtEpochMillis INTEGER NOT NULL,
                    PRIMARY KEY(accountUid, remoteRoomId, remoteMessageId),
                    FOREIGN KEY(accountUid, remoteRoomId)
                    REFERENCES remote_direct_room_cache(accountUid, remoteRoomId)
                    ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_message_cache_accountUid_remoteRoomId " +
                    "ON remote_message_cache(accountUid, remoteRoomId)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "index_remote_message_cache_accountUid_remoteRoomId_idempotencyKey " +
                    "ON remote_message_cache(accountUid, remoteRoomId, idempotencyKey)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "index_remote_message_cache_accountUid_remoteRoomId_serverCreatedAtEpochMillis " +
                    "ON remote_message_cache(accountUid, remoteRoomId, serverCreatedAtEpochMillis)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS remote_message_outbox (
                    accountUid TEXT NOT NULL,
                    operationId TEXT NOT NULL,
                    remoteRoomId TEXT NOT NULL,
                    remoteMessageId TEXT NOT NULL,
                    idempotencyKey TEXT NOT NULL,
                    senderUid TEXT NOT NULL,
                    body TEXT NOT NULL,
                    state TEXT NOT NULL,
                    attemptCount INTEGER NOT NULL,
                    createdAtEpochMillis INTEGER NOT NULL,
                    lastAttemptAtEpochMillis INTEGER,
                    failureReason TEXT,
                    PRIMARY KEY(accountUid, operationId),
                    FOREIGN KEY(accountUid, remoteRoomId)
                    REFERENCES remote_direct_room_cache(accountUid, remoteRoomId)
                    ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_message_outbox_accountUid_remoteRoomId " +
                    "ON remote_message_outbox(accountUid, remoteRoomId)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "index_remote_message_outbox_accountUid_remoteRoomId_idempotencyKey " +
                    "ON remote_message_outbox(accountUid, remoteRoomId, idempotencyKey)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_message_outbox_accountUid_state " +
                    "ON remote_message_outbox(accountUid, state)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS remote_sync_cursors (
                    accountUid TEXT NOT NULL,
                    collectionName TEXT NOT NULL,
                    scopeId TEXT NOT NULL,
                    serverTimestampEpochMillis INTEGER NOT NULL,
                    documentId TEXT NOT NULL,
                    updatedAtEpochMillis INTEGER NOT NULL,
                    PRIMARY KEY(accountUid, collectionName, scopeId)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_sync_cursors_accountUid " +
                    "ON remote_sync_cursors(accountUid)",
            )
        }
    }

val SYNAPSE_DATABASE_MIGRATION_10_11 =
    object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS remote_room_cache (
                    accountUid TEXT NOT NULL,
                    remoteRoomId TEXT NOT NULL,
                    roomKind TEXT NOT NULL,
                    directKey TEXT,
                    peerUid TEXT,
                    title TEXT NOT NULL,
                    avatarObjectPath TEXT,
                    unreadCount INTEGER NOT NULL,
                    latestMessagePreview TEXT,
                    latestMessageSenderUid TEXT,
                    currentMemberRole TEXT NOT NULL,
                    notificationsEnabled INTEGER NOT NULL,
                    isMuted INTEGER NOT NULL,
                    isArchived INTEGER NOT NULL,
                    isPinned INTEGER NOT NULL,
                    joinedAtEpochMillis INTEGER NOT NULL,
                    lastReadAtEpochMillis INTEGER,
                    remoteUpdatedAtEpochMillis INTEGER NOT NULL,
                    cachedAtEpochMillis INTEGER NOT NULL,
                    PRIMARY KEY(accountUid, remoteRoomId)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO remote_room_cache (
                    accountUid, remoteRoomId, roomKind, directKey, peerUid, title,
                    avatarObjectPath, unreadCount, latestMessagePreview, latestMessageSenderUid,
                    currentMemberRole, notificationsEnabled, isMuted, isArchived, isPinned,
                    joinedAtEpochMillis, lastReadAtEpochMillis,
                    remoteUpdatedAtEpochMillis, cachedAtEpochMillis
                )
                SELECT
                    room.accountUid,
                    room.remoteRoomId,
                    'DIRECT',
                    room.directKey,
                    room.peerUid,
                    room.title,
                    NULL,
                    room.unreadCount,
                    room.latestMessagePreview,
                    room.latestMessageSenderUid,
                    COALESCE(membership.role, 'MEMBER'),
                    1,
                    0,
                    0,
                    0,
                    COALESCE(membership.joinedAtEpochMillis, room.remoteUpdatedAtEpochMillis),
                    membership.lastReadAtEpochMillis,
                    room.remoteUpdatedAtEpochMillis,
                    room.cachedAtEpochMillis
                FROM remote_direct_room_cache AS room
                LEFT JOIN remote_room_membership_cache AS membership
                  ON membership.accountUid = room.accountUid
                 AND membership.remoteRoomId = room.remoteRoomId
                 AND membership.memberUid = room.accountUid
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_room_cache_accountUid " +
                    "ON remote_room_cache(accountUid)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_room_cache_accountUid_peerUid " +
                    "ON remote_room_cache(accountUid, peerUid)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_room_cache_accountUid_remoteUpdatedAtEpochMillis " +
                    "ON remote_room_cache(accountUid, remoteUpdatedAtEpochMillis)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS remote_message_cache_v11 (
                    accountUid TEXT NOT NULL,
                    remoteRoomId TEXT NOT NULL,
                    remoteMessageId TEXT NOT NULL,
                    idempotencyKey TEXT NOT NULL,
                    senderUid TEXT NOT NULL,
                    authorKind TEXT NOT NULL,
                    body TEXT NOT NULL,
                    deliveryState TEXT NOT NULL,
                    clientCreatedAtEpochMillis INTEGER NOT NULL,
                    serverCreatedAtEpochMillis INTEGER,
                    failureReason TEXT,
                    cachedAtEpochMillis INTEGER NOT NULL,
                    PRIMARY KEY(accountUid, remoteRoomId, remoteMessageId),
                    FOREIGN KEY(accountUid, remoteRoomId)
                    REFERENCES remote_room_cache(accountUid, remoteRoomId)
                    ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO remote_message_cache_v11
                SELECT * FROM remote_message_cache
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS remote_message_outbox_v11 (
                    accountUid TEXT NOT NULL,
                    operationId TEXT NOT NULL,
                    remoteRoomId TEXT NOT NULL,
                    remoteMessageId TEXT NOT NULL,
                    idempotencyKey TEXT NOT NULL,
                    senderUid TEXT NOT NULL,
                    body TEXT NOT NULL,
                    state TEXT NOT NULL,
                    attemptCount INTEGER NOT NULL,
                    createdAtEpochMillis INTEGER NOT NULL,
                    lastAttemptAtEpochMillis INTEGER,
                    failureReason TEXT,
                    PRIMARY KEY(accountUid, operationId),
                    FOREIGN KEY(accountUid, remoteRoomId)
                    REFERENCES remote_room_cache(accountUid, remoteRoomId)
                    ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO remote_message_outbox_v11
                SELECT * FROM remote_message_outbox
                """.trimIndent(),
            )

            db.execSQL("DROP TABLE remote_message_outbox")
            db.execSQL("DROP TABLE remote_message_cache")
            db.execSQL("DROP TABLE remote_room_membership_cache")
            db.execSQL("DROP TABLE remote_direct_room_cache")
            db.execSQL("ALTER TABLE remote_message_cache_v11 RENAME TO remote_message_cache")
            db.execSQL("ALTER TABLE remote_message_outbox_v11 RENAME TO remote_message_outbox")

            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_message_cache_accountUid_remoteRoomId " +
                    "ON remote_message_cache(accountUid, remoteRoomId)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "index_remote_message_cache_accountUid_remoteRoomId_idempotencyKey " +
                    "ON remote_message_cache(accountUid, remoteRoomId, idempotencyKey)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "index_remote_message_cache_accountUid_remoteRoomId_serverCreatedAtEpochMillis " +
                    "ON remote_message_cache(accountUid, remoteRoomId, serverCreatedAtEpochMillis)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_message_outbox_accountUid_remoteRoomId " +
                    "ON remote_message_outbox(accountUid, remoteRoomId)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "index_remote_message_outbox_accountUid_remoteRoomId_idempotencyKey " +
                    "ON remote_message_outbox(accountUid, remoteRoomId, idempotencyKey)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_message_outbox_accountUid_state " +
                    "ON remote_message_outbox(accountUid, state)",
            )
        }
    }

val SYNAPSE_DATABASE_MIGRATION_11_12 =
    object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE remote_message_cache ADD COLUMN replyToMessageId TEXT")
            db.execSQL("ALTER TABLE remote_message_cache ADD COLUMN editedAtEpochMillis INTEGER")
            db.execSQL("ALTER TABLE remote_message_cache ADD COLUMN deletedAtEpochMillis INTEGER")
            db.execSQL("ALTER TABLE remote_message_cache ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
            db.execSQL(
                "ALTER TABLE remote_message_cache " +
                    "ADD COLUMN reactionCountsJson TEXT NOT NULL DEFAULT '{}'",
            )
            db.execSQL(
                "ALTER TABLE remote_message_cache " +
                    "ADD COLUMN deliveredToCount INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE remote_message_cache " +
                    "ADD COLUMN readByCount INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL("ALTER TABLE remote_message_outbox ADD COLUMN replyToMessageId TEXT")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS remote_message_drafts (
                    accountUid TEXT NOT NULL,
                    remoteRoomId TEXT NOT NULL,
                    body TEXT NOT NULL,
                    updatedAtEpochMillis INTEGER NOT NULL,
                    PRIMARY KEY(accountUid, remoteRoomId),
                    FOREIGN KEY(accountUid, remoteRoomId)
                    REFERENCES remote_room_cache(accountUid, remoteRoomId)
                    ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_message_drafts_accountUid " +
                    "ON remote_message_drafts(accountUid)",
            )
        }
    }

val SYNAPSE_DATABASE_MIGRATION_12_13 =
    object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE remote_message_cache " +
                    "ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT '[]'",
            )
            db.execSQL(
                "ALTER TABLE remote_message_outbox " +
                    "ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT '[]'",
            )
        }
    }

val SYNAPSE_DATABASE_MIGRATION_13_14 =
    object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE remote_room_cache ADD COLUMN mutedUntilEpochMillis INTEGER")
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS remote_message_search USING FTS4(" +
                    "accountUid, remoteRoomId, remoteMessageId, body, tokenize=unicode61)",
            )
            db.execSQL(
                "INSERT INTO remote_message_search(accountUid, remoteRoomId, remoteMessageId, body) " +
                    "SELECT accountUid, remoteRoomId, remoteMessageId, body " +
                    "FROM remote_message_cache WHERE deletedAtEpochMillis IS NULL AND body != ''",
            )
        }
    }

val SYNAPSE_DATABASE_MIGRATION_14_15 =
    object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE remote_message_cache ADD COLUMN aiParticipantId TEXT")
            db.execSQL("ALTER TABLE remote_message_cache ADD COLUMN aiProvenance TEXT")
        }
    }

val SYNAPSE_DATABASE_MIGRATION_15_16 =
    object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS remote_room_local_state (
                    accountUid TEXT NOT NULL,
                    remoteRoomId TEXT NOT NULL,
                    hiddenThroughRemoteUpdatedAtEpochMillis INTEGER,
                    messagesHiddenThroughEpochMillis INTEGER,
                    updatedAtEpochMillis INTEGER NOT NULL,
                    PRIMARY KEY(accountUid, remoteRoomId),
                    FOREIGN KEY(accountUid, remoteRoomId)
                    REFERENCES remote_room_cache(accountUid, remoteRoomId)
                    ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_room_local_state_accountUid " +
                    "ON remote_room_local_state(accountUid)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS remote_message_local_state (
                    accountUid TEXT NOT NULL,
                    remoteRoomId TEXT NOT NULL,
                    remoteMessageId TEXT NOT NULL,
                    hiddenAtEpochMillis INTEGER NOT NULL,
                    PRIMARY KEY(accountUid, remoteRoomId, remoteMessageId),
                    FOREIGN KEY(accountUid, remoteRoomId, remoteMessageId)
                    REFERENCES remote_message_cache(accountUid, remoteRoomId, remoteMessageId)
                    ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_remote_message_local_state_accountUid_remoteRoomId " +
                    "ON remote_message_local_state(accountUid, remoteRoomId)",
            )
        }
    }

private fun requireKnownLegacyConversationRoles(db: SupportSQLiteDatabase) {
    db.query(
        """
        SELECT role
        FROM chat_messages
        WHERE role NOT IN ('USER', 'ASSISTANT', 'SYSTEM')
        LIMIT 1
        """.trimIndent(),
    ).use { cursor ->
        check(!cursor.moveToFirst()) {
            "Cannot migrate chat message with unsupported conversation role '${cursor.getString(0)}'."
        }
    }
}

private fun insertBuiltInParticipants(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        INSERT INTO chat_participants (
            id, kind, displayName, avatarUri, avatarColorArgb,
            remoteId, revision, syncState, createdAtEpochMillis, updatedAtEpochMillis
        )
        VALUES (
            '$LOCAL_HUMAN_PARTICIPANT_ID',
            'HUMAN',
            'You',
            NULL,
            NULL,
            NULL,
            0,
            'LOCAL_ONLY',
            COALESCE((SELECT MIN(createdAtEpochMillis) FROM chat_threads), 0),
            COALESCE((SELECT MAX(updatedAtEpochMillis) FROM chat_threads), 0)
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        INSERT INTO chat_participants (
            id, kind, displayName, avatarUri, avatarColorArgb,
            remoteId, revision, syncState, createdAtEpochMillis, updatedAtEpochMillis
        )
        VALUES (
            '$SYNAPSE_LOCAL_AI_PARTICIPANT_ID',
            'LOCAL_AI',
            'Synapse',
            NULL,
            NULL,
            NULL,
            0,
            'LOCAL_ONLY',
            COALESCE((SELECT MIN(createdAtEpochMillis) FROM chat_threads), 0),
            COALESCE((SELECT MAX(updatedAtEpochMillis) FROM chat_threads), 0)
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        INSERT INTO chat_participants (
            id, kind, displayName, avatarUri, avatarColorArgb,
            remoteId, revision, syncState, createdAtEpochMillis, updatedAtEpochMillis
        )
        VALUES (
            '$SYSTEM_PARTICIPANT_ID',
            'SYSTEM',
            'System',
            NULL,
            NULL,
            NULL,
            0,
            'LOCAL_ONLY',
            COALESCE((SELECT MIN(createdAtEpochMillis) FROM chat_threads), 0),
            COALESCE((SELECT MAX(updatedAtEpochMillis) FROM chat_threads), 0)
        )
        """.trimIndent(),
    )
}

private fun insertLegacyCoreRoomMemberships(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        INSERT INTO room_memberships (
            roomId, participantId, role, canPost, joinedAtEpochMillis,
            leftAtEpochMillis, aiResponsePolicy, remoteId, revision, syncState
        )
        SELECT
            id,
            '$LOCAL_HUMAN_PARTICIPANT_ID',
            'OWNER',
            1,
            createdAtEpochMillis,
            NULL,
            'NEVER',
            NULL,
            0,
            'LOCAL_ONLY'
        FROM chat_threads
        """.trimIndent(),
    )
    db.execSQL(
        """
        INSERT INTO room_memberships (
            roomId, participantId, role, canPost, joinedAtEpochMillis,
            leftAtEpochMillis, aiResponsePolicy, remoteId, revision, syncState
        )
        SELECT
            id,
            '$SYNAPSE_LOCAL_AI_PARTICIPANT_ID',
            'MEMBER',
            1,
            createdAtEpochMillis,
            NULL,
            'AUTOMATIC',
            NULL,
            0,
            'LOCAL_ONLY'
        FROM chat_threads
        """.trimIndent(),
    )
}

private fun insertLegacySystemRoomMemberships(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        INSERT INTO room_memberships (
            roomId, participantId, role, canPost, joinedAtEpochMillis,
            leftAtEpochMillis, aiResponsePolicy, remoteId, revision, syncState
        )
        SELECT
            thread.id,
            '$SYSTEM_PARTICIPANT_ID',
            'MEMBER',
            1,
            thread.createdAtEpochMillis,
            NULL,
            'NEVER',
            NULL,
            0,
            'LOCAL_ONLY'
        FROM chat_threads AS thread
        WHERE EXISTS (
            SELECT 1
            FROM chat_messages AS message
            WHERE message.threadId = thread.id
              AND message.role = 'SYSTEM'
        )
        """.trimIndent(),
    )
}

private fun insertLegacyMessageAuthors(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        INSERT INTO chat_message_authors (messageId, authorParticipantId)
        SELECT
            id,
            CASE role
                WHEN 'USER' THEN '$LOCAL_HUMAN_PARTICIPANT_ID'
                WHEN 'ASSISTANT' THEN '$SYNAPSE_LOCAL_AI_PARTICIPANT_ID'
                WHEN 'SYSTEM' THEN '$SYSTEM_PARTICIPANT_ID'
            END
        FROM chat_messages
        """.trimIndent(),
    )
}

private fun insertLegacySmsParticipants(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        INSERT INTO chat_participants (
            id, kind, displayName, avatarUri, avatarColorArgb,
            remoteId, revision, syncState, createdAtEpochMillis, updatedAtEpochMillis
        )
        SELECT
            '$SMS_PARTICIPANT_ID_PREFIX' || lower(hex(CAST(senderAddress AS BLOB))),
            'HUMAN',
            senderAddress,
            NULL,
            NULL,
            NULL,
            0,
            'LOCAL_ONLY',
            createdAtEpochMillis,
            updatedAtEpochMillis
        FROM sms_sender_threads
        """.trimIndent(),
    )
    db.execSQL(
        """
        UPDATE sms_sender_threads
        SET participantId = '$SMS_PARTICIPANT_ID_PREFIX' || lower(hex(CAST(senderAddress AS BLOB)))
        """.trimIndent(),
    )
    db.execSQL(
        """
        INSERT INTO room_memberships (
            roomId, participantId, role, canPost, joinedAtEpochMillis,
            leftAtEpochMillis, aiResponsePolicy, remoteId, revision, syncState
        )
        SELECT
            threadId,
            participantId,
            'MEMBER',
            1,
            createdAtEpochMillis,
            NULL,
            'NEVER',
            NULL,
            0,
            'LOCAL_ONLY'
        FROM sms_sender_threads
        WHERE participantId IS NOT NULL
        """.trimIndent(),
    )
}

private fun assertEveryLegacyMessageHasAuthor(db: SupportSQLiteDatabase) {
    db.query(
        """
        SELECT COUNT(*)
        FROM chat_messages AS message
        LEFT JOIN chat_message_authors AS authorship
          ON authorship.messageId = message.id
        WHERE authorship.messageId IS NULL
        """.trimIndent(),
    ).use { cursor ->
        check(cursor.moveToFirst() && cursor.getLong(0) == 0L) {
            "Chat authorship backfill did not cover every legacy message."
        }
    }
}

private const val LOCAL_HUMAN_PARTICIPANT_ID = "participant-local-human"
private const val SYNAPSE_LOCAL_AI_PARTICIPANT_ID = "participant-synapse-local-ai"
private const val SYSTEM_PARTICIPANT_ID = "participant-system"
private const val SMS_PARTICIPANT_ID_PREFIX = "participant-sms-"
