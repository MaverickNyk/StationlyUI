/**
 * Clock Updater
 * Updates the widget clock display every second
 */

function updateClock() {
    const now = new Date();
    const time = [now.getHours(), now.getMinutes(), now.getSeconds()]
        .map(n => String(n).padStart(2, '0'))
        .join(':');
    
    const clockElements = document.querySelectorAll('.widget-clock');
    clockElements.forEach(el => el.textContent = time);
}

// Initialize clock on page load
document.addEventListener('DOMContentLoaded', () => {
    updateClock();
    setInterval(updateClock, 1000);
});
