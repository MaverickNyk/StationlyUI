const admin = require('firebase-admin');
const fs = require('fs');

const SERVICE_ACCOUNT_FILE = './serviceAccountKey.json';

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
 * The manual SDUI blueprint to send to Android.
 * This is totally decoupled from live data. It just tells the app exactly what the view 
 * structure should look like.
 */
function buildSduiLayoutBlueprint() {
    return {
        id: "940GZZLUKSX", // Example: King's Cross
        title: "King's Cross St. Pancras",
        theme: {
            primaryColor: "#00FF00", // Matrix Green text
            backgroundColor: "#000000" // Pure black background
        },
        components: [
            {
                type: "header",
                title: "Piccadilly : Eastbound - 6"
            },
            {
                type: "row",
                index: "1",           // slot for nearest train
                destination: "Any",   // "Any" means wildcard slot
                eta: ""               // leave blank in template
            },
            {
                type: "row",
                index: "2",           // slot for 2nd train
                destination: "Any",
                eta: ""
            },
            {
                type: "row",
                index: "3",           // slot for 3rd train
                destination: "Any",
                eta: ""
            },
            {
                type: "header",
                title: "Piccadilly : Westbound - 5"
            },
            {
                type: "row",
                index: "1",
                destination: "Any",
                eta: ""
            },
            {
                type: "row",
                index: "2",
                destination: "Any",
                eta: ""
            },
            {
                type: "row",
                index: "3",
                destination: "Any",
                eta: ""
            }
        ]
    };
}

/**
 * Send the FCM layout update silently in the background
 */
async function pushLayoutUpdate(sduiPayload) {
    // We can use a dedicated layout topic or use the same one
    const topic = `Station_${sduiPayload.id}`;

    const message = {
        data: {
            sdui_payload: JSON.stringify(sduiPayload)
        },
        topic: topic
    };

    if (!admin.apps.length) {
        console.log('\n[SIMULATED PUSH] Here is the layout template that would be sent to Android:');
        console.log(JSON.stringify(message, null, 2));
        return;
    }

    try {
        const response = await admin.messaging().send(message);
        console.log(`Successfully pushed Layout Blueprint to Android widget! FCM Message ID:`, response);
    } catch (error) {
        console.error('Error sending FCM message:', error);
    }
}

// Push the template!
const blueprint = buildSduiLayoutBlueprint();
pushLayoutUpdate(blueprint);
