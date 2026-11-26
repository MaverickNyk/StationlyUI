-- The Android v1 schema, verbatim from the last released build.
-- Captured 2026-09-05 with:
--   git show master:core/src/commonMain/sqldelight/com/stationly/db/StationlyDatabase.sq
--
-- A FIXTURE, not a mirror. It must NOT be regenerated from the current .sq:
-- the whole point is to hold still while that file moves, so MigrationTest is
-- always migrating the shape a real released phone actually has.
-- Comments stripped; DDL only.

CREATE TABLE UserSelectionEntity (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    mode TEXT NOT NULL,
    line TEXT NOT NULL,
    station TEXT NOT NULL,
    stationName TEXT NOT NULL,
    direction TEXT NOT NULL,
    destinations TEXT NOT NULL,
    destinationIds TEXT NOT NULL
);
CREATE TABLE PredictionEntity (
    stationId TEXT NOT NULL,
    lineId TEXT NOT NULL,
    destination TEXT NOT NULL,
    platform TEXT NOT NULL,
    eta TEXT NOT NULL,
    isDue INTEGER NOT NULL DEFAULT 0,
    stopLetter TEXT,
    timestamp INTEGER NOT NULL,

    targetEpochMs INTEGER,
    PRIMARY KEY (stationId, lineId, destination, platform, eta)
);
CREATE INDEX prediction_lookup ON PredictionEntity(stationId, lineId);
CREATE TABLE LineStatusEntity (
    mode TEXT NOT NULL,
    line TEXT NOT NULL,
    statusSeverityDescription TEXT NOT NULL,
    reason TEXT,
    lastUpdatedTime TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    PRIMARY KEY (mode, line)
);
CREATE TABLE SyncStatusEntity (
    stationId TEXT NOT NULL,
    lineId TEXT NOT NULL,
    lastSyncMs INTEGER NOT NULL,
    PRIMARY KEY (stationId, lineId)
);
