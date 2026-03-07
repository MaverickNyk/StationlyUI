const express = require('express');
const cors = require('cors');
const app = express();
const port = 3000;

app.use(cors());

// Simple logging middleware
app.use((req, res, next) => {
    console.log(`${new Date().toISOString()} - ${req.method} ${req.url}`);
    next();
});

/**
 * SDUI LAYOUT API
 * This endpoint tells the Jetpack Compose Android app exactly what UI form to render.
 */
app.get('/sdui/app/layout', (req, res) => {
    res.json({
        id: "station_selection_screen",
        title: "Stationly Setup",
        theme: {
            primaryColor: "#FFB81C", // TfL Amber
            backgroundColor: "#000000"
        },
        loadingMessage: "Connecting to Tfl Signals...",
        successMessage: "Your Board is now active!",
        components: [
            {
                type: "text",
                id: "welcome_header",
                text: "Design Your\nBoard",
                style: "title"
            },
            {
                type: "text",
                id: "welcome_subtitle",
                text: "Select a route to begin tracking live London signals on your home screen.\n\nNote: Predictions are updated every 60s.",
                style: "subtitle"
            },
            {
                type: "dropdown",
                id: "mode",
                label: "1. Select Mode",
                dataSourceUrl: "/sdui/app/data/modes"
            },
            {
                type: "dropdown",
                id: "line",
                label: "2. Select Line",
                dependsOn: "mode",
                dataSourceUrl: "/sdui/app/data/lines?mode={mode}"
            },
            {
                type: "dropdown",
                id: "direction",
                label: "3. Select Direction",
                dependsOn: "line",
                dataSourceUrl: "/sdui/app/data/directions?line={line}"
            },
            {
                type: "dropdown",
                id: "station",
                label: "4. Select Station",
                dependsOn: "direction", // In reality, depends on line+direction, simplifying for demo
                dataSourceUrl: "/sdui/app/data/stations?line={line}&direction={direction}"
            },
            {
                type: "button",
                id: "save_button",
                label: "Activate Live Board",
                action: "SAVE_SELECTION_ACTION",
                color: "#FFB81C"
            }
        ]
    });
});

/**
 * DATA APIs
 * These endpoints would normally wrap the real TfL APIs or business logic,
 * but return the data in a standardized standard format {id, label} for the dropdowns.
 */
const axios = require('axios');

app.get('/sdui/app/data/modes', async (req, res) => {
    try {
        const response = await axios.get('https://api.stationly.co.uk/StationlyBE/api/v1/modes');
        const formatted = response.data.map(mode => ({
            id: mode.modeName,
            label: mode.modeName.charAt(0).toUpperCase() + mode.modeName.slice(1)
        }));
        res.json(formatted);
    } catch (e) {
        res.json([{ id: "tube", label: "Underground" }]);
    }
});

app.get('/sdui/app/data/lines', async (req, res) => {
    const mode = req.query.mode;
    try {
        const response = await axios.get(`https://api.stationly.co.uk/StationlyBE/api/v1/lines/mode/${mode}`);
        const formatted = response.data.map(line => ({
            id: line.id,
            label: line.name
        }));
        res.json(formatted);
    } catch (e) {
        res.json([{ id: "piccadilly", label: "Piccadilly" }]);
    }
});

app.get('/sdui/app/data/directions', async (req, res) => {
    const line = req.query.line;
    try {
        const response = await axios.get(`https://api.stationly.co.uk/StationlyBE/api/v1/lines/${line}/route`);
        const formatted = response.data.directions.map(dir => {
            const dirName = dir.direction.charAt(0).toUpperCase() + dir.direction.slice(1);
            let label = `${dirName} towards`;
            if (dir.destinations && dir.destinations.length > 0) {
                const destNames = dir.destinations.map(d =>
                    d.name.replace(" Underground Station", "")
                        .replace(" DLR Station", "")
                        .replace(" Rail Station", "")
                        .trim()
                ).join('\n');
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

app.get('/sdui/app/data/stations', async (req, res) => {
    const line = req.query.line;
    const direction = req.query.direction;
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

app.listen(port, () => {
    console.log(`SDUI API Engine listening on port ${port}`);
    console.log(`Point your Android emulator here: http://10.0.2.2:3000/sdui/app/layout`)
});
