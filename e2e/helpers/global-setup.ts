import * as fs from 'node:fs';
import * as path from 'node:path';
import * as crypto from 'node:crypto';
import { TEST_USER } from './constants';

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:8080';
const HEALTH_URL = `${BASE_URL}/q/health/ready`;
const MAX_WAIT_MS = 120_000;
const POLL_INTERVAL_MS = 3_000;

/**
 * Creates a DPoP proof JWT (ES256) for a given HTTP method and URL.
 */
async function createDpopProof(method: string, url: string): Promise<{ proof: string; publicJwk: JsonWebKey }> {
  const keyPair = crypto.generateKeyPairSync('ec', {
    namedCurve: 'P-256',
  });

  const publicJwk = keyPair.publicKey.export({ format: 'jwk' });

  // Base64url encode helper
  const b64url = (data: string | Buffer) => {
    const buf = typeof data === 'string' ? Buffer.from(data) : data;
    return buf.toString('base64url');
  };

  const header = {
    alg: 'ES256',
    typ: 'dpop+jwt',
    jwk: publicJwk,
  };

  const urlObj = new URL(url);
  const payload = {
    htm: method,
    htu: `${urlObj.origin}${urlObj.pathname}`,
    jti: crypto.randomUUID(),
    iat: Math.floor(Date.now() / 1000),
  };

  const signingInput = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(payload))}`;
  const signature = crypto.sign('SHA256', Buffer.from(signingInput), keyPair.privateKey);

  // Node returns DER-encoded signature; we need raw r||s for ES256
  const rawSig = derToRaw(signature);
  const proof = `${signingInput}.${b64url(rawSig)}`;

  return { proof, publicJwk };
}

/** Convert DER-encoded ECDSA signature to raw r||s (64 bytes for P-256). */
function derToRaw(derSig: Buffer): Buffer {
  // DER: 0x30 <len> 0x02 <rLen> <r> 0x02 <sLen> <s>
  let offset = 2; // skip 0x30 <len>
  offset += 1; // skip 0x02
  const rLen = derSig[offset++];
  const r = derSig.subarray(offset, offset + rLen);
  offset += rLen;
  offset += 1; // skip 0x02
  const sLen = derSig[offset++];
  const s = derSig.subarray(offset, offset + sLen);

  // Pad or trim to 32 bytes each
  const raw = Buffer.alloc(64);
  r.copy(raw, 32 - Math.min(r.length, 32), Math.max(r.length - 32, 0));
  s.copy(raw, 64 - Math.min(s.length, 32), Math.max(s.length - 32, 0));
  return raw;
}

/**
 * Waits for the backend health endpoint to report UP.
 */
async function waitForBackend(): Promise<void> {
  const deadline = Date.now() + MAX_WAIT_MS;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(HEALTH_URL);
      if (res.ok) {
        const body = await res.json();
        if (body.status === 'UP') {
          console.log('✅ Backend is healthy');
          return;
        }
      }
    } catch {
      // not ready yet
    }
    console.log(`⏳ Waiting for backend at ${HEALTH_URL}...`);
    await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
  }
  throw new Error(`Backend did not become healthy within ${MAX_WAIT_MS / 1000}s`);
}

/**
 * Registers the shared test user via the signup endpoint, then logs in
 * via the IAM API and writes a storageState file that authenticated
 * tests can reuse.
 */
async function seedTestUser(): Promise<void> {
  const { username, email, fullName, password, plan } = TEST_USER;

  // 1. Register
  const signupRes = await fetch(`${BASE_URL}/signup`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      Accept: 'application/json',
    },
    body: new URLSearchParams({ username, email, fullName, password, confirmPassword: password, plan }).toString(),
  });
  if (!signupRes.ok) {
    const err = await signupRes.text();
    throw new Error(`Signup failed (${signupRes.status}): ${err}`);
  }
  console.log(`✅ Registered test user: ${username}`);

  // 2. Login via IAM to get tokens (requires DPoP proof)
  const loginUrl = `${BASE_URL}/api/v1/platform/iam/login`;
  const { proof: dpopProof } = await createDpopProof('POST', loginUrl);

  const loginRes = await fetch(loginUrl, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      DPoP: dpopProof,
    },
    body: JSON.stringify({ email, password }),
  });
  if (!loginRes.ok) {
    const err = await loginRes.text();
    throw new Error(`Login failed (${loginRes.status}): ${err}`);
  }
  const tokens = await loginRes.json();
  console.log(`✅ Obtained auth tokens for: ${username}`);

  // 3. Complete onboarding via form POSTs so that dashboard is accessible
  // Step 1: workspace name
  const sessionPayload = btoa(JSON.stringify({ u: username, e: email, s: tokens.subject, t: 'DPoP' }));
  const sessionCookie = `cveil_session=${sessionPayload}`;

  const obStep1 = await fetch(`${BASE_URL}/onboarding`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Cookie: sessionCookie },
    body: new URLSearchParams({ workspaceName: `e2e-workspace` }).toString(),
    redirect: 'manual',
  });
  console.log(`  Onboarding step 1 (workspace): ${obStep1.status}`);

  // Step 2: industry
  const obStep2 = await fetch(`${BASE_URL}/onboarding/industry`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Cookie: sessionCookie },
    body: new URLSearchParams({ industry: 'HEALTHCARE' }).toString(),
    redirect: 'manual',
  });
  console.log(`  Onboarding step 2 (industry): ${obStep2.status}`);

  // Step 3: compliance
  const obStep3 = await fetch(`${BASE_URL}/onboarding/compliance`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Cookie: sessionCookie },
    body: new URLSearchParams([
      ['jurisdictions', 'US_FEDERAL'],
      ['jurisdictions', 'CALIFORNIA'],
      ['entities', 'customer'],
      ['entities', 'employee'],
    ]).toString(),
    redirect: 'manual',
  });
  console.log(`  Onboarding step 3 (compliance): ${obStep3.status}`);

  // Wait a moment for async provisioning to settle
  await new Promise((r) => setTimeout(r, 5_000));
  console.log(`✅ Onboarding completed for: ${username}`);

  // 4. Build the storageState JSON that Playwright can load.
  // The app uses sessionStorage for DPoP tokens and cookies for session context.
  const authDir = path.join(__dirname, '.auth');
  fs.mkdirSync(authDir, { recursive: true });

  const storageState = {
    cookies: [
      {
        name: 'cveil_session',
        value: sessionPayload,
        domain: 'localhost',
        path: '/',
        httpOnly: false,
        secure: false,
        sameSite: 'Lax' as const,
        expires: Math.floor(Date.now() / 1000) + 3600,
      },
    ],
    origins: [
      {
        origin: BASE_URL,
        localStorage: [],
        sessionStorage: [
          { name: 'cveil_access_token', value: tokens.accessToken },
          { name: 'cveil_refresh_token', value: tokens.refreshToken },
          { name: 'cveil_token_type', value: tokens.tokenType || 'DPoP' },
          { name: 'cveil_expires_at', value: String(Date.now() + (tokens.expiresIn || 900) * 1000) },
        ],
      },
    ],
  };

  fs.writeFileSync(path.join(authDir, 'user.json'), JSON.stringify(storageState, null, 2));
  console.log('✅ Auth storageState saved to helpers/.auth/user.json');
}

export default async function globalSetup(): Promise<void> {
  console.log('\n🚀 E2E Global Setup\n');
  await waitForBackend();
  await seedTestUser();
  console.log('\n✅ Global setup complete\n');
}
