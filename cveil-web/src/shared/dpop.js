import { generateKeyPair, exportJWK, importJWK, SignJWT, calculateJwkThumbprint } from 'jose';

const STORAGE_KEY = 'cveil_dpop_jwk';
const ALG = 'ES256';

let _keyPair = null;

/**
 * Initialise (or restore) the ephemeral DPoP key pair.
 *
 * The *public* JWK is persisted in sessionStorage so the same key survives
 * soft navigations within the same tab.  The CryptoKey private-key reference
 * lives only in module scope — if the page hard-reloads a new pair is
 * generated (the old DPoP-bound tokens will simply expire normally).
 */
export async function initDpopKeys() {
    if (_keyPair) return _keyPair;

    const stored = sessionStorage.getItem(STORAGE_KEY);
    if (stored) {
        try {
            const jwk = JSON.parse(stored);
            // Re-import both halves from the full JWK (which includes `d`)
            const privateKey = await importJWK(jwk, ALG);
            // Build a public-only JWK for embedding in proofs
            const { d: _, ...publicJwk } = jwk;
            const publicKey = await importJWK(publicJwk, ALG);
            _keyPair = { privateKey, publicKey, publicJwk };
            return _keyPair;
        } catch {
            sessionStorage.removeItem(STORAGE_KEY);
        }
    }

    // Generate a fresh ES256 key pair (extractable so we can serialise it)
    const { privateKey, publicKey } = await generateKeyPair(ALG, { extractable: true });
    const fullJwk = await exportJWK(privateKey);   // includes `d`
    const { d: _, ...publicJwk } = fullJwk;

    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(fullJwk));
    _keyPair = { privateKey, publicKey, publicJwk };
    return _keyPair;
}

/**
 * Return the public key JWK (for informational / debugging purposes).
 */
export async function getPublicKeyJwk() {
    const kp = await initDpopKeys();
    return kp.publicJwk;
}

/**
 * Create a DPoP proof JWT per RFC 9449.
 *
 * @param {string} method  HTTP method (GET, POST, …)
 * @param {string} url     Full request URL
 * @param {string} [accessToken]  If present, `ath` (access-token hash) is included
 * @returns {Promise<string>}  Compact-serialised signed JWT
 */
export async function getDpopProof(method, url, accessToken) {
    const kp = await initDpopKeys();

    // htu = scheme + authority + path  (no query / fragment per RFC 9449 §4.2)
    const parsed = new URL(url);
    const htu = parsed.origin + parsed.pathname;

    const claims = { htm: method.toUpperCase(), htu, jti: _uuid() };

    if (accessToken) {
        claims.ath = await _sha256base64url(accessToken);
    }

    return new SignJWT(claims)
        .setProtectedHeader({ alg: ALG, typ: 'dpop+jwt', jwk: kp.publicJwk })
        .setIssuedAt()
        .sign(kp.privateKey);
}

/**
 * Clear the stored key pair (e.g. on logout).
 */
export function clearDpopKeys() {
    _keyPair = null;
    sessionStorage.removeItem(STORAGE_KEY);
}

// -- helpers ----------------------------------------------------------------

function _uuid() {
    return crypto.randomUUID();
}

async function _sha256base64url(input) {
    const data = new TextEncoder().encode(input);
    const hash = await crypto.subtle.digest('SHA-256', data);
    // Base64url-encode (no padding)
    return btoa(String.fromCharCode(...new Uint8Array(hash)))
        .replace(/\+/g, '-')
        .replace(/\//g, '_')
        .replace(/=+$/, '');
}
