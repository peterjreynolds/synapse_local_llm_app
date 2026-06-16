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
        TraceEventEntity::class,
        MemoryObjectEntity::class,
        MemoryVersionEntity::class,
        MemorySupportEntity::class,
        MemoryWriteReceiptEntity::class,
        RetrievalReceiptEntity::class,
        RetrievedMemoryReceiptEntity::class,
        StorageHealthSnapshotEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class SynapseDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    abstract fun memoryDao(): MemoryDao

    abstract fun storageHealthDao(): StorageHealthDao

    abstract fun diagnosticsDao(): DiagnosticsDao
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
