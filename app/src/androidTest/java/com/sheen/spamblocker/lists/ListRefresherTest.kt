package com.sheen.spamblocker.lists

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.spamblocker.data.ListSource
import com.sheen.spamblocker.data.SpamBlockerDatabase
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SPEC §13.2: refresh against MockWebServer for 200, 304, 500 and an oversized body. */
@RunWith(AndroidJUnit4::class)
class ListRefresherTest {
    private lateinit var db: SpamBlockerDatabase
    private lateinit var server: MockWebServer
    private lateinit var refresher: ListRefresher
    private var sourceId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, SpamBlockerDatabase::class.java)
            .build()
        server = MockWebServer().apply { start() }
        refresher = ListRefresher(db, ListFetcher(OkHttpClient()), ListParser())
        sourceId = db.listSources().insert(
            ListSource(name = "test", url = server.url("/list.txt").toString(), format = ListFormat.TXT),
        )
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private suspend fun source() = db.listSources().get(sourceId)!!
    private suspend fun entries() = db.listEntries().getIndexable().map { it.value }.sorted()

    @Test
    fun okThenNotModifiedThenServerError() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body("9876543210\n8888812345\n77777*\n").addHeader("ETag", "\"v1\"").build(),
        )
        assertEquals(RefreshOutcome.UPDATED, refresher.refresh(sourceId))
        assertEquals(listOf("+9177777", "+918888812345", "+919876543210"), entries())
        assertEquals("\"v1\"", source().etag)
        assertEquals(3, source().entryCount)
        server.takeRequest()

        server.enqueue(MockResponse.Builder().code(304).build())
        assertEquals(RefreshOutcome.NOT_MODIFIED, refresher.refresh(sourceId))
        assertEquals("\"v1\"", server.takeRequest().headers["If-None-Match"])
        assertEquals(3, entries().size)

        server.enqueue(MockResponse.Builder().code(500).build())
        assertEquals(RefreshOutcome.RETRYABLE_FAILURE, refresher.refresh(sourceId))
        assertEquals("HTTP 500", source().lastError)
        assertEquals("the last good copy is kept", 3, entries().size)
    }

    @Test
    fun oversizedBodyIsRejectedAndOldEntriesKept() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("9876543210\n").build())
        refresher.refresh(sourceId)

        val fetcher = ListFetcher(OkHttpClient())
        server.enqueue(MockResponse.Builder().code(200).body("9876543210\n".repeat(200)).build())
        val result = fetcher.fetch(source(), maxBytes = 1_000)
        assertEquals(FetchResult.Failure("List is larger than 1000 bytes", retryable = false), result)
        assertEquals(listOf("+919876543210"), entries())
    }

    @Test
    fun nonAsciiValidatorsAreNotStoredOrSentBack() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body("9876543210\n").addHeaderLenient("ETag", "\"vé\"").build(),
        )
        assertEquals(RefreshOutcome.UPDATED, refresher.refresh(sourceId))
        assertNull(source().etag)
        server.takeRequest()

        // Validators stored before this check existed are skipped rather than crashing the request.
        db.listSources().update(source().copy(etag = "\"vé\""))
        server.enqueue(MockResponse.Builder().code(200).body("9876543210\n").build())
        assertEquals(RefreshOutcome.UPDATED, refresher.refresh(sourceId))
        assertNull(server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun mostlyGarbageListIsRejected() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("9876543210\n").build())
        refresher.refresh(sourceId)
        assertNull(source().lastError)

        server.enqueue(MockResponse.Builder().code(200).body("<html>\nnot\na list\n</html>\n").build())
        assertEquals(RefreshOutcome.REJECTED, refresher.refresh(sourceId))
        assertNotNull(source().lastError)
        assertEquals(listOf("+919876543210"), entries())
    }
}
