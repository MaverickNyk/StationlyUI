/* Stationly cookie consent (UK PECR / UK GDPR) — shared across every page.
   Injects the banner if it isn't already present, persists the choice site-wide,
   acts on it, and exposes window.manageCookies() for the footer "Cookie settings" link. */
(function () {
  var CONSENT_KEY = 'stationly_cookie_consent';

  function ensureBanner() {
    var el = document.getElementById('cookie-banner');
    if (el) return el;
    el = document.createElement('div');
    el.className = 'cookie-banner';
    el.id = 'cookie-banner';
    el.setAttribute('role', 'region');
    el.setAttribute('aria-label', 'Cookie consent');
    el.setAttribute('hidden', '');
    el.innerHTML =
      '<div class="cookie-inner">' +
        '<p class="cookie-text">We use essential cookies to make Stationly work, plus optional analytics cookies to learn what’s useful. The choice is yours. ' +
          '<a href="/privacy/">Read our Privacy Policy</a>.' +
        '</p>' +
        '<div class="cookie-actions">' +
          '<button class="cookie-btn cookie-btn-ghost" type="button" data-consent="rejected">Reject optional</button>' +
          '<button class="cookie-btn cookie-btn-primary" type="button" data-consent="accepted">Accept all</button>' +
        '</div>' +
      '</div>';
    document.body.appendChild(el);
    return el;
  }

  var banner = ensureBanner();

  function enableOptionalCookies() {
    // Load analytics / non-essential cookies ONLY here, gated on consent.
    // e.g. if (!window.__analyticsLoaded) { /* inject GA/Plausible */ window.__analyticsLoaded = true; }
  }
  function disableOptionalCookies() {
    ['_ga', '_gid', '_gat', '_ga_CONTAINER'].forEach(function (n) {
      document.cookie = n + '=; Max-Age=0; path=/;';
      document.cookie = n + '=; Max-Age=0; path=/; domain=.' + location.hostname + ';';
    });
    window.__analyticsLoaded = false;
  }
  function applyConsent(choice) {
    window.__cookieConsent = choice;
    if (choice === 'accepted') enableOptionalCookies();
    else disableOptionalCookies();
  }
  function show() {
    banner.removeAttribute('hidden');
    requestAnimationFrame(function () { banner.classList.add('show'); });
  }
  function hide() {
    banner.classList.remove('show');
    setTimeout(function () { banner.setAttribute('hidden', ''); }, 320);
  }
  function set(choice) {
    try { localStorage.setItem(CONSENT_KEY, choice); } catch (e) {}
    applyConsent(choice);
    hide();
  }

  window.setCookieConsent = set;
  window.manageCookies = function (e) { if (e && e.preventDefault) e.preventDefault(); show(); };

  banner.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-consent]');
    if (btn) set(btn.getAttribute('data-consent'));
  });

  var saved = null;
  try { saved = localStorage.getItem(CONSENT_KEY); } catch (e) {}
  if (saved === 'accepted' || saved === 'rejected') {
    applyConsent(saved);       // honour the prior choice on every page load
  } else {
    window.__cookieConsent = null;
    show();                    // no valid choice yet, so always ask
  }
})();
