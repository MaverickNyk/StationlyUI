/**
 * Stationly Celebration & Confetti Engine
 *
 * Triggers full-screen celebratory multi-colored confetti cannons and a branded
 * "Thank you" modal when a supporter returns from Stripe checkout.
 *
 * Invariant rules:
 * - Brand palette matches mobile app: brand amber (#FFC819), pink (#FF5C8A),
 *   green (#4ADE80), blue (#4A90D9), violet (#B06CF0), orange (#FF8A3D), teal (#34D6D6).
 * - Multi-colored confetti cannons fire from bottom corners plus falling flutter pieces.
 * - Physics: velocity, gravity, drag, and 3D fluttering paper (scaleY = |cos(spin * t)|).
 * - Clean teardown: stops scheduling frames and removes the canvas once done (~3.8s).
 * - Thank-you modal (styles in support.css .thx-*); its words come from the backend.
 * - Strictly zero em dashes in code and copy.
 */

const CONFETTI_COLORS = [
    '#FFC819', // Stationly amber (weighted)
    '#FFC819',
    '#FF5C8A', // Pink
    '#4ADE80', // Green
    '#4A90D9', // Blue
    '#B06CF0', // Violet
    '#FF8A3D', // Orange
    '#34D6D6', // Teal
];

export function launchConfetti() {
    // Avoid multiple overlapping canvases
    const existing = document.getElementById('stationly-confetti-canvas');
    if (existing && existing.parentNode) {
        existing.parentNode.removeChild(existing);
    }

    const canvas = document.createElement('canvas');
    canvas.id = 'stationly-confetti-canvas';
    canvas.style.position = 'fixed';
    canvas.style.top = '0';
    canvas.style.left = '0';
    canvas.style.width = '100vw';
    canvas.style.height = '100vh';
    canvas.style.pointerEvents = 'none';
    canvas.style.zIndex = '99999';
    document.body.appendChild(canvas);

    const ctx = canvas.getContext('2d');
    let width = (canvas.width = window.innerWidth);
    let height = (canvas.height = window.innerHeight);

    const handleResize = () => {
        width = canvas.width = window.innerWidth;
        height = canvas.height = window.innerHeight;
    };
    window.addEventListener('resize', handleResize);

    const particleCount = 150;
    const particles = [];

    // Cannons fire from bottom corners plus top shower
    for (let i = 0; i < particleCount; i++) {
        const fromLeft = i % 2 === 0;
        const isCannon = i < 110;

        let x, y, vx, vy;
        if (isCannon) {
            if (fromLeft) {
                x = width * 0.05 + Math.random() * (width * 0.08);
                y = height + 10;
                const angle = (50 + Math.random() * 40) * (Math.PI / 180);
                const speed = 16 + Math.random() * 14;
                vx = Math.cos(angle) * speed;
                vy = -Math.sin(angle) * speed;
            } else {
                x = width * 0.87 + Math.random() * (width * 0.08);
                y = height + 10;
                const angle = (90 + Math.random() * 40) * (Math.PI / 180);
                const speed = 16 + Math.random() * 14;
                vx = -Math.cos(angle) * speed;
                vy = -Math.sin(angle) * speed;
            }
        } else {
            x = Math.random() * width;
            y = -20 - Math.random() * 80;
            vx = (Math.random() - 0.5) * 3;
            vy = 2 + Math.random() * 4;
        }

        particles.push({
            x,
            y,
            vx,
            vy,
            size: 6 + Math.random() * 6,
            color: CONFETTI_COLORS[Math.floor(Math.random() * CONFETTI_COLORS.length)],
            spin: 3 + Math.random() * 7,
            phase: Math.random() * Math.PI * 2,
            wobble: Math.random() * Math.PI * 2,
            round: Math.random() < 0.22,
        });
    }

    const startTime = performance.now();
    const duration = 3800; // 3.8s matching mobile app

    function render(now) {
        const elapsed = now - startTime;
        const progress = Math.min(elapsed / duration, 1);
        const t = elapsed / 1000;

        ctx.clearRect(0, 0, width, height);

        // Graceful alpha fade in the final 25% of animation
        const alpha = progress > 0.75 ? 1 - (progress - 0.75) / 0.25 : 1;
        ctx.globalAlpha = Math.max(0, alpha);

        for (let i = 0; i < particles.length; i++) {
            const p = particles[i];

            // Physics: gravity and air resistance
            p.vy += 0.36;
            p.vx *= 0.985;
            p.vy *= 0.985;

            p.x += p.vx + Math.sin(t * 3 + p.wobble) * 0.5;
            p.y += p.vy;

            // 3D paper flutter simulation
            const flutter = Math.abs(Math.cos(t * p.spin + p.phase));

            ctx.fillStyle = p.color;
            ctx.beginPath();

            if (p.round) {
                ctx.arc(p.x, p.y, Math.max(0.1, (p.size / 2) * flutter), 0, Math.PI * 2);
            } else {
                ctx.save();
                ctx.translate(p.x, p.y);
                ctx.rotate(t * 2 + p.phase);
                ctx.scale(1, Math.max(0.05, flutter));
                ctx.fillRect(-p.size / 2, -p.size / 2, p.size, p.size * 1.4);
                ctx.restore();
            }
            ctx.fill();
        }

        if (progress < 1) {
            requestAnimationFrame(render);
        } else {
            window.removeEventListener('resize', handleResize);
            if (canvas.parentNode) {
                canvas.parentNode.removeChild(canvas);
            }
        }
    }

    requestAnimationFrame(render);
}

/**
 * @param {{ title?: string, note?: string }} [thanks] from the backend web
 *   config. Rendered with textContent, never as HTML.
 */
export function showThankYouModal(thanks) {
    if (document.getElementById('support-thanks-overlay')) return;

    const returnFocus = document.activeElement;
    const overlay = document.createElement('div');
    overlay.id = 'support-thanks-overlay';
    overlay.className = 'thx-overlay';
    overlay.setAttribute('role', 'dialog');
    overlay.setAttribute('aria-modal', 'true');
    overlay.setAttribute('aria-labelledby', 'thx-title');

    overlay.innerHTML = `
        <div class="thx-card">
            <button type="button" class="thx-close" aria-label="Close">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>
            </button>
            <div class="thx-heart" aria-hidden="true">
                <svg viewBox="0 0 24 24"><path d="M12 20.5s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.6a4.3 4.3 0 0 1 7.5 2.7c0 5.6-7.5 10.2-7.5 10.2z"/></svg>
            </div>
            <h2 id="thx-title" class="gradient-text"></h2>
            <p class="thx-note"></p>
            <button type="button" class="thx-done">Close</button>
        </div>
    `;

    overlay.querySelector('#thx-title').textContent = thanks?.title || 'Thank you';
    const note = overlay.querySelector('.thx-note');
    if (thanks?.note) note.textContent = thanks.note;
    else note.remove();

    document.body.appendChild(overlay);
    requestAnimationFrame(() => requestAnimationFrame(() => overlay.classList.add('is-open')));

    const doneBtn = overlay.querySelector('.thx-done');
    setTimeout(() => doneBtn.focus({ preventScroll: true }), 350);

    const handleKey = (e) => {
        if (e.key === 'Escape') dismiss();
        if (e.key === 'Tab') {
            // Keep focus inside the dialog
            const f = overlay.querySelectorAll('button');
            const first = f[0];
            const last = f[f.length - 1];
            if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
            else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
        }
    };

    function dismiss() {
        window.removeEventListener('keydown', handleKey);
        overlay.classList.remove('is-open');
        setTimeout(() => {
            overlay.remove();
            if (returnFocus && returnFocus.focus) returnFocus.focus({ preventScroll: true });
        }, 300);
    }

    doneBtn.addEventListener('click', dismiss);
    overlay.querySelector('.thx-close').addEventListener('click', dismiss);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) dismiss(); });
    window.addEventListener('keydown', handleKey);
}
