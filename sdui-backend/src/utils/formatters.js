/**
 * Tfl Data Formatter Utilities
 * Handles icons, labeling, and data transformation for SDUI.
 */

const MODE_ICONS = {
    'tube': '/icons/tube.png',
    'underground': '/icons/tube.png',
    'bus': '/icons/bus.png',
    'dlr': '/icons/dlr.png',
    'elizabeth-line': '/icons/elizabeth.png',
    'elizabeth': '/icons/elizabeth.png',
    'overground': '/icons/overground.png'
};

/**
 * Maps a TfL mode to its local icon path.
 * @param {string} modeName 
 * @returns {string|null}
 */
function getIconPath(modeName) {
    if (!modeName) return null;
    const m = modeName.toLowerCase();
    return MODE_ICONS[m] || null;
}

/**
 * Human-readable mode labels (e.g., 'elizabeth-line' -> 'Elizabeth Line')
 * @param {string} modeName 
 * @returns {string}
 */
function formatModeLabel(modeName) {
    if (!modeName) return "";
    return modeName
        .split('-')
        .map(word => word.charAt(0).toUpperCase() + word.slice(1))
        .join(' ');
}

/**
 * Clean up destination names for better mobile UI fit
 * @param {string} name 
 * @returns {string}
 */
function formatDestination(name) {
    if (!name) return "";
    return name
        .replace(" Underground Station", "")
        .replace(" DLR Station", "")
        .replace(" Rail Station", "")
        .trim();
}

/**
 * Returns the fully qualified icon URL for a mode
 * @param {string} modeName 
 * @param {string} baseUrl 
 * @returns {string|null}
 */
function getIconUrl(modeName, baseUrl = "http://localhost:3000") {
    const path = getIconPath(modeName);
    return path ? `${baseUrl}${path}` : null;
}

module.exports = {
    getIconPath,
    formatModeLabel,
    formatDestination,
    getIconUrl
};
