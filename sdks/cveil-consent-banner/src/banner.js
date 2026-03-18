/**
 * Cveil Consent Banner Widget
 *
 * Embeddable consent management widget. Customers include this script via:
 *   <script src="https://cdn.cveil.com/banner.min.js" data-site-id="cvs_xxx"></script>
 *
 * The widget automatically:
 *   1. Fetches banner configuration from the Cveil public API
 *   2. Checks localStorage for existing consent state
 *   3. Renders a consent banner if consent has not been given
 *   4. Provides a preferences modal for granular purpose control
 *   5. Sends grant/revoke requests to the Cveil API
 */

(function () {
    'use strict';

    const STORAGE_KEY = 'cveil_consent';
    const BANNER_ID = 'cveil-consent-banner';
    const MODAL_ID = 'cveil-consent-modal';

    // Auto-detect script element and extract configuration
    const scriptEl = document.currentScript || document.querySelector('script[data-site-id]');
    if (!scriptEl) return;

    const siteId = scriptEl.getAttribute('data-site-id');
    const apiBase = scriptEl.getAttribute('data-api-base') || inferApiBase();
    const subjectId = scriptEl.getAttribute('data-subject') || getOrCreateSubjectId();

    if (!siteId) {
        console.warn('[Cveil] Missing data-site-id attribute on script tag');
        return;
    }

    function inferApiBase() {
        // Default: same origin as the script source, or fallback to current page origin
        try {
            const src = scriptEl.src || scriptEl.getAttribute('src');
            if (src) {
                const url = new URL(src);
                return url.origin;
            }
        } catch (_) {}
        return window.location.origin;
    }

    function getOrCreateSubjectId() {
        let id = localStorage.getItem('cveil_subject_id');
        if (!id) {
            id = 'anon_' + crypto.randomUUID();
            localStorage.setItem('cveil_subject_id', id);
        }
        return id;
    }

    // -- State management ---------------------------------------------------

    function getConsent() {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            return raw ? JSON.parse(raw) : null;
        } catch (_) {
            return null;
        }
    }

    function saveConsent(purposes) {
        localStorage.setItem(STORAGE_KEY, JSON.stringify({
            siteId,
            purposes,
            timestamp: new Date().toISOString()
        }));
    }

    // -- API client ---------------------------------------------------------

    async function fetchConfig() {
        const res = await fetch(`${apiBase}/api/v1/public/consent/config/${siteId}`);
        if (!res.ok) throw new Error('Failed to load banner config');
        return res.json();
    }

    async function grantConsent(purposeName) {
        const res = await fetch(`${apiBase}/api/v1/public/consent/grant`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                siteId,
                subject: subjectId,
                scope: { purposeName, category: '*', fieldName: '*', entityName: '*' }
            })
        });
        if (!res.ok) throw new Error(`Grant failed for ${purposeName}: ${res.status}`);
    }

    async function revokeConsent(purposeName) {
        const res = await fetch(`${apiBase}/api/v1/public/consent/revoke`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                siteId,
                subject: subjectId,
                scope: { purposeName, category: '*', fieldName: '*', entityName: '*' }
            })
        });
        if (!res.ok) throw new Error(`Revoke failed for ${purposeName}: ${res.status}`);
    }

    // -- UI rendering -------------------------------------------------------

    function injectStyles() {
        if (document.getElementById('cveil-banner-styles')) return;
        const style = document.createElement('style');
        style.id = 'cveil-banner-styles';
        style.textContent = `
            #${BANNER_ID} {
                position: fixed; left: 0; right: 0; z-index: 999999;
                background: #fff; color: #1f2937;
                box-shadow: 0 -2px 20px rgba(0,0,0,0.12);
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                font-size: 14px; line-height: 1.5;
                padding: 16px 24px;
                display: flex; align-items: center; justify-content: space-between;
                gap: 16px; flex-wrap: wrap;
            }
            #${BANNER_ID}.cveil-top { top: 0; }
            #${BANNER_ID}.cveil-bottom { bottom: 0; }
            #${BANNER_ID} .cveil-text { flex: 1; min-width: 200px; }
            #${BANNER_ID} .cveil-text p { margin: 0; }
            #${BANNER_ID} .cveil-actions { display: flex; gap: 8px; flex-shrink: 0; }
            #${BANNER_ID} .cveil-btn {
                padding: 8px 20px; border-radius: 6px; cursor: pointer;
                font-size: 13px; font-weight: 600; border: none; transition: opacity 0.15s;
            }
            #${BANNER_ID} .cveil-btn:hover { opacity: 0.9; }
            #${BANNER_ID} .cveil-btn-accept { background: #4f46e5; color: #fff; }
            #${BANNER_ID} .cveil-btn-reject { background: #e5e7eb; color: #374151; }
            #${BANNER_ID} .cveil-btn-prefs { background: transparent; color: #4f46e5; text-decoration: underline; }

            #${MODAL_ID} {
                position: fixed; inset: 0; z-index: 1000000;
                background: rgba(0,0,0,0.5); display: flex;
                align-items: center; justify-content: center;
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            }
            #${MODAL_ID} .cveil-modal-content {
                background: #fff; border-radius: 12px; padding: 24px;
                max-width: 500px; width: 90%; max-height: 80vh; overflow-y: auto;
                box-shadow: 0 20px 60px rgba(0,0,0,0.2);
            }
            #${MODAL_ID} h2 { margin: 0 0 16px; font-size: 18px; color: #111827; }
            #${MODAL_ID} .cveil-purpose {
                display: flex; justify-content: space-between; align-items: center;
                padding: 12px 0; border-bottom: 1px solid #f3f4f6;
            }
            #${MODAL_ID} .cveil-purpose:last-child { border-bottom: none; }
            #${MODAL_ID} .cveil-purpose label { font-size: 14px; color: #374151; cursor: pointer; }
            #${MODAL_ID} .cveil-toggle {
                width: 44px; height: 24px; background: #d1d5db; border-radius: 12px;
                position: relative; cursor: pointer; transition: background 0.2s; border: none;
            }
            #${MODAL_ID} .cveil-toggle.active { background: #4f46e5; }
            #${MODAL_ID} .cveil-toggle::after {
                content: ''; position: absolute; width: 20px; height: 20px;
                background: #fff; border-radius: 50%; top: 2px; left: 2px;
                transition: transform 0.2s;
            }
            #${MODAL_ID} .cveil-toggle.active::after { transform: translateX(20px); }
            #${MODAL_ID} .cveil-modal-actions {
                display: flex; gap: 8px; margin-top: 20px; justify-content: flex-end;
            }
        `;
        document.head.appendChild(style);
    }

    function renderBanner(config) {
        removeBanner();
        injectStyles();

        const text = config.customText || {};
        const banner = document.createElement('div');
        banner.id = BANNER_ID;
        banner.className = config.bannerPosition === 'top' ? 'cveil-top' : 'cveil-bottom';
        banner.setAttribute('role', 'banner');
        banner.setAttribute('aria-label', 'Cookie consent');

        const textDiv = document.createElement('div');
        textDiv.className = 'cveil-text';
        const msgP = document.createElement('p');
        msgP.textContent = text.message || 'We use cookies and similar technologies to improve your experience. You can manage your preferences below.';
        textDiv.appendChild(msgP);

        const actionsDiv = document.createElement('div');
        actionsDiv.className = 'cveil-actions';

        const prefsBtn = document.createElement('button');
        prefsBtn.className = 'cveil-btn cveil-btn-prefs';
        prefsBtn.textContent = text.preferencesLabel || 'Preferences';
        prefsBtn.addEventListener('click', () => showPreferences(config));

        const rejectBtn = document.createElement('button');
        rejectBtn.className = 'cveil-btn cveil-btn-reject';
        rejectBtn.textContent = text.rejectLabel || 'Reject All';
        rejectBtn.addEventListener('click', () => rejectAll(config));

        const acceptBtn = document.createElement('button');
        acceptBtn.className = 'cveil-btn cveil-btn-accept';
        acceptBtn.textContent = text.acceptLabel || 'Accept All';
        acceptBtn.addEventListener('click', () => acceptAll(config));

        actionsDiv.append(prefsBtn, rejectBtn, acceptBtn);
        banner.append(textDiv, actionsDiv);
        document.body.appendChild(banner);
    }

    function removeBanner() {
        document.getElementById(BANNER_ID)?.remove();
    }

    function showPreferences(config) {
        document.getElementById(MODAL_ID)?.remove();
        const existing = getConsent();
        const purposeStates = {};
        (config.purposes || []).forEach(p => {
            purposeStates[p] = existing?.purposes?.[p] ?? false;
        });

        const modal = document.createElement('div');
        modal.id = MODAL_ID;
        modal.setAttribute('role', 'dialog');
        modal.setAttribute('aria-label', 'Cookie preferences');

        const content = document.createElement('div');
        content.className = 'cveil-modal-content';

        const heading = document.createElement('h2');
        heading.textContent = config.customText?.preferencesTitle || 'Cookie Preferences';
        content.appendChild(heading);

        (config.purposes || []).forEach(p => {
            const row = document.createElement('div');
            row.className = 'cveil-purpose';

            const label = document.createElement('label');
            label.textContent = p;

            const toggle = document.createElement('button');
            toggle.className = 'cveil-toggle' + (purposeStates[p] ? ' active' : '');
            toggle.dataset.purpose = p;
            toggle.setAttribute('aria-label', 'Toggle ' + p);
            toggle.addEventListener('click', () => {
                toggle.classList.toggle('active');
                purposeStates[toggle.dataset.purpose] = toggle.classList.contains('active');
            });

            row.append(label, toggle);
            content.appendChild(row);
        });

        const actions = document.createElement('div');
        actions.className = 'cveil-modal-actions';

        const cancelBtn = document.createElement('button');
        cancelBtn.className = 'cveil-btn cveil-btn-reject';
        cancelBtn.id = 'cveil-prefs-cancel';
        cancelBtn.textContent = 'Cancel';
        cancelBtn.addEventListener('click', () => modal.remove());

        const saveBtn = document.createElement('button');
        saveBtn.className = 'cveil-btn cveil-btn-accept';
        saveBtn.id = 'cveil-prefs-save';
        saveBtn.textContent = 'Save Preferences';
        saveBtn.addEventListener('click', () => savePreferences(config, purposeStates));

        actions.append(cancelBtn, saveBtn);
        content.appendChild(actions);
        modal.appendChild(content);

        // Trap focus inside modal and support Escape key
        modal.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') modal.remove();
        });

        document.body.appendChild(modal);
    }

    // -- Actions ------------------------------------------------------------

    async function acceptAll(config) {
        const results = await Promise.allSettled(
            (config.purposes || []).map(p => grantConsent(p).then(() => ({ purpose: p, ok: true })))
        );
        const purposes = {};
        let anyFailed = false;
        for (const r of results) {
            if (r.status === 'fulfilled') {
                purposes[r.value.purpose] = true;
            } else {
                anyFailed = true;
            }
        }
        if (Object.keys(purposes).length > 0) saveConsent(purposes);
        if (anyFailed) console.warn('[Cveil] Some consent grants failed — only successful ones saved');
        removeBanner();
        dispatchEvent('cveil:consent:accepted', purposes);
    }

    async function rejectAll(config) {
        const results = await Promise.allSettled(
            (config.purposes || []).map(p => revokeConsent(p).then(() => ({ purpose: p, ok: true })))
        );
        const purposes = {};
        for (const r of results) {
            if (r.status === 'fulfilled') {
                purposes[r.value.purpose] = false;
            }
        }
        if (Object.keys(purposes).length > 0) saveConsent(purposes);
        removeBanner();
        dispatchEvent('cveil:consent:rejected', purposes);
    }

    async function savePreferences(config, purposeStates) {
        const entries = Object.entries(purposeStates);
        const results = await Promise.allSettled(
            entries.map(([purpose, granted]) =>
                (granted ? grantConsent(purpose) : revokeConsent(purpose))
                    .then(() => ({ purpose, granted, ok: true }))
            )
        );
        const saved = {};
        for (const r of results) {
            if (r.status === 'fulfilled') {
                saved[r.value.purpose] = r.value.granted;
            }
        }
        if (Object.keys(saved).length > 0) saveConsent(saved);
        document.getElementById(MODAL_ID)?.remove();
        removeBanner();
        dispatchEvent('cveil:consent:saved', saved);
    }

    function dispatchEvent(name, detail) {
        window.dispatchEvent(new CustomEvent(name, { detail }));
    }

    // -- Public API (attached to window) ------------------------------------

    window.CveilBanner = {
        show: () => init(),
        reset: () => {
            localStorage.removeItem(STORAGE_KEY);
            init();
        },
        getConsent: () => getConsent(),
        hasConsent: (purpose) => getConsent()?.purposes?.[purpose] === true
    };

    // -- Initialization -----------------------------------------------------

    async function init() {
        try {
            const existing = getConsent();
            if (existing && existing.siteId === siteId) {
                // Consent already recorded — don't show banner
                return;
            }
            const config = await fetchConfig();
            renderBanner(config);
        } catch (e) {
            console.warn('[Cveil] Failed to initialize consent banner:', e.message);
        }
    }

    // Auto-initialize when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
