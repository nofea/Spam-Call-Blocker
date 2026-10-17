package com.sheen.spamblocker.screening

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.spamblocker.calllog.CallLogReader
import com.sheen.spamblocker.data.BlockRule
import com.sheen.spamblocker.data.CallEvent
import com.sheen.spamblocker.data.ListEntry
import com.sheen.spamblocker.data.ListSource
import com.sheen.spamblocker.data.RuleIndexHolder
import com.sheen.spamblocker.data.RuleOrigin
import com.sheen.spamblocker.data.RuleType
import com.sheen.spamblocker.data.SettingsRepository
import com.sheen.spamblocker.data.SpamBlockerDatabase
import com.sheen.spamblocker.lists.ListFormat
import com.sheen.spamblocker.notify.Notifier
import com.sheen.spamblocker.phone.PhoneNormalizer
import com.sheen.spamblocker.rules.Action
import com.sheen.spamblocker.rules.WildcardCompiler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * SPEC §13.2: the screening path end to end (index, settings, Room history),
 * driven through [CallScreener] since `Call.Details` can't be constructed.
 */
@RunWith(AndroidJUnit4::class)
class CallScreenerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scope: CoroutineScope
    private lateinit var db: SpamBlockerDatabase
    private lateinit var screener: CallScreener
    private lateinit var storeFile: File
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() = runBlocking {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        db = Room.inMemoryDatabaseBuilder(context, SpamBlockerDatabase::class.java).build()
        storeFile = File(context.cacheDir, "screener-test-${System.nanoTime()}.preferences_pb")
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { storeFile })
        val normalizer = PhoneNormalizer()

        db.blockRules().insert(
            BlockRule(pattern = "+9188888*", type = RuleType.PREFIX, action = Action.WARN, origin = RuleOrigin.MANUAL_PATTERN, createdAt = 0),
        )
        val source = db.listSources().insert(ListSource(name = "list", url = "https://example.com", format = ListFormat.TXT))
        db.listEntries().insertAll(listOf(ListEntry(sourceId = source, value = "+917777777777", isPrefix = false)))

        screener = CallScreener(
            normalizer = normalizer,
            indexHolder = RuleIndexHolder(db, WildcardCompiler(normalizer), scope),
            settings = settings,
            callEvents = db.callEvents(),
            sources = db.listSources(),
            callLog = CallLogReader(context, normalizer),
            notifier = Notifier(context, db.callEvents()),
            scope = scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        storeFile.delete()
    }

    private fun screen(handle: String?, presentation: Presentation = Presentation.ALLOWED, at: Long = now) =
        runBlocking { screener.screen(handle, presentation, at) }

    @Test
    fun decisionsForEachAction() {
        assertEquals(ScreeningDecision(Decision.REJECT, Stage.TRAI_140), screen("1401234567"))
        assertEquals(Decision.SILENCE, screen("+91 77777 77777").decision)
        assertEquals(Decision.WARN, screen("8888812345").decision)
        assertEquals(ScreeningDecision(Decision.SILENCE, Stage.HIDDEN), screen(null, Presentation.RESTRICTED))
        assertEquals(ScreeningDecision.allow(Stage.TRAI_160), screen("1600123456"))
        assertEquals(ScreeningDecision.allow(Stage.DEFAULT), screen("9123456789"))
        listOf(Decision.ALLOW, Decision.WARN, Decision.SILENCE, Decision.REJECT).forEach { SpamScreeningService.responseFor(it) }
    }

    @Test
    fun everyDecisionIsRecordedAndSecondCallGetsThrough() = runBlocking {
        assertEquals(Decision.SILENCE, screen("7777777777").decision)
        waitForEvents(1)
        assertEquals(ScreeningDecision.allow(Stage.REPEAT_CALLER), screen("7777777777", at = now + 60_000))
        val events = waitForEvents(2).sortedBy { it.timestamp }
        assertEquals(listOf(Stage.REMOTE_LIST, Stage.REPEAT_CALLER), events.map { it.stage })
        assertTrue(events.all { it.number == "+917777777777" && it.decisionMicros > 0 })
    }

    @Test
    fun decisionsAreFast() {
        screen("9000000000") // warm up the index and settings
        val micros = (0 until 50).map { i ->
            val start = System.nanoTime()
            screen("9${(100000000 + i * 37)}")
            (System.nanoTime() - start) / 1_000
        }.sorted()
        val p95 = micros[(micros.size * 0.95).toInt() - 1]
        assertTrue("p95 was $p95 µs", p95 < 100_000)
    }

    /** Events are written in the background after the decision returns. */
    private suspend fun waitForEvents(count: Int): List<CallEvent> = withTimeout(5_000) {
        var events = db.callEvents().getSince(0)
        while (events.size < count) {
            delay(20)
            events = db.callEvents().getSince(0)
        }
        events
    }
}
