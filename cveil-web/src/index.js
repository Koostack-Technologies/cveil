import './styles.css';
import * as billing from './billing/index.js';
import * as catalogue from './catalogue/index.js';
import * as consent from './consent/index.js';
import * as governance from './governance/index.js';
import * as reports from './reports/index.js';
import * as analytics from './analytics/index.js';
import * as dsar from './dsar/index.js';
import { setAccessToken, initDpopKeys, authFetch } from './shared/index.js';

// Bootstrap DPoP: restore access token from sessionStorage so that
// createApi() wrappers (which run at module scope in each module) can
// attach DPoP proofs to outbound fetch() calls.
const storedToken = typeof sessionStorage !== 'undefined'
    ? sessionStorage.getItem('cveil_access_token')
    : null;

function isTokenExpired(token) {
    try {
        const parts = token.split('.');
        if (parts.length !== 3) return true;
        const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
        return !payload.exp || payload.exp * 1000 < Date.now();
    } catch { return true; }
}

if (storedToken && !isTokenExpired(storedToken)) {
    setAccessToken(storedToken);
    // Pre-initialise the DPoP key pair (non-blocking)
    initDpopKeys().catch(() => {});
} else if (storedToken) {
    // Stale/expired token — clear so cookie-based auth takes over
    sessionStorage.removeItem('cveil_access_token');
    sessionStorage.removeItem('cveil_refresh_token');
    sessionStorage.removeItem('cveil_token_type');
    sessionStorage.removeItem('cveil_expires_at');
    sessionStorage.removeItem('cveil_dpop_jwk');
}

export { billing, catalogue, consent, governance, reports, analytics, dsar, authFetch };
