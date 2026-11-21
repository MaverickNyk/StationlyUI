/* Stationly staging banner, shared by every page that loads it.
   Shown only on staging.stationly.co.uk; does nothing anywhere else. */
(function () {
    if (window.location.hostname === 'staging.stationly.co.uk') {
        var stStyle = document.createElement('style');
        stStyle.textContent = [
            '.st-dot{width:7px;height:7px;border-radius:50%;background:#F5A623;flex-shrink:0;',
            'animation:stPulse 2s ease-in-out infinite;}',
            '@keyframes stPulse{0%,100%{opacity:1;box-shadow:0 0 0 0 rgba(245,166,35,0.55)}',
            '55%{opacity:0.75;box-shadow:0 0 0 6px rgba(245,166,35,0)}}',
            '#st-close{position:absolute;right:14px;top:50%;transform:translateY(-50%);',
            'background:none;border:none;color:#7A8499;cursor:pointer;font-size:19px;',
            'line-height:1;padding:4px 7px;}',
            '#st-close:hover{color:#EFF3FC;}'
        ].join('');
        document.head.appendChild(stStyle);

        var stBanner = document.createElement('div');
        stBanner.id = 'st-banner';
        stBanner.style.cssText = [
            'position:fixed;top:0;left:0;right:0;z-index:9999;',
            'background:rgba(8,13,26,0.97);backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);',
            'border-bottom:1px solid rgba(245,166,35,0.2);',
            'padding:8px 48px;display:flex;align-items:center;justify-content:center;gap:10px;',
            'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;'
        ].join('');
        stBanner.innerHTML = [
            '<span style="display:inline-flex;align-items:center;gap:6px;',
            'background:rgba(245,166,35,0.1);border:1px solid rgba(245,166,35,0.25);',
            'border-radius:20px;padding:3px 10px 3px 7px;font-size:10.5px;font-weight:700;',
            'letter-spacing:0.08em;color:#F5A623;text-transform:uppercase;flex-shrink:0;">',
            '<span class="st-dot"></span>STAGING</span>',
            '<span style="font-size:12.5px;color:#7A8499;">',
            'You\'re on the staging server. Data and behaviour may differ from production</span>',
            '<button id="st-close" title="Dismiss">&#x2715;</button>'
        ].join('');
        document.body.prepend(stBanner);

        // Push fixed nav down so it clears the banner
        var bh = stBanner.offsetHeight;
        var hdr = document.querySelector('.header');
        if (hdr) hdr.style.top = bh + 'px';

        document.getElementById('st-close').addEventListener('click', function () {
            stBanner.style.display = 'none';
            if (hdr) hdr.style.top = '0';
        });
    }
})();
