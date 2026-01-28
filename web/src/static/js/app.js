/**
 * Stationly Static Site
 * Main application logic
 */

// Handle download button clicks
document.addEventListener('DOMContentLoaded', () => {
    const downloadButtons = document.querySelectorAll('[data-download]');
    downloadButtons.forEach(button => {
        button.addEventListener('click', (e) => {
            e.preventDefault();
            const platform = button.getAttribute('data-download');
            alert(`Coming soon! Download on ${platform}`);
        });
    });

    // Smooth scroll for anchor links
    document.querySelectorAll('a[href^="#"]').forEach(anchor => {
        anchor.addEventListener('click', function (e) {
            const href = this.getAttribute('href');
            if (href !== '#') {
                e.preventDefault();
                const target = document.querySelector(href);
                if (target) {
                    target.scrollIntoView({ behavior: 'smooth' });
                }
            }
        });
    });
});
