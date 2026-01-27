/**
 * Stationly Main Script
 * Handles Navigation Mobile Menu and Tab Switching
 */

function toggleMenu() {
    document.querySelector('.header').classList.toggle('nav-open');
}

function switchTab(tabId) {
    // Close mobile menu if open
    document.querySelector('.header').classList.remove('nav-open');

    // Hide all tabs
    document.querySelectorAll('.tab-content').forEach(tab => {
        tab.classList.remove('active');
    });
    // Show selected tab
    document.getElementById(tabId).classList.add('active');

    // Update buttons (both desktop and mobile)
    document.querySelectorAll('.nav-btn').forEach(btn => {
        btn.classList.remove('active');
    });

    // Highlight active buttons
    const buttons = Array.from(document.querySelectorAll('.nav-btn'));
    const matchingButtons = buttons.filter(b => b.textContent.toLowerCase().includes(tabId));
    matchingButtons.forEach(b => b.classList.add('active'));

    // Scroll to top
    window.scrollTo(0, 0);
}
