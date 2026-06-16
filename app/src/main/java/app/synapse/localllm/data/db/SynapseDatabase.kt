package app.synapse.localllm.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatThreadEntity::class,
        ChatMessageEntity::class,
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
    version = 2,
    exportSchema = false,
)
abstract class SynapseDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    abstract fun memoryDao(): MemoryDao

    abstract fun storageHealthDao(): StorageHealthDao
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
