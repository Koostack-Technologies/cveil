import * as fs from 'node:fs';
import * as path from 'node:path';
import { chromium } from '@playwright/test';
import { TEST_USER } from './constants';

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:8080';
const HEALTH_URL = `${BASE_URL}/q/health/ready`;
const MAX_WAIT_MS = 120_000;
const POLL_INTERVAL_MS = 3_000;

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
 * Uses a real browser to signup, complete onboarding, then saves the
 * storageState (cookies) + sessionStorage so authenticated tests can reuse it.
 */
async function seedTestUser(): Promise<void> {
  const { username, email, fullName, password, plan } = TEST_USER;
  const authDir = path.join(__dirname, '.auth');
  fs.mkdirSync(authDir, { recursive: true });

  const browser = await chromium.launch();
  const context = await browser.newContext({ baseURL: BASE_URL });
  const page = await context.newPage();

  // 1. Signup
  await page.goto('/signup');
  await page.fill('#username', username);
  await page.fill('#email', email);
  await page.fill('#fullName', fullName);
  await page.fill('#password', password);
  await page.fill('#confirmPassword', password);
  await page.click('#signup-btn');
  console.log(`✅ Registered test user: ${username}`);

  // Signup JS auto-logs-in and redirects to /onboarding
  await page.waitForURL('**/onboarding**', { timeout: 30_000 });

  // 2. Complete onboarding step 1 — organization name
  await page.fill('#workspaceName', `e2e-ws-${Date.now()}`);
  await page.click('button[type="submit"]');

  // Step 2 — industry
  await page.waitForURL('**/onboarding/industry**', { timeout: 15_000 });
  await page.click('#card-HEALTHCARE');
  await page.click('#continue-btn');

  // Step 3 — compliance (HEALTHCARE preset pre-selects some checkboxes)
  await page.waitForURL('**/onboarding/compliance**', { timeout: 15_000 });
  const firstJur = page.locator('input[name="jurisdictions"]').first();
  if (!(await firstJur.isChecked())) await firstJur.check();
  const firstEnt = page.locator('input[name="entities"]').first();
  if (!(await firstEnt.isChecked())) await firstEnt.check();
  await page.click('button[type="submit"]');

  // Wait for provisioning to complete and redirect to dashboard
  await page.waitForURL('**/dashboard**', { timeout: 60_000 });
  console.log('✅ Onboarding completed');

  // 3. Save storageState (cookies + localStorage)
  await context.storageState({ path: path.join(authDir, 'user.json') });

  // 4. Also capture sessionStorage (Playwright doesn't include it in storageState)
  const sessionData = await page.evaluate(() => {
    const data: Record<string, string> = {};
    for (let i = 0; i < sessionStorage.length; i++) {
      const key = sessionStorage.key(i)!;
      data[key] = sessionStorage.getItem(key)!;
    }
    return data;
  });
  fs.writeFileSync(path.join(authDir, 'session-storage.json'), JSON.stringify(sessionData, null, 2));
  console.log('✅ Auth storageState + sessionStorage saved');

  await browser.close();
}

export default async function globalSetup(): Promise<void> {
  console.log('\n🚀 E2E Global Setup\n');
  await waitForBackend();
  await seedTestUser();
  console.log('\n✅ Global setup complete\n');
}
