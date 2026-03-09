/**
 * Static SDUI Layout Template
 * Defines the JETPACK COMPOSE UI form structure.
 */

const SELECTION_LAYOUT = {
    id: "station_selection_screen",
    version: "1.2",
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
            dependsOn: "direction",
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
};

module.exports = {
    SELECTION_LAYOUT
};
