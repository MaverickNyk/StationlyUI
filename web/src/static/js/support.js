/**
 * Support Stationly page.
 *
 * Backend-driven: amounts, copy, the thank-you and this environment's Stripe
 * Payment Links all come from `GET /api/v1/support-money/web`
 * (stationly-backend SupportMoneyConfigService.getWebConfig). This file holds
 * no links and no copy. If the config cannot be loaded, or the backend says the
 * web checkout is closed, the button is disabled rather than guessing.
 *
 * Environment follows the hostname, the same rule the home page uses for its
 * API: stationly.co.uk talks to production (live links), localhost to a
 * local backend on :3000, and everything else to staging (test links).
 */
import { launchConfetti, showThankYouModal } from './celebration.js';

const PROD_HOSTS = ['stationly.co.uk', 'www.stationly.co.uk'];
const LOCAL_HOSTS = ['localhost', '127.0.0.1'];
const API_BASE = PROD_HOSTS.includes(window.location.hostname)
    ? 'https://api.stationly.co.uk'
    : LOCAL_HOSTS.includes(window.location.hostname)
        ? 'http://localhost:3000' // a local backend (`npm run dev` in stationly-backend)
        : 'https://staging-api.stationly.co.uk';

const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

async function loadConfig() {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 8000);
    try {
        const res = await fetch(`${API_BASE}/api/v1/support-money/web`, {
            signal: ctrl.signal,
            credentials: 'omit',
            referrerPolicy: 'no-referrer',
        });
        if (!res.ok) return null;
        const cfg = await res.json();
        return Array.isArray(cfg?.tiers) && cfg.tiers.length ? cfg : null;
    } catch {
        return null;
    } finally {
        clearTimeout(timer);
    }
}

/** Accept only what the backend is allowed to send: a Stripe Payment Link. */
function safeCheckoutUrl(raw) {
    try {
        const u = new URL(raw);
        return u.protocol === 'https:' && u.hostname === 'buy.stripe.com' ? u.toString() : '';
    } catch {
        return '';
    }
}

/* ── Rolling amount in the button ────────────────────────────── */

function rollTo(el, text) {
    const prev = el.querySelector('.sp-roll-item:not(.is-leaving)');
    if (prev && prev.textContent === text) return;

    // Anything still on its way out from a previous fast tap goes now, so
    // at most two items (one leaving, one entering) ever share the slot.
    el.querySelectorAll('.sp-roll-item.is-leaving').forEach((n) => n.remove());

    const next = document.createElement('span');
    next.className = 'sp-roll-item';
    next.textContent = text;

    if (!prev || reduceMotion) {
        el.replaceChildren(next);
        return;
    }
    // Drop is-entering first: both classes set `animation`, and the later
    // rule would win, leaving the old amount parked on top of the new one.
    prev.classList.remove('is-entering');
    prev.classList.add('is-leaving');
    next.classList.add('is-entering');
    el.appendChild(next);
    const drop = () => prev.remove();
    prev.addEventListener('animationend', drop, { once: true });
    setTimeout(drop, 400); // if animationend never fires
}

/* ── Tier picker ─────────────────────────────────────────────── */

const CHECK_SVG = '<svg viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>';

function renderPicker(cfg) {
    const group = document.querySelector('.sp-tiers');
    const hintBox = document.querySelector('.sp-hint');
    const cta = document.getElementById('sp-cta');
    const amountEl = document.getElementById('sp-cta-amount');

    const tiers = cfg.tiers.map((t) => ({ ...t, url: cfg.enabled ? safeCheckoutUrl(t.url) : '' }));
    const open = cfg.enabled && tiers.every((t) => t.url);

    // Rows and hints are built with textContent only: nothing from the
    // network is ever parsed as HTML.
    group.replaceChildren(...tiers.map((t) => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'sp-tier';
        btn.dataset.tier = t.id;
        btn.setAttribute('role', 'radio');

        const amount = document.createElement('span');
        amount.className = 'sp-tier-amount' + (t.amount_minor > 0 ? '' : ' is-word');
        amount.textContent = t.display;
        const label = document.createElement('span');
        label.className = 'sp-tier-label';
        label.textContent = t.label;
        const radio = document.createElement('span');
        radio.className = 'sp-radio';
        radio.setAttribute('aria-hidden', 'true');
        radio.innerHTML = CHECK_SVG; // static markup, not config

        btn.append(amount, label, radio);
        return btn;
    }));

    // Every hint shares one grid cell, so the box is always as tall as the
    // longest one and nothing below it moves when the selection changes.
    hintBox.replaceChildren(...tiers.map((t) => {
        const span = document.createElement('span');
        span.className = 'sp-hint-text';
        span.dataset.for = t.id;
        span.textContent = t.hint;
        return span;
    }));

    let current = null;
    const select = (id, focus = false) => {
        const tier = tiers.find((t) => t.id === id);
        if (!tier || id === current) return;
        current = id;
        group.querySelectorAll('.sp-tier').forEach((b) => {
            const on = b.dataset.tier === id;
            b.setAttribute('aria-checked', String(on));
            b.tabIndex = on ? 0 : -1;
            if (on && focus) b.focus();
        });
        hintBox.querySelectorAll('.sp-hint-text').forEach((h) => {
            h.classList.toggle('is-shown', h.dataset.for === id);
        });
        if (open) cta.href = tier.url;
        rollTo(amountEl, tier.amount_minor > 0 ? tier.display : '');
    };

    group.addEventListener('click', (e) => {
        const btn = e.target.closest('.sp-tier');
        if (btn) select(btn.dataset.tier);
    });
    // Arrow keys move through the radiogroup, as a native radio set would.
    group.addEventListener('keydown', (e) => {
        const step = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 }[e.key];
        if (!step) return;
        e.preventDefault();
        const i = tiers.findIndex((t) => t.id === current);
        select(tiers[(i + step + tiers.length) % tiers.length].id, true);
    });

    select(tiers.some((t) => t.id === cfg.default_tier_id) ? cfg.default_tier_id : tiers[0].id);
    group.removeAttribute('aria-busy');
    if (open) cta.classList.remove('is-closed');
    else closeCheckout();
}

/** No config, or the backend says closed: keep the page, disable the button. */
function closeCheckout() {
    const cta = document.getElementById('sp-cta');
    cta.removeAttribute('href');
    cta.setAttribute('aria-disabled', 'true');
    cta.classList.add('is-closed');
    cta.querySelector('.sp-cta-label').textContent = 'Opening soon';
    cta.querySelector('.sp-cta-end').hidden = true;
}

/* ── Split-flap settle on the mini board ─────────────────────── */

const FLAP_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';

function flap(el, delay) {
    const target = el.dataset.text || el.textContent;
    if (reduceMotion) { el.textContent = target; return; }

    const chars = target.split('');
    const settleAt = chars.map((_, i) => delay + 260 + i * 38 + Math.random() * 90);
    const start = performance.now();

    function frame(now) {
        const t = now - start;
        let done = true;
        el.textContent = chars.map((c, i) => {
            if (c === ' ' || t >= settleAt[i]) return c;
            done = false;
            return t < delay ? ' ' : FLAP_CHARS[(Math.random() * FLAP_CHARS.length) | 0];
        }).join('');
        if (!done) requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
    // Whatever happens to rAF (background tab, throttling), land on the real words.
    setTimeout(() => { el.textContent = target; }, Math.max(...settleAt) + 120);
}

/* ── Returning from Stripe ───────────────────────────────────── */

function takeSuccessFlag() {
    const params = new URLSearchParams(window.location.search);
    if (params.get('support') !== 'success') return false;
    // Clean the URL straight away so a refresh or a shared link does not
    // replay the thank-you.
    window.history.replaceState(null, document.title, window.location.pathname);
    return true;
}

async function init() {
    document.querySelectorAll('.sp-flap').forEach((el, i) => flap(el, 380 + i * 140));

    const returning = takeSuccessFlag();
    const cfg = await loadConfig();
    if (cfg) {
        renderPicker(cfg);
    } else {
        // Nothing to choose from: drop the placeholders rather than leave
        // them shimmering under a disabled button.
        document.querySelectorAll('.sp-card-head, .sp-tiers, .sp-hint').forEach((el) => { el.hidden = true; });
        closeCheckout();
    }

    if (returning) {
        if (!reduceMotion) launchConfetti();
        showThankYouModal(cfg?.thanks);
    }
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
else init();
