/**
 * Page and Section Navigation System
 */

document.addEventListener('DOMContentLoaded', function() {
    const pageButtons = document.querySelectorAll('[data-page]');
    const sectionLinks = document.querySelectorAll('[data-section]');
    const pageContents = document.querySelectorAll('.page-content');
    const footer = document.getElementById('footer');
    const logoSection = document.querySelector('.logo-section');
    const apiIframe = document.getElementById('api-iframe');
    let apiLoaded = false;

    // Logo click - navigate to home
    if (logoSection) {
        logoSection.style.cursor = 'pointer';
        logoSection.addEventListener('click', function() {
            const homeButton = document.querySelector('[data-page="home"]');
            homeButton.click();
        });
    }

    // Page navigation (Home, Developer)
    pageButtons.forEach(button => {
        button.addEventListener('click', function() {
            const pageName = this.getAttribute('data-page');
            
            // Remove active class from all pages and buttons
            pageButtons.forEach(btn => btn.classList.remove('tab-button-active'));
            pageContents.forEach(page => page.classList.remove('page-content-active'));
            
            // Add active class to clicked button and page
            this.classList.add('tab-button-active');
            const activePage = document.getElementById(`page-${pageName}`);
            if (activePage) {
                activePage.classList.add('page-content-active');
                
                // Hide footer on developer page, show on home
                if (pageName === 'developer') {
                    footer.style.display = 'none';
                    
                    // Lazy load API iframe on first Developer tab click only
                    if (!apiLoaded) {
                        const loader = document.querySelector('.developer-loader');
                        if (loader) {
                            loader.style.display = 'flex';
                        }
                        apiIframe.src = 'https://api.stationly.co.uk';
                        apiLoaded = true;
                    }
                } else {
                    footer.style.display = 'block';
                }
            }
            
            // Scroll to top
            window.scrollTo(0, 0);
        });
    });

    // Section navigation (Features, Download) - scrolls within Home page
    sectionLinks.forEach(link => {
        link.addEventListener('click', function() {
            const sectionName = this.getAttribute('data-section');
            
            // Remove ALL active classes from both buttons and links
            pageButtons.forEach(btn => btn.classList.remove('tab-button-active'));
            sectionLinks.forEach(l => l.classList.remove('section-link-active'));
            pageContents.forEach(page => page.classList.remove('page-content-active'));
            
            // Make sure Home page is active
            const homeButton = document.querySelector('[data-page="home"]');
            homeButton.classList.add('tab-button-active');
            document.getElementById('page-home').classList.add('page-content-active');
            footer.style.display = 'block';
            
            // Highlight section link
            this.classList.add('section-link-active');
            
            // Scroll to section
            const section = document.getElementById(`section-${sectionName}`);
            if (section) {
                setTimeout(() => {
                    section.scrollIntoView({ behavior: 'smooth' });
                }, 100);
            }
        });
    });
});
