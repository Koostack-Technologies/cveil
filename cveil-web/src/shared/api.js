import { getDpopProof } from './dpop.js';

let _accessToken = null;

/**
 * Store / retrieve the in-memory access token.
 * Kept in a module-scoped variable so HttpOnly cookies are no longer needed
 * for JS-initiated requests — DPoP proof-of-possession replaces cookie-based
 * XSS protection.
 */
export function setAccessToken(token) { _accessToken = token; }
export function getAccessToken() { return _accessToken; }
export function clearAccessToken() { _accessToken = null; }

export function createApi(basePath) {
    return async function api(path, opts = {}) {
        const headers = { ...opts.headers };
        if (opts.body) headers['Content-Type'] = 'application/json';

        const fullUrl = new URL(basePath + path, window.location.origin).href;
        const method = (opts.method || 'GET').toUpperCase();

        // Attach DPoP proof and Authorization header when a token is available
        if (_accessToken) {
            headers['Authorization'] = 'DPoP ' + _accessToken;
            try {
                headers['DPoP'] = await getDpopProof(method, fullUrl, _accessToken);
            } catch (e) {
                console.warn('DPoP proof generation failed, falling back to Bearer', e);
                headers['Authorization'] = 'Bearer ' + _accessToken;
            }
        }

        let res;
        try {
            res = await fetch(basePath + path, { ...opts, headers });
        } catch (_) {
            throw new Error('Network error — please check your connection and try again.');
        }
        if (!res.ok) {
            const err = await res.json().catch(() => ({ message: res.statusText }));
            throw new Error(err.message || res.statusText);
        }
        if (res.status === 204) return null;
        return res.json();
    };
}
