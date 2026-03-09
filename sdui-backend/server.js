const express = require('express');
const cors = require('cors');
const app = express();
const port = 3000;

// Middleware
app.use(cors());
app.use(express.json());
app.use('/icons', express.static('public/icons'));

// Logging Middleware
app.use((req, res, next) => {
    console.log(`${new Date().toISOString()} - ${req.method} ${req.url}`);
    next();
});

// SDUI & Data Routes
const sduiRoutes = require('./src/routes/sdui-routes');
app.use('/api/v1', sduiRoutes);

// Main Server Entry
app.listen(port, () => {
    console.log(`\n--- [STATIONLY SDUI ENGINE LIVE] ---`);
    console.log(`Port: ${port}`);
    console.log(`Local URL: http://localhost:${port}/api/v1/sdui/app/layout`);
    console.log(`Emulator Path: http://10.0.2.2:${port}/api/v1/sdui/app/layout\n`);
});
