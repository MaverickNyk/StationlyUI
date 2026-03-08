const admin = require('firebase-admin');
const axios = require('axios');
const fs = require('fs');

// Note: You will need to download your service account key from the Firebase Console 
// (Project Settings > Service Accounts > Generate New Private Key) and place it here.
const SERVICE_ACCOUNT_FILE = './serviceAccountKey.json';

// In this example, we'll track the Piccadilly line arrivals at King's Cross
const TRACKED_STATION_ID = '940GZZLUKSX';
const TRACKED_LINE = 'piccadilly';
const DIRECTION = 'inbound'; // Eastbound/Westbound etc.

// Initialize Firebase Admin SDK
try {
    if (fs.existsSync(SERVICE_ACCOUNT_FILE)) {
        const serviceAccount = require(SERVICE_ACCOUNT_FILE);
        admin.initializeApp({
            credential: admin.credential.cert(serviceAccount)
        });
        console.log('Firebase Admin initialized successfully.');
    } else {
        console.warn(`\n⚠️  WARNING: ${SERVICE_ACCOUNT_FILE} not found.`);
        console.warn('To send real push notifications to your Android device, you must:');
        console.warn('1. Go to Firebase Console -> Project Settings -> Service Accounts');
        console.warn('2. Click "Generate new private key"');
        console.warn('3. Save it as "serviceAccountKey.json" in this directory.\n');
    }
} catch (error) {
    console.error('Error initializing Firebase:', error);
}

/**
 * Fetch live data from TfL Unified API
 */
async function fetchTflData() {
    try {
        console.log(`Fetching TfL data for line: ${TRACKED_LINE}, station: ${TRACKED_STATION_ID}...`);
        // Public TfL Arrivals API
        const response = await axios.get(`https://api.tfl.gov.uk/Line/${TRACKED_LINE}/Arrivals/${TRACKED_STATION_ID}`);
        return response.data;
    } catch (error) {
        console.error('Error fetching from TfL:', error.message);
        return [];
    }
}

/**
 * Converts pure TfL data into our SDUI visual specification
 * so Android can render it perfectly without knowing any business logic
 */
function buildSduiPayload(arrivals) {
    // 1. Sort by timeToStation (soonest first)
    arrivals.sort((a, b) => a.timeToStation - b.timeToStation);

    // 2. Initialize the base SDUI payload from our exact Kotlin Schema
    const sduiPayload = {
        id: TRACKED_STATION_ID,
        title: `LIVE: Kings Cross St. Pancras`,
        theme: {
            primaryColor: "#FF9800", // TfL Amber
            backgroundColor: "#000000"
        },
        components: []
    };

    if (arrivals.length === 0) {
        // Send a message component if no trains
        sduiPayload.components.push({
            type: "message",
            text: "No trains currently scheduled.",
            color: "#666666"
        });
        return sduiPayload;
    }

    // 3. Group trains by platform
    const groupedByPlatform = {};
    arrivals.forEach(train => {
        const platform = train.platformName || "Unknown Platform";
        if (!groupedByPlatform[platform]) {
            groupedByPlatform[platform] = [];
        }
        groupedByPlatform[platform].push(train);
    });

    // 4. Construct rows and headers for the UI
    let totalDelays = 0;
    arrivals.forEach(t => { if (t.timeToStation > 1200) totalDelays++ });

    if (totalDelays > 2) {
        sduiPayload.components.push({
            type: "message",
            text: "⚠️ SIGNIFICANT DELAYS EXPECTED",
            color: "#FF5252",
            textAlign: "center"
        });
    }

    for (const [platformName, trains] of Object.entries(groupedByPlatform)) {
        // Add a Header component for this platform
        sduiPayload.components.push({
            type: "header",
            title: `Piccadilly : ${platformName.replace('Platform ', '')}`,
            color: "#FF9800",
            style: "bold"
        });

        // Add up to 3 train rows
        const trainsToShow = trains.slice(0, 3);
        trainsToShow.forEach((train, index) => {
            const minutes = Math.floor(train.timeToStation / 60);
            const etaFormatted = minutes < 1 ? "Due" : `${minutes} min`;

            sduiPayload.components.push({
                type: "row",
                index: (index + 1).toString(),
                destination: train.destinationName,
                eta: etaFormatted,
                etaColor: minutes < 3 ? "#FF5252" : "#FF9800", // Red for soon, Amber for later
                animation: minutes < 1 ? "pulse" : null
            });
        });
    }

    return sduiPayload;
}

/**
 * Send the FCM widget update silently in the background
 */
async function sendWidgetUpdatePush(sduiPayload) {
    // Our Android app subscribes to topics/Station_{stationId}
    const topic = `Station_${TRACKED_STATION_ID}`;

    const message = {
        data: {
            sdui_payload: JSON.stringify(sduiPayload) // Android app will decode this string
        },
        topic: topic // Use FCM Topics to mass-broadcast to anyone observing this station
    };

    if (!admin.apps.length) {
        console.log('\n[SIMULATED PUSH] Since Firebase is not configured, here is what would be sent to Android:');
        console.log(JSON.stringify(message, null, 2));
        return;
    }

    try {
        const response = await admin.messaging().send(message);
        console.log(`Successfully pushed UI update to Android widget! FCM Message ID:`, response);
    } catch (error) {
        console.error('Error sending FCM message:', error);
    }
}

/**
 * Main loop
 */
async function runSduiEngine() {
    console.log('--- SDUI Backend Engine Started ---');

    // 1. Get real data
    const arrivals = await fetchTflData();

    // 2. Map data to Visual SDUI JSON
    const sduiLayout = buildSduiPayload(arrivals);

    // 3. Push visual payload to Android
    await sendWidgetUpdatePush(sduiLayout);
}

// Run the engine
runSduiEngine();

// In a real environment, you run this on a cron schedule or setInterval every 60 seconds
setInterval(runSduiEngine, 60000);
