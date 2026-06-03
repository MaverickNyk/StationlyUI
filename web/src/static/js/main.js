window.toggleMenu = function () {
    const header = document.querySelector('.header');
    const open = header.classList.toggle('nav-open');
    const btn = header.querySelector('.hamburger');
    if (btn) btn.setAttribute('aria-expanded', open ? 'true' : 'false');
}

window.switchTab = function (tabId) {
    document.querySelector('.header').classList.remove('nav-open');

    document.querySelectorAll('.tab-content').forEach(tab => {
        tab.classList.remove('active');
    });

    const targetTab = document.getElementById(tabId);
    if (targetTab) targetTab.classList.add('active');

    document.querySelectorAll('.nav-btn').forEach(btn => {
        btn.classList.remove('active');
    });

    Array.from(document.querySelectorAll('.nav-btn'))
        .filter(b => b.textContent.toLowerCase().includes(tabId))
        .forEach(b => b.classList.add('active'));

    window.scrollTo(0, 0);

    if (window.location.hash.substring(1) !== tabId) {
        history.replaceState(null, null, '#' + tabId);
    }
}

const initTabs = () => {
    const hash = window.location.hash.substring(1);
    const validTabs = ['home', 'developer', 'support'];
    if (hash && validTabs.includes(hash)) window.switchTab(hash);
};

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initTabs);
} else {
    initTabs();
}
