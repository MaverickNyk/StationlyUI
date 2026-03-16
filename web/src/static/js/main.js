import './auth/ui-handler.js';

/**
 * Stationly Main Script
 * Handles Navigation Mobile Menu and Tab Switching
 */

// Make functions globally available for HTML onclick handlers
window.toggleMenu = function () {
    document.querySelector('.header').classList.toggle('nav-open');
}

window.switchTab = function (tabId) {
    // Close mobile menu if open
    document.querySelector('.header').classList.remove('nav-open');

    // Hide all tabs
    document.querySelectorAll('.tab-content').forEach(tab => {
        tab.classList.remove('active');
    });
    // Show selected tab (handle potential missing tab)
    const targetTab = document.getElementById(tabId);
    if (targetTab) {
        targetTab.classList.add('active');
    }

    // Update buttons (both desktop and mobile)
    document.querySelectorAll('.nav-btn').forEach(btn => {
        btn.classList.remove('active');
    });

    // Highlight active buttons
    const buttons = Array.from(document.querySelectorAll('.nav-btn'));
    const matchingButtons = buttons.filter(b => b.textContent.toLowerCase().includes(tabId) ||
        (tabId === 'auth' && b.id === 'nav-auth-btn'));
    matchingButtons.forEach(b => b.classList.add('active'));

    // Scroll to top
    window.scrollTo(0, 0);

    // Update URL hash without scrolling (history handling)
    if (window.location.hash.substring(1) !== tabId) {
        history.replaceState(null, null, '#' + tabId);
    }
}

// Initialize Tab from URL Hash on Load
const initTabs = () => {
    const hash = window.location.hash.substring(1);
    const validTabs = ['home', 'developer', 'support', 'auth'];

    if (hash && validTabs.includes(hash)) {
        window.switchTab(hash);
    }
};

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initTabs);
} else {
    initTabs();
}
