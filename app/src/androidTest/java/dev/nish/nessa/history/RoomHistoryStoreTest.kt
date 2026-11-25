package dev.nish.nessa.history

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class RoomHistoryStoreTest {
    private val store = RoomHistoryStore(InstrumentationRegistry.getInstrumentation().targetContext, inMemory = true)
    private val t = Instant.parse("2026-01-05T10:00:00Z")

    @After fun tearDown() = store.close()

    @Test fun insertingTheSameIdTwiceKeepsOneRow() = runBlocking {
        val a = StoredActivity("e1", "PET_VISIT", t, 60, 8.0)
        assertEquals(1, store.addActivities(listOf(a)))
        assertEquals(0, store.addActivities(listOf(a.copy(weightLb = 9.0))))
        assertEquals(listOf(a), store.activitiesSince(Instant.EPOCH))
    }

    @Test fun activitiesSinceIsSortedAndFiltered() = runBlocking {
        store.addActivities(listOf(
            StoredActivity("b", "CYCLE_COMPLETED", t.plusSeconds(60), null, null),
            StoredActivity("a", "PET_VISIT", t, 30, null),
            StoredActivity("old", "PET_VISIT", t.minusSeconds(86400), 30, null),
        ))
        assertEquals(listOf("a", "b"), store.activitiesSince(t).map { it.id })
    }

    @Test fun readingsRoundTrip() = runBlocking {
        val r = RobotReading(t, 40, 95.0, "Optimal", false, true, 12)
        store.addReading(r)
        assertEquals(listOf(r), store.readingsSince(Instant.EPOCH))
    }
}
