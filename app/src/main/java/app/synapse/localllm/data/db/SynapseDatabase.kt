package app.synapse.localllm.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

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
    version = 1,
    exportSchema = false,
)
abstract class SynapseDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    abstract fun memoryDao(): MemoryDao

    abstract fun storageHealthDao(): StorageHealthDao
}
