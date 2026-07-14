package app.synapse.localllm.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.data.settings.SynapseSettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SynapseDatabaseMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DATABASE_NAME)
    }

    @Test
    fun migration6To7AddsGeneralizedMemoryColumnsWithDefaults() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion6MemoryVersionsTable(db)
                            db.execSQL(
                                """
                                INSERT INTO memory_versions (
                                    id,
                                    memoryObjectId,
                                    text,
                                    confidence,
                                    surfacePolicy,
                                    sourceTraceEventIdsCsv,
                                    scope,
                                    subject,
                                    keywordsCsv,
                                    createdAtEpochMillis
                                )
                                VALUES (
                                    'version-1',
                                    'memory-1',
                                    'User prefers concise Kotlin.',
                                    0.95,
                                    'PROMPT_VISIBLE',
                                    'trace-1',
                                    'GLOBAL',
                                    'self',
                                    'kotlin,concise',
                                    1781712000000
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        helper.writableDatabase.use { database ->
            SYNAPSE_DATABASE_MIGRATION_6_7.migrate(database)

            val columnNames = database.query("PRAGMA table_info(memory_versions)").use { cursor ->
                buildSet {
                    val nameColumnIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) {
                        add(cursor.getString(nameColumnIndex))
                    }
                }
            }
            assertTrue(columnNames.containsAll(expectedVersion7MemoryColumns))

            database.query(
                """
                SELECT domain, writeIntent, durabilityScore, futureUsefulnessScore, sensitivity
                FROM memory_versions
                WHERE id = 'version-1'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("GIST", cursor.getString(0))
                assertEquals("EXPLICIT_SAVE", cursor.getString(1))
                assertEquals(1.0, cursor.getDouble(2), 0.0)
                assertEquals(1.0, cursor.getDouble(3), 0.0)
                assertEquals("LOW", cursor.getString(4))
            }
        }
    }

    @Test
    fun migration7To8AddsSmsAutoReplyTables() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion7ChatTables(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        helper.writableDatabase.use { database ->
            SYNAPSE_DATABASE_MIGRATION_7_8.migrate(database)

            val receiptColumns = database.query("PRAGMA table_info(sms_auto_reply_receipts)").use { cursor ->
                buildSet {
                    val nameColumnIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) {
                        add(cursor.getString(nameColumnIndex))
                    }
                }
            }
            assertTrue(receiptColumns.containsAll(expectedSmsAutoReplyReceiptColumns))

            database.execSQL(
                """
                INSERT INTO chat_threads (
                    id,
                    title,
                    pinnedAtEpochMillis,
                    archivedAtEpochMillis,
                    titleEditedByUser,
                    createdAtEpochMillis,
                    updatedAtEpochMillis
                )
                VALUES ('thread-sms', 'SMS +15551234567', NULL, NULL, 0, 1781712000000, 1781712000000)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO sms_sender_threads (
                    senderAddress,
                    threadId,
                    createdAtEpochMillis,
                    updatedAtEpochMillis
                )
                VALUES ('+15551234567', 'thread-sms', 1781712000000, 1781712000000)
                """.trimIndent(),
            )

            database.query(
                "SELECT threadId FROM sms_sender_threads WHERE senderAddress = '+15551234567'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("thread-sms", cursor.getString(0))
            }
        }
    }

    @Test
    fun migration8To11PreservesRowsAndBackfillsRoomParticipantsAndAuthors() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(8) {
                        override fun onConfigure(db: SupportSQLiteDatabase) {
                            db.setForeignKeyConstraintsEnabled(true)
                        }

                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion8PersistenceFixture(db)
                            seedVersion8PersistenceFixture(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val legacyRowsBeforeMigration = readVersion8LegacyRows(helper.writableDatabase)
        helper.close()

        val database = Room.databaseBuilder(
            context,
            SynapseDatabase::class.java,
            TEST_DATABASE_NAME,
        )
            .addMigrations(
                SYNAPSE_DATABASE_MIGRATION_8_9,
                SYNAPSE_DATABASE_MIGRATION_9_10,
                SYNAPSE_DATABASE_MIGRATION_10_11,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val migratedDatabase = database.openHelper.writableDatabase

            assertEquals(11, migratedDatabase.version)
            assertEquals(legacyRowsBeforeMigration, readVersion8LegacyRows(migratedDatabase))
            assertMainThreadPreservedWithVersion9Defaults(migratedDatabase)
            assertArchivedThreadPreserved(migratedDatabase)
            assertMessagesAndChildRowsPreserved(migratedDatabase)
            assertMemoryReferencesPreserved(migratedDatabase)
            assertSmsRowsAndSenderParticipantPreserved(migratedDatabase)
            assertBuiltInParticipantsAndMembershipsBackfilled(migratedDatabase)
            assertEveryMessageHasExactlyOneExpectedAuthor(migratedDatabase)
            assertForeignKeysRemainValid(migratedDatabase)
        } finally {
            database.close()
        }
    }

    @Test
    fun migration9To11AddsAccountScopedRemoteCacheWithoutChangingLocalState() = runTest {
        val settingsStore = SynapseSettingsStore(context)
        settingsStore.updateMemoryWritesEnabled(false)
        settingsStore.updateSmsAutoReplyEnabled(true)
        val settingsBeforeMigration = settingsStore.settingsFlow.first()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(9) {
                        override fun onConfigure(db: SupportSQLiteDatabase) {
                            db.setForeignKeyConstraintsEnabled(true)
                        }

                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion8PersistenceFixture(db)
                            seedVersion8PersistenceFixture(db)
                            SYNAPSE_DATABASE_MIGRATION_8_9.migrate(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val version9RowsBeforeMigration = readVersion9LocalRows(helper.writableDatabase)
        helper.close()

        val database = Room.databaseBuilder(
            context,
            SynapseDatabase::class.java,
            TEST_DATABASE_NAME,
        )
            .addMigrations(
                SYNAPSE_DATABASE_MIGRATION_9_10,
                SYNAPSE_DATABASE_MIGRATION_10_11,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val migratedDatabase = database.openHelper.writableDatabase

            assertEquals(11, migratedDatabase.version)
            assertEquals(version9RowsBeforeMigration, readVersion9LocalRows(migratedDatabase))
            assertEquals(settingsBeforeMigration, settingsStore.settingsFlow.first())
            version11RemoteCacheTables.forEach { tableName ->
                assertEquals(0L, queryCount(migratedDatabase, tableName))
            }
            assertForeignKeysRemainValid(migratedDatabase)
        } finally {
            database.close()
        }
    }

    @Test
    fun migration10To11PreservesRemoteMessagesAndFlattensCurrentMembership() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(10) {
                        override fun onConfigure(db: SupportSQLiteDatabase) {
                            db.setForeignKeyConstraintsEnabled(true)
                        }

                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion8PersistenceFixture(db)
                            seedVersion8PersistenceFixture(db)
                            SYNAPSE_DATABASE_MIGRATION_8_9.migrate(db)
                            SYNAPSE_DATABASE_MIGRATION_9_10.migrate(db)
                            seedVersion10RemoteCache(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        helper.writableDatabase
        helper.close()

        val database = Room.databaseBuilder(
            context,
            SynapseDatabase::class.java,
            TEST_DATABASE_NAME,
        )
            .addMigrations(SYNAPSE_DATABASE_MIGRATION_10_11)
            .allowMainThreadQueries()
            .build()
        try {
            val migratedDatabase = database.openHelper.writableDatabase

            assertEquals(11, migratedDatabase.version)
            migratedDatabase.query(
                """
                SELECT roomKind, directKey, peerUid, title, avatarObjectPath,
                       currentMemberRole, notificationsEnabled, isMuted, isArchived, isPinned,
                       joinedAtEpochMillis, lastReadAtEpochMillis
                FROM remote_room_cache
                WHERE accountUid = 'peter-uid' AND remoteRoomId = 'direct-room'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("DIRECT", cursor.getString(0))
                assertEquals("peter-uid:trish-uid", cursor.getString(1))
                assertEquals("trish-uid", cursor.getString(2))
                assertEquals("Peter, Trish", cursor.getString(3))
                assertTrue(cursor.isNull(4))
                assertEquals("MEMBER", cursor.getString(5))
                assertEquals(1, cursor.getInt(6))
                assertEquals(0, cursor.getInt(7))
                assertEquals(0, cursor.getInt(8))
                assertEquals(0, cursor.getInt(9))
                assertEquals(4_000L, cursor.getLong(10))
                assertEquals(4_500L, cursor.getLong(11))
            }
            assertEquals(1L, queryCount(migratedDatabase, "remote_message_cache"))
            assertEquals(1L, queryCount(migratedDatabase, "remote_message_outbox"))
            assertEquals(
                0L,
                queryLong(
                    migratedDatabase,
                    """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type = 'table' AND name IN (
                        'remote_direct_room_cache',
                        'remote_room_membership_cache'
                    )
                    """.trimIndent(),
                ),
            )
            assertForeignKeysRemainValid(migratedDatabase)
        } finally {
            database.close()
        }
    }

    private fun readVersion8LegacyRows(db: SupportSQLiteDatabase): Map<String, List<List<String?>>> =
        linkedMapOf(
            "chat_threads" to db.readRows(
                """
                SELECT id, title, pinnedAtEpochMillis, archivedAtEpochMillis,
                       titleEditedByUser, createdAtEpochMillis, updatedAtEpochMillis
                FROM chat_threads
                ORDER BY id ASC
                """.trimIndent(),
            ),
            "chat_messages" to db.readRows(
                """
                SELECT id, threadId, role, body, deliveryState,
                       createdAtEpochMillis, completedAtEpochMillis, failureReason
                FROM chat_messages
                ORDER BY id ASC
                """.trimIndent(),
            ),
            "attachments" to db.readRows("SELECT * FROM attachments ORDER BY id ASC"),
            "assistant_generation_traces" to db.readRows(
                "SELECT * FROM assistant_generation_traces ORDER BY id ASC",
            ),
            "trace_events" to db.readRows("SELECT * FROM trace_events ORDER BY id ASC"),
            "memory_objects" to db.readRows("SELECT * FROM memory_objects ORDER BY id ASC"),
            "memory_versions" to db.readRows("SELECT * FROM memory_versions ORDER BY id ASC"),
            "memory_supports" to db.readRows(
                "SELECT * FROM memory_supports ORDER BY memoryVersionId ASC, traceEventId ASC",
            ),
            "sms_sender_threads" to db.readRows(
                """
                SELECT senderAddress, threadId, createdAtEpochMillis, updatedAtEpochMillis
                FROM sms_sender_threads
                ORDER BY senderAddress ASC
                """.trimIndent(),
            ),
            "sms_auto_reply_receipts" to db.readRows(
                "SELECT * FROM sms_auto_reply_receipts ORDER BY id ASC",
            ),
        )

    private fun readVersion9LocalRows(db: SupportSQLiteDatabase): Map<String, List<List<String?>>> =
        version9LocalTables.associateWith { tableName ->
            db.readRows("SELECT * FROM $tableName ORDER BY rowid ASC")
        }

    private fun SupportSQLiteDatabase.readRows(sql: String): List<List<String?>> =
        query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        List(cursor.columnCount) { columnIndex ->
                            if (cursor.isNull(columnIndex)) null else cursor.getString(columnIndex)
                        },
                    )
                }
            }
        }

    private fun createVersion8PersistenceFixture(db: SupportSQLiteDatabase) {
        createVersion7ChatTables(db)
        db.execSQL(
            """
            CREATE TABLE attachments (
                id TEXT NOT NULL PRIMARY KEY,
                messageId TEXT NOT NULL,
                displayName TEXT NOT NULL,
                mimeType TEXT,
                uri TEXT NOT NULL,
                byteCount INTEGER,
                kind TEXT NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                FOREIGN KEY(messageId) REFERENCES chat_messages(id)
                ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE library_artifacts (
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
            CREATE TABLE library_artifact_write_receipts (
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
            CREATE TABLE assistant_generation_traces (
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
            CREATE TABLE trace_events (
                id TEXT NOT NULL PRIMARY KEY,
                sourceMessageId TEXT NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                observedAtEpochMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE memory_objects (
                id TEXT NOT NULL PRIMARY KEY,
                kind TEXT NOT NULL,
                status TEXT NOT NULL,
                claimKey TEXT,
                createdAtEpochMillis INTEGER NOT NULL,
                updatedAtEpochMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE memory_versions (
                id TEXT NOT NULL PRIMARY KEY,
                memoryObjectId TEXT NOT NULL,
                text TEXT NOT NULL,
                confidence REAL NOT NULL,
                surfacePolicy TEXT NOT NULL,
                scope TEXT NOT NULL,
                domain TEXT NOT NULL,
                subject TEXT,
                predicate TEXT,
                valueText TEXT,
                sourceQuote TEXT,
                writeIntent TEXT NOT NULL,
                durabilityScore REAL NOT NULL,
                futureUsefulnessScore REAL NOT NULL,
                sensitivity TEXT NOT NULL,
                keywordsCsv TEXT NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                FOREIGN KEY(memoryObjectId) REFERENCES memory_objects(id)
                ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE memory_supports (
                memoryVersionId TEXT NOT NULL,
                traceEventId TEXT NOT NULL,
                PRIMARY KEY(memoryVersionId, traceEventId),
                FOREIGN KEY(memoryVersionId) REFERENCES memory_versions(id)
                ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(traceEventId) REFERENCES trace_events(id)
                ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE memory_write_receipts (
                id TEXT NOT NULL PRIMARY KEY,
                outcome TEXT NOT NULL,
                traceEventId TEXT,
                memoryObjectId TEXT,
                memoryVersionId TEXT,
                decidedAtEpochMillis INTEGER NOT NULL,
                reason TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE retrieval_receipts (
                id TEXT NOT NULL PRIMARY KEY,
                query TEXT NOT NULL,
                retrievalIntent TEXT NOT NULL,
                promptBlock TEXT NOT NULL,
                retrievedAtEpochMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE retrieved_memory_receipts (
                retrievalReceiptId TEXT NOT NULL,
                memoryVersionId TEXT NOT NULL,
                memoryObjectId TEXT NOT NULL,
                reasonCodes TEXT NOT NULL,
                rankScore REAL NOT NULL,
                PRIMARY KEY(retrievalReceiptId, memoryVersionId),
                FOREIGN KEY(retrievalReceiptId) REFERENCES retrieval_receipts(id)
                ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(memoryVersionId) REFERENCES memory_versions(id)
                ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE storage_health_snapshots (
                id TEXT NOT NULL PRIMARY KEY,
                state TEXT NOT NULL,
                checkedAtEpochMillis INTEGER NOT NULL,
                availableBytes INTEGER NOT NULL,
                memoryDatabaseBytes INTEGER NOT NULL,
                attachmentCacheBytes INTEGER NOT NULL,
                reason TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE sms_sender_threads (
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
            CREATE TABLE sms_auto_reply_receipts (
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
        createVersion8Indices(db)
    }

    private fun seedVersion10RemoteCache(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO remote_direct_room_cache (
                accountUid, remoteRoomId, directKey, peerUid, title, unreadCount,
                latestMessagePreview, latestMessageSenderUid,
                remoteUpdatedAtEpochMillis, cachedAtEpochMillis
            ) VALUES (
                'peter-uid', 'direct-room', 'peter-uid:trish-uid', 'trish-uid',
                'Peter, Trish', 1, 'Hello', 'trish-uid', 5000, 5100
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO remote_room_membership_cache (
                accountUid, remoteRoomId, memberUid, role, isActive,
                joinedAtEpochMillis, lastReadAtEpochMillis, cachedAtEpochMillis
            ) VALUES (
                'peter-uid', 'direct-room', 'peter-uid', 'MEMBER', 1, 4000, 4500, 5100
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO remote_message_cache (
                accountUid, remoteRoomId, remoteMessageId, idempotencyKey,
                senderUid, authorKind, body, deliveryState,
                clientCreatedAtEpochMillis, serverCreatedAtEpochMillis,
                failureReason, cachedAtEpochMillis
            ) VALUES (
                'peter-uid', 'direct-room', 'message-1', 'message-1',
                'trish-uid', 'HUMAN', 'Hello', 'SENT', 4800, 4900, NULL, 5100
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO remote_message_outbox (
                accountUid, operationId, remoteRoomId, remoteMessageId,
                idempotencyKey, senderUid, body, state, attemptCount,
                createdAtEpochMillis, lastAttemptAtEpochMillis, failureReason
            ) VALUES (
                'peter-uid', 'operation-1', 'direct-room', 'message-1',
                'message-1', 'peter-uid', 'Hello', 'FAILED', 1, 4800, 5000, 'offline'
            )
            """.trimIndent(),
        )
    }

    private fun createVersion8Indices(db: SupportSQLiteDatabase) {
        listOf(
            "CREATE INDEX index_chat_messages_threadId ON chat_messages(threadId)",
            "CREATE INDEX index_chat_messages_createdAtEpochMillis ON chat_messages(createdAtEpochMillis)",
            "CREATE INDEX index_assistant_generation_traces_assistantMessageId ON assistant_generation_traces(assistantMessageId)",
            "CREATE INDEX index_assistant_generation_traces_startedAtEpochMillis ON assistant_generation_traces(startedAtEpochMillis)",
            "CREATE INDEX index_attachments_messageId ON attachments(messageId)",
            "CREATE INDEX index_library_artifacts_artifactKind ON library_artifacts(artifactKind)",
            "CREATE INDEX index_library_artifacts_sourceKind ON library_artifacts(sourceKind)",
            "CREATE INDEX index_library_artifacts_sha256 ON library_artifacts(sha256)",
            "CREATE INDEX index_library_artifacts_updatedAtEpochMillis ON library_artifacts(updatedAtEpochMillis)",
            "CREATE INDEX index_library_artifact_write_receipts_artifactId ON library_artifact_write_receipts(artifactId)",
            "CREATE INDEX index_library_artifact_write_receipts_writtenAtEpochMillis ON library_artifact_write_receipts(writtenAtEpochMillis)",
            "CREATE INDEX index_trace_events_sourceMessageId ON trace_events(sourceMessageId)",
            "CREATE INDEX index_trace_events_observedAtEpochMillis ON trace_events(observedAtEpochMillis)",
            "CREATE INDEX index_memory_objects_kind ON memory_objects(kind)",
            "CREATE INDEX index_memory_objects_status ON memory_objects(status)",
            "CREATE INDEX index_memory_objects_claimKey ON memory_objects(claimKey)",
            "CREATE INDEX index_memory_versions_memoryObjectId ON memory_versions(memoryObjectId)",
            "CREATE INDEX index_memory_versions_createdAtEpochMillis ON memory_versions(createdAtEpochMillis)",
            "CREATE INDEX index_memory_versions_surfacePolicy ON memory_versions(surfacePolicy)",
            "CREATE INDEX index_memory_versions_scope ON memory_versions(scope)",
            "CREATE INDEX index_memory_versions_subject ON memory_versions(subject)",
            "CREATE INDEX index_memory_versions_domain ON memory_versions(domain)",
            "CREATE INDEX index_memory_versions_predicate ON memory_versions(predicate)",
            "CREATE INDEX index_memory_versions_writeIntent ON memory_versions(writeIntent)",
            "CREATE INDEX index_memory_versions_sensitivity ON memory_versions(sensitivity)",
            "CREATE INDEX index_memory_supports_traceEventId ON memory_supports(traceEventId)",
            "CREATE INDEX index_memory_write_receipts_traceEventId ON memory_write_receipts(traceEventId)",
            "CREATE INDEX index_memory_write_receipts_memoryObjectId ON memory_write_receipts(memoryObjectId)",
            "CREATE INDEX index_retrieved_memory_receipts_memoryVersionId ON retrieved_memory_receipts(memoryVersionId)",
            "CREATE INDEX index_storage_health_snapshots_checkedAtEpochMillis ON storage_health_snapshots(checkedAtEpochMillis)",
            "CREATE INDEX index_sms_sender_threads_threadId ON sms_sender_threads(threadId)",
            "CREATE UNIQUE INDEX index_sms_auto_reply_receipts_inboundMessageKey ON sms_auto_reply_receipts(inboundMessageKey)",
            "CREATE INDEX index_sms_auto_reply_receipts_senderAddress ON sms_auto_reply_receipts(senderAddress)",
            "CREATE INDEX index_sms_auto_reply_receipts_threadId ON sms_auto_reply_receipts(threadId)",
            "CREATE INDEX index_sms_auto_reply_receipts_userMessageId ON sms_auto_reply_receipts(userMessageId)",
            "CREATE INDEX index_sms_auto_reply_receipts_assistantMessageId ON sms_auto_reply_receipts(assistantMessageId)",
            "CREATE INDEX index_sms_auto_reply_receipts_decidedAtEpochMillis ON sms_auto_reply_receipts(decidedAtEpochMillis)",
        ).forEach(db::execSQL)
    }

    private fun seedVersion8PersistenceFixture(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO chat_threads (
                id, title, pinnedAtEpochMillis, archivedAtEpochMillis,
                titleEditedByUser, createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES
                ('thread-main', 'Pinned planning', 1001, NULL, 1, 1000, 1400),
                ('thread-archived', 'Archived room', NULL, 2500, 0, 2000, 2500),
                ('thread-sms', 'SMS +15551234567', NULL, NULL, 0, 3000, 3400)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO chat_messages (
                id, threadId, role, body, deliveryState,
                createdAtEpochMillis, completedAtEpochMillis, failureReason
            ) VALUES
                ('message-system', 'thread-main', 'SYSTEM', 'System context.', 'COMPLETE', 1050, 1050, NULL),
                ('message-user', 'thread-main', 'USER', 'Keep this body exactly.', 'COMPLETE', 1100, 1100, NULL),
                ('message-assistant', 'thread-main', 'ASSISTANT', 'Preserved answer.', 'COMPLETE', 1200, 1300, NULL),
                ('message-sms-user', 'thread-sms', 'USER', 'Incoming SMS body', 'COMPLETE', 3100, 3100, NULL),
                ('message-sms-assistant', 'thread-sms', 'ASSISTANT', 'SMS reply', 'COMPLETE', 3200, 3300, NULL)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO attachments (
                id, messageId, displayName, mimeType, uri,
                byteCount, kind, createdAtEpochMillis
            ) VALUES (
                'attachment-1', 'message-user', 'notes.txt', 'text/plain',
                'content://synapse/notes', 42, 'TEXT', 1100
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO assistant_generation_traces (
                id, assistantMessageId, backend, modelName,
                promptMessageCount, promptCharacterCount, retrievedMemoryCount,
                maxTokens, temperature, startedAtEpochMillis, completedAtEpochMillis,
                rawTokenEvents, rawCharacterCount, visibleCharacterCount,
                filteredCharacterCount, firstRawTokenAtEpochMillis,
                firstVisibleTokenAtEpochMillis, stopReason, failureReason
            ) VALUES (
                'generation-1', 'message-assistant', 'EMBEDDED', 'fixture-model',
                3, 120, 1, 256, 0.7, 1200, 1300,
                4, 32, 17, 15, 1210, 1220, 'COMPLETED', NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO trace_events (
                id, sourceMessageId, role, text, observedAtEpochMillis
            ) VALUES (
                'trace-1', 'message-user', 'USER', 'Keep this body exactly.', 1110
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO memory_objects (
                id, kind, status, claimKey, createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES (
                'memory-1', 'PREFERENCE', 'ACTIVE', 'preference:fixture', 1120, 1120
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO memory_versions (
                id, memoryObjectId, text, confidence, surfacePolicy,
                scope, domain, subject, predicate, valueText, sourceQuote,
                writeIntent, durabilityScore, futureUsefulnessScore,
                sensitivity, keywordsCsv, createdAtEpochMillis
            ) VALUES (
                'version-1', 'memory-1', 'Keep this body exactly.', 0.95, 'PROMPT_VISIBLE',
                'GLOBAL', 'PREFERENCE', 'self', 'prefers', 'exact preservation',
                'Keep this body exactly.', 'EXPLICIT_SAVE', 1.0, 1.0,
                'LOW', 'fixture,preservation', 1130
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO memory_supports (memoryVersionId, traceEventId)
            VALUES ('version-1', 'trace-1')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO sms_sender_threads (
                senderAddress, threadId, createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES (
                '+15551234567', 'thread-sms', 3000, 3400
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO sms_auto_reply_receipts (
                id, inboundMessageKey, senderAddress, inboundBodySha256,
                inboundCharacterCount, inboundReceivedAtEpochMillis,
                threadId, userMessageId, assistantMessageId, state,
                replyBodySha256, replyCharacterCount, smsPartCount,
                queuedAtEpochMillis, decidedAtEpochMillis, failureReason
            ) VALUES (
                'receipt-sms', 'inbound-key-1', '+15551234567', 'inbound-hash',
                20, 3090, 'thread-sms', 'message-sms-user', 'message-sms-assistant',
                'SMS_QUEUED', 'reply-hash', 9, 1, 3310, 3320, NULL
            )
            """.trimIndent(),
        )
    }

    private fun assertMainThreadPreservedWithVersion9Defaults(db: SupportSQLiteDatabase) {
        db.query(
            """
            SELECT title, pinnedAtEpochMillis, archivedAtEpochMillis,
                   titleEditedByUser, createdAtEpochMillis, updatedAtEpochMillis,
                   roomKind, remoteId, revision, syncState
            FROM chat_threads
            WHERE id = 'thread-main'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Pinned planning", cursor.getString(0))
            assertEquals(1001L, cursor.getLong(1))
            assertTrue(cursor.isNull(2))
            assertEquals(1, cursor.getInt(3))
            assertEquals(1000L, cursor.getLong(4))
            assertEquals(1400L, cursor.getLong(5))
            assertEquals("AI_CHAT", cursor.getString(6))
            assertTrue(cursor.isNull(7))
            assertEquals(0L, cursor.getLong(8))
            assertEquals("LOCAL_ONLY", cursor.getString(9))
        }
    }

    private fun assertArchivedThreadPreserved(db: SupportSQLiteDatabase) {
        db.query(
            """
            SELECT title, pinnedAtEpochMillis, archivedAtEpochMillis,
                   titleEditedByUser, createdAtEpochMillis, updatedAtEpochMillis, roomKind
            FROM chat_threads
            WHERE id = 'thread-archived'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Archived room", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertEquals(2500L, cursor.getLong(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals(2000L, cursor.getLong(4))
            assertEquals(2500L, cursor.getLong(5))
            assertEquals("AI_CHAT", cursor.getString(6))
        }
    }

    private fun assertMessagesAndChildRowsPreserved(db: SupportSQLiteDatabase) {
        db.query(
            """
            SELECT threadId, role, body, deliveryState, createdAtEpochMillis,
                   completedAtEpochMillis, failureReason, remoteId, revision, syncState
            FROM chat_messages
            WHERE id = 'message-user'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("thread-main", cursor.getString(0))
            assertEquals("USER", cursor.getString(1))
            assertEquals("Keep this body exactly.", cursor.getString(2))
            assertEquals("COMPLETE", cursor.getString(3))
            assertEquals(1100L, cursor.getLong(4))
            assertEquals(1100L, cursor.getLong(5))
            assertTrue(cursor.isNull(6))
            assertTrue(cursor.isNull(7))
            assertEquals(0L, cursor.getLong(8))
            assertEquals("LOCAL_ONLY", cursor.getString(9))
        }
        db.query(
            """
            SELECT messageId, displayName, mimeType, uri, byteCount, kind, createdAtEpochMillis
            FROM attachments
            WHERE id = 'attachment-1'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("message-user", cursor.getString(0))
            assertEquals("notes.txt", cursor.getString(1))
            assertEquals("text/plain", cursor.getString(2))
            assertEquals("content://synapse/notes", cursor.getString(3))
            assertEquals(42L, cursor.getLong(4))
            assertEquals("TEXT", cursor.getString(5))
            assertEquals(1100L, cursor.getLong(6))
        }
        db.query(
            """
            SELECT assistantMessageId, backend, modelName, rawTokenEvents,
                   visibleCharacterCount, stopReason
            FROM assistant_generation_traces
            WHERE id = 'generation-1'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("message-assistant", cursor.getString(0))
            assertEquals("EMBEDDED", cursor.getString(1))
            assertEquals("fixture-model", cursor.getString(2))
            assertEquals(4, cursor.getInt(3))
            assertEquals(17, cursor.getInt(4))
            assertEquals("COMPLETED", cursor.getString(5))
        }
        assertEquals(5L, queryCount(db, "chat_messages"))
        assertEquals(1L, queryCount(db, "attachments"))
        assertEquals(1L, queryCount(db, "assistant_generation_traces"))
    }

    private fun assertMemoryReferencesPreserved(db: SupportSQLiteDatabase) {
        db.query(
            """
            SELECT sourceMessageId, role, text, observedAtEpochMillis
            FROM trace_events
            WHERE id = 'trace-1'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("message-user", cursor.getString(0))
            assertEquals("USER", cursor.getString(1))
            assertEquals("Keep this body exactly.", cursor.getString(2))
            assertEquals(1110L, cursor.getLong(3))
        }
        db.query(
            """
            SELECT memoryVersionId, traceEventId
            FROM memory_supports
            WHERE memoryVersionId = 'version-1'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("version-1", cursor.getString(0))
            assertEquals("trace-1", cursor.getString(1))
        }
        db.query(
            """
            SELECT memoryObjectId, text, sourceQuote, keywordsCsv
            FROM memory_versions
            WHERE id = 'version-1'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("memory-1", cursor.getString(0))
            assertEquals("Keep this body exactly.", cursor.getString(1))
            assertEquals("Keep this body exactly.", cursor.getString(2))
            assertEquals("fixture,preservation", cursor.getString(3))
        }
    }

    private fun assertSmsRowsAndSenderParticipantPreserved(db: SupportSQLiteDatabase) {
        db.query(
            """
            SELECT threadId, createdAtEpochMillis, updatedAtEpochMillis, participantId
            FROM sms_sender_threads
            WHERE senderAddress = '+15551234567'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("thread-sms", cursor.getString(0))
            assertEquals(3000L, cursor.getLong(1))
            assertEquals(3400L, cursor.getLong(2))
            assertEquals(SMS_PARTICIPANT_ID, cursor.getString(3))
        }
        db.query(
            """
            SELECT inboundMessageKey, senderAddress, inboundBodySha256,
                   inboundCharacterCount, inboundReceivedAtEpochMillis,
                   threadId, userMessageId, assistantMessageId, state,
                   replyBodySha256, replyCharacterCount, smsPartCount,
                   queuedAtEpochMillis, decidedAtEpochMillis, failureReason
            FROM sms_auto_reply_receipts
            WHERE id = 'receipt-sms'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("inbound-key-1", cursor.getString(0))
            assertEquals("+15551234567", cursor.getString(1))
            assertEquals("inbound-hash", cursor.getString(2))
            assertEquals(20, cursor.getInt(3))
            assertEquals(3090L, cursor.getLong(4))
            assertEquals("thread-sms", cursor.getString(5))
            assertEquals("message-sms-user", cursor.getString(6))
            assertEquals("message-sms-assistant", cursor.getString(7))
            assertEquals("SMS_QUEUED", cursor.getString(8))
            assertEquals("reply-hash", cursor.getString(9))
            assertEquals(9, cursor.getInt(10))
            assertEquals(1, cursor.getInt(11))
            assertEquals(3310L, cursor.getLong(12))
            assertEquals(3320L, cursor.getLong(13))
            assertTrue(cursor.isNull(14))
        }
        db.query(
            """
            SELECT kind, displayName, createdAtEpochMillis, updatedAtEpochMillis
            FROM chat_participants
            WHERE id = '$SMS_PARTICIPANT_ID'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("HUMAN", cursor.getString(0))
            assertEquals("+15551234567", cursor.getString(1))
            assertEquals(3000L, cursor.getLong(2))
            assertEquals(3400L, cursor.getLong(3))
        }
    }

    private fun assertBuiltInParticipantsAndMembershipsBackfilled(db: SupportSQLiteDatabase) {
        db.query(
            """
            SELECT id, kind, displayName, revision, syncState
            FROM chat_participants
            WHERE id IN (
                'participant-local-human',
                'participant-synapse-local-ai',
                'participant-system'
            )
            ORDER BY id ASC
            """.trimIndent(),
        ).use { cursor ->
            val participants = buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(0), Triple(cursor.getString(1), cursor.getString(2), cursor.getString(4)))
                    assertEquals(0L, cursor.getLong(3))
                }
            }
            assertEquals(Triple("HUMAN", "You", "LOCAL_ONLY"), participants["participant-local-human"])
            assertEquals(Triple("LOCAL_AI", "Synapse", "LOCAL_ONLY"), participants["participant-synapse-local-ai"])
            assertEquals(Triple("SYSTEM", "System", "LOCAL_ONLY"), participants["participant-system"])
        }
        assertEquals(
            3L,
            queryLong(
                db,
                """
                SELECT COUNT(*) FROM room_memberships
                WHERE participantId = 'participant-local-human'
                  AND role = 'OWNER'
                  AND canPost = 1
                  AND leftAtEpochMillis IS NULL
                  AND aiResponsePolicy = 'NEVER'
                """.trimIndent(),
            ),
        )
        assertEquals(
            3L,
            queryLong(
                db,
                """
                SELECT COUNT(*) FROM room_memberships
                WHERE participantId = 'participant-synapse-local-ai'
                  AND role = 'MEMBER'
                  AND canPost = 1
                  AND leftAtEpochMillis IS NULL
                  AND aiResponsePolicy = 'AUTOMATIC'
                """.trimIndent(),
            ),
        )
        assertEquals(
            1L,
            queryLong(
                db,
                """
                SELECT COUNT(*) FROM room_memberships
                WHERE roomId = 'thread-main'
                  AND participantId = 'participant-system'
                  AND leftAtEpochMillis IS NULL
                """.trimIndent(),
            ),
        )
        assertEquals(
            1L,
            queryLong(
                db,
                """
                SELECT COUNT(*) FROM room_memberships
                WHERE roomId = 'thread-sms'
                  AND participantId = '$SMS_PARTICIPANT_ID'
                  AND role = 'MEMBER'
                  AND canPost = 1
                  AND aiResponsePolicy = 'NEVER'
                """.trimIndent(),
            ),
        )
    }

    private fun assertEveryMessageHasExactlyOneExpectedAuthor(db: SupportSQLiteDatabase) {
        assertEquals(
            queryCount(db, "chat_messages"),
            queryCount(db, "chat_message_authors"),
        )
        assertEquals(
            0L,
            queryLong(
                db,
                """
                SELECT COUNT(*)
                FROM chat_messages AS message
                LEFT JOIN chat_message_authors AS authorship
                  ON authorship.messageId = message.id
                LEFT JOIN chat_participants AS author
                  ON author.id = authorship.authorParticipantId
                WHERE authorship.messageId IS NULL OR author.id IS NULL
                """.trimIndent(),
            ),
        )
        db.query(
            """
            SELECT message.role, authorship.authorParticipantId
            FROM chat_messages AS message
            INNER JOIN chat_message_authors AS authorship
              ON authorship.messageId = message.id
            ORDER BY message.id ASC
            """.trimIndent(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val expectedParticipantId = when (cursor.getString(0)) {
                    "USER" -> "participant-local-human"
                    "ASSISTANT" -> "participant-synapse-local-ai"
                    "SYSTEM" -> "participant-system"
                    else -> error("Unexpected fixture role ${cursor.getString(0)}")
                }
                assertEquals(expectedParticipantId, cursor.getString(1))
            }
        }
    }

    private fun assertForeignKeysRemainValid(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertTrue("Expected no foreign-key violations after migration", !cursor.moveToFirst())
        }
    }

    private fun queryCount(
        db: SupportSQLiteDatabase,
        tableName: String,
    ): Long = queryLong(db, "SELECT COUNT(*) FROM $tableName")

    private fun queryLong(
        db: SupportSQLiteDatabase,
        sql: String,
    ): Long =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "Expected query to return one row: $sql" }
            cursor.getLong(0)
        }

    private fun createVersion6MemoryVersionsTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE memory_versions (
                id TEXT NOT NULL PRIMARY KEY,
                memoryObjectId TEXT NOT NULL,
                text TEXT NOT NULL,
                confidence REAL NOT NULL,
                surfacePolicy TEXT NOT NULL,
                sourceTraceEventIdsCsv TEXT NOT NULL,
                scope TEXT NOT NULL DEFAULT 'GLOBAL',
                subject TEXT DEFAULT NULL,
                keywordsCsv TEXT NOT NULL DEFAULT '',
                createdAtEpochMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createVersion7ChatTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE chat_threads (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                pinnedAtEpochMillis INTEGER DEFAULT NULL,
                archivedAtEpochMillis INTEGER DEFAULT NULL,
                titleEditedByUser INTEGER NOT NULL DEFAULT 0,
                createdAtEpochMillis INTEGER NOT NULL,
                updatedAtEpochMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE chat_messages (
                id TEXT NOT NULL PRIMARY KEY,
                threadId TEXT NOT NULL,
                role TEXT NOT NULL,
                body TEXT NOT NULL,
                deliveryState TEXT NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                completedAtEpochMillis INTEGER,
                failureReason TEXT,
                FOREIGN KEY(threadId) REFERENCES chat_threads(id)
                ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    private companion object {
        const val TEST_DATABASE_NAME = "synapse-migration-test.db"
        const val SMS_PARTICIPANT_ID = "participant-sms-2b3135353531323334353637"

        val expectedVersion7MemoryColumns = setOf(
            "domain",
            "predicate",
            "valueText",
            "sourceQuote",
            "writeIntent",
            "durabilityScore",
            "futureUsefulnessScore",
            "sensitivity",
        )

        val expectedSmsAutoReplyReceiptColumns = setOf(
            "inboundMessageKey",
            "senderAddress",
            "inboundBodySha256",
            "inboundCharacterCount",
            "threadId",
            "userMessageId",
            "assistantMessageId",
            "state",
            "replyBodySha256",
            "replyCharacterCount",
            "smsPartCount",
            "queuedAtEpochMillis",
            "failureReason",
        )

        val version9LocalTables = listOf(
            "assistant_generation_traces",
            "attachments",
            "chat_message_authors",
            "chat_messages",
            "chat_participants",
            "chat_threads",
            "library_artifact_write_receipts",
            "library_artifacts",
            "memory_objects",
            "memory_supports",
            "memory_versions",
            "memory_write_receipts",
            "retrieval_receipts",
            "retrieved_memory_receipts",
            "room_memberships",
            "sms_auto_reply_receipts",
            "sms_sender_threads",
            "storage_health_snapshots",
            "trace_events",
        )

        val version11RemoteCacheTables = listOf(
            "remote_message_cache",
            "remote_message_outbox",
            "remote_profile_cache",
            "remote_room_cache",
            "remote_sync_cursors",
        )
    }
}
