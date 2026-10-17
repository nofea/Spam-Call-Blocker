package com.sheen.spamblocker.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.spamblocker.lists.ListFormat
import com.sheen.spamblocker.rules.Action
import com.sheen.spamblocker.screening.Decision
import com.sheen.spamblocker.screening.Stage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: SpamBlockerDatabase

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SpamBlockerDatabase::class.java)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SpamBlockerDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    private fun source(name: String, enabled: Boolean = true, action: Action = Action.SILENCE) =
        ListSource(name = name, url = "https://example.com/$name", format = ListFormat.TXT, enabled = enabled, action = action)

    @Test
    fun replaceForSourceSwapsOnlyThatSource() = runTest {
        val a = db.listSources().insert(source("a"))
        val b = db.listSources().insert(source("b"))
        db.listEntries().replaceForSource(a, listOf(ListEntry(sourceId = a, value = "+910000000001", isPrefix = false)))
        db.listEntries().replaceForSource(b, listOf(ListEntry(sourceId = b, value = "+912", isPrefix = false)))
        db.listEntries().replaceForSource(
            a,
            (0 until 12_000).map { ListEntry(sourceId = a, value = "+91$it", isPrefix = false) },
        )
        val entries = db.listEntries().getIndexable()
        assertEquals(12_001, entries.size)
        assertTrue(entries.any { it.value == "+912" && it.sourceId == b })
        assertFalse(entries.any { it.value == "+910000000001" })
    }

    @Test
    fun indexableSkipsDisabledSourcesAndCarriesAction() = runTest {
        val on = db.listSources().insert(source("on", action = Action.WARN))
        val off = db.listSources().insert(source("off", enabled = false))
        db.listEntries().insertAll(
            listOf(ListEntry(sourceId = on, value = "+911", isPrefix = false), ListEntry(sourceId = off, value = "+912", isPrefix = true)),
        )
        assertEquals(listOf(IndexableListEntry("+911", false, on, Action.WARN)), db.listEntries().getIndexable())
    }

    @Test
    fun deletingSourceCascadesToEntries() = runTest {
        val id = db.listSources().insert(source("a"))
        db.listEntries().insertAll(listOf(ListEntry(sourceId = id, value = "+911", isPrefix = false)))
        db.listSources().delete(db.listSources().get(id)!!)
        assertTrue(db.listEntries().getIndexable().isEmpty())
    }

    @Test
    fun repeatCallerQueryAndPruning() = runTest {
        val events = db.callEvents()
        events.insert(CallEvent(number = "+919876543210", timestamp = 1_000, decision = Decision.SILENCE, stage = Stage.REMOTE_LIST, decisionMicros = 5))
        events.insert(CallEvent(number = null, timestamp = 2_000, decision = Decision.SILENCE, stage = Stage.HIDDEN, decisionMicros = 5))
        assertTrue(events.existsSince("+919876543210", 1_000))
        assertFalse(events.existsSince("+919876543210", 1_001))
        assertFalse(events.existsSince("+919876543211", 0))
        assertEquals(1, events.deleteOlderThan(1_500))
        assertEquals(1, events.getSince(0).size)
    }

    @Test
    fun suggestionsKeepDismissedAndDropStalePending() = runTest {
        val dao = db.suggestions()
        dao.insertAll(
            listOf(
                DerivedSuggestion("+9198765432", listOf("+919876543201"), 100, SuggestionStatus.PENDING, 1),
                DerivedSuggestion("+9188888888", listOf("+918888888801"), 100, SuggestionStatus.PENDING, 1),
                DerivedSuggestion("+9177777777", listOf("+917777777701"), 100, SuggestionStatus.DISMISSED, 1),
            ),
        )
        dao.deletePendingExcept(listOf("+9198765432"))
        assertEquals(setOf("+9198765432", "+9177777777"), dao.getAll().map { it.prefix }.toSet())
        assertEquals(listOf("+919876543201"), dao.get("+9198765432")!!.sampleNumbers)
    }

    /** Validates the exported v1 schema against the entities (SPEC §13.2 migration tests). */
    @Test
    fun schemaVersion1MatchesExport() {
        migrations.createDatabase("migration-test", 1).close()
        migrations.runMigrationsAndValidate("migration-test", 1, true)
    }
}
