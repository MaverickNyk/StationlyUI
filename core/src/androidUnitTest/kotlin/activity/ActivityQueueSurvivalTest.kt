package com.stationly.core.activity

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.stationly.core.model.UserSelection
import com.stationly.core.repository.SqlStorage
import com.stationly.db.StationlyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The activity queue must survive the wipe that login and logout both run.
 *
 * ## Why this is a test and not a comment
 * It IS a comment, at the top of `ActivityEventEntity` in the `.sq` file, and
 * that comment ends "keep it that way" — which is a request nobody can act on
 * while making an unrelated change. `clearAllData()` names its tables one at a
 * time, so the table survives **by omission**: the way to break it is to add a
 * line, which is exactly what somebody adding a table will do.
 *
 * What breaks is the part of the trail that matters most. The events worth
 * having are the ones AROUND an auth change — "signed out", "signed in on a new
 * device", "account deleted" — and a queue emptied by the very event it is
 * recording can never report it. There is no error and no gap in any log: the
 * rows simply were never there.
 */
class ActivityQueueSurvivalTest {

    @Test
    fun `a wipe takes the boards and leaves the activity trail`() = withStorage { storage ->
        storage.saveSelection(
            UserSelection(
                mode = "tube",
                line = "victoria",
                station = "940GZZLUKSX",
                stationName = "King's Cross",
                direction = "inbound",
                destinations = emptyList(),
                destinationIds = emptyList(),
            ),
        )
        storage.enqueueActivityEvent(
            id = "evt-1",
            uid = "u1",
            name = ActivityEvents.AUTH_LOGGED_OUT,
            t = 1_700_000_000_000,
            props = "{}",
        )

        storage.clearAllData()

        assertTrue(storage.getAllSelections().isEmpty(), "the board list should be wiped — that is what clearAllData is for")
        assertEquals(
            1,
            storage.activityBatch("u1", 10).size,
            "the activity queue was emptied by the sign-out it exists to report",
        )
    }

    /**
     * A signed-out row uploads under whoever signs in next, so it has to survive
     * the wipe that the sign-in itself performs. This is the same assertion as
     * above from the other side of the session boundary, and it is the one that
     * actually loses data: the event is recorded with a blank uid, then login
     * clears the database before the row has ever been sent.
     */
    @Test
    fun `an event recorded while signed out survives the login restore`() = withStorage { storage ->
        storage.enqueueActivityEvent(
            id = "evt-2",
            uid = "",
            name = ActivityEvents.AUTH_LOGGED_IN,
            t = 1_700_000_000_001,
            props = "{}",
        )

        storage.clearAllData()

        assertEquals(1, storage.activityBatch("whoever-signs-in-next", 10).size)
    }

    private fun withStorage(block: (SqlStorage) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        StationlyDatabase.Schema.create(driver).value
        try {
            block(SqlStorage(StationlyDatabase(driver)))
        } finally {
            driver.close()
        }
    }
}
