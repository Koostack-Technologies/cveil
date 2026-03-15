import * as billing from './billing/index.js';
import * as catalogue from './catalogue/index.js';
import * as consent from './consent/index.js';
import * as governance from './governance/index.js';
import { setAccessToken, initDpopKeys } from './shared/index.js';

// Bootstrap DPoP: restore access token from sessionStorage so that
// createApi() wrappers (which run at module scope in each module) can
// attach DPoP proofs to outbound fetch() calls.
const storedToken = typeof sessionStorage !== 'undefined'
    ? sessionStorage.getItem('cveil_access_token')
    : null;
if (storedToken) {
    setAccessToken(storedToken);
    // Pre-initialise the DPoP key pair (non-blocking)
    initDpopKeys().catch(() => {});
}

export { billing, catalogue, consent, governance };
