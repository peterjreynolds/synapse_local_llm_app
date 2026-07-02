package app.synapse.localllm.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatThreadEntity::class,
        ChatMessageEntity::class,
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
    ],
    version = 8,
    exportSchema = false,
)
abstract class SynapseDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    abstract fun memoryDao(): MemoryDao

    abstract fun storageHealthDao(): StorageHealthDao

    abstract fun diagnosticsDao(): DiagnosticsDao

    abstract fun libraryDao(): LibraryDao

    abstract fun smsAutoReplyDao(): SmsAutoReplyDao
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
