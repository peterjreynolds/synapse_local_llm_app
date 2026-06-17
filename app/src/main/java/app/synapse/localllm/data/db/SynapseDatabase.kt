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
    ],
    version = 4,
    exportSchema = false,
)
abstract class SynapseDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    abstract fun memoryDao(): MemoryDao

    abstract fun storageHealthDao(): StorageHealthDao

    abstract fun diagnosticsDao(): DiagnosticsDao

    abstract fun libraryDao(): LibraryDao
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
