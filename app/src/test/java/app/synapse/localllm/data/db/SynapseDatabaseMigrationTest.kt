package app.synapse.localllm.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
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

    private companion object {
        const val TEST_DATABASE_NAME = "synapse-migration-test.db"

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
    }
}
