const express = require('express');
const axios = require('axios');
const router = express.Router();
const { SELECTION_LAYOUT } = require('../utils/constants');
const { formatModeLabel, getIconUrl, formatDestination } = require('../utils/formatters');

/**
 * SDUI Layout Route
 */
router.get('/sdui/app/layout', (req, res) => {
    res.json(SELECTION_LAYOUT);
});

/**
 * Data API: Transport Modes
 */
router.get('/sdui/app/data/modes', async (req, res) => {
    console.log(">>> [FETCH] Requested modes data");
    try {
        const response = await axios.get('https://api.stationly.co.uk/StationlyBE/api/v1/modes');
        const formatted = response.data.map(mode => ({
            id: mode.modeName,
            label: formatModeLabel(mode.modeName),
            iconUrl: getIconUrl(mode.modeName)
        }));
        res.json(formatted);
    } catch (e) {
        console.error(">>> [ERROR] Modes fetch failed:", e.message);
        res.json([{ id: "tube", label: "Underground", iconUrl: getIconUrl("tube") }]);
    }
});

/**
 * Data API: Lines filtered by Mode
 */
router.get('/sdui/app/data/lines', async (req, res) => {
    const mode = req.query.mode;
    try {
        const response = await axios.get(`https://api.stationly.co.uk/StationlyBE/api/v1/lines/mode/${mode}`);
        const formatted = response.data.map(line => ({
            id: line.id,
            label: line.name
        }));
        res.json(formatted);
    } catch (e) {
        res.json([]);
    }
});

/**
 * Data API: Directions for a Line
 */
router.get('/sdui/app/data/directions', async (req, res) => {
    const line = req.query.line;
    try {
        const response = await axios.get(`https://api.stationly.co.uk/StationlyBE/api/v1/lines/${line}/route`);
        const formatted = response.data.directions.map(dir => {
            const dirName = dir.direction.charAt(0).toUpperCase() + dir.direction.slice(1);
            let label = `${dirName} towards`;
            if (dir.destinations && dir.destinations.length > 0) {
                const destNames = dir.destinations.map(d => formatDestination(d.name)).join('\n');
                label = `${dirName} towards\n${destNames}`;
            }
            return {
                id: dir.direction,
                label: label
            };
        });
        res.json(formatted);
    } catch (e) {
        res.json([{ id: "inbound", label: "Inbound" }, { id: "outbound", label: "Outbound" }]);
    }
});

/**
 * Data API: Stations filtered by Line and Direction
 */
router.get('/sdui/app/data/stations', async (req, res) => {
    const { line, direction } = req.query;
    try {
        const response = await axios.get(`https://api.stationly.co.uk/StationlyBE/api/v1/stations/search?searchKey=${line}_${direction}`);
        const formatted = response.data.map(station => ({
            id: station.naptanId,
            label: station.commonName
        }));
        res.json(formatted);
    } catch (e) {
        res.json([]);
    }
});

/**
 * User/Device Sync: Stations Subscription
 */
router.post('/user/sync/stations', (req, res) => {
    console.log(`>>> [SYNC] Received station sync for user: ${req.body.uid}`);
    res.json({ success: true });
});

module.exports = router;
