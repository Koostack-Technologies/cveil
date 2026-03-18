import { type Page } from '@playwright/test';

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:8080';

/**
 * Generate a unique username for auth flow tests so they don't
 * collide with each other or the global shared user.
 */
export function uniqueUser(prefix = 'e2e') {
  const ts = Date.now();
  const rand = Math.random().toString(36).slice(2, 6);
  return {
    username: `${prefix}_${ts}_${rand}`,
    email: `${prefix}_${ts}_${rand}@test.local`,
    fullName: `${prefix} User`,
    password: 'TestPass123!',
  };
}

/**
 * Fill the signup form and submit. Does NOT wait for navigation —
 * the caller should assert the expected outcome.
 */
export async function fillSignupForm(
  page: Page,
  user: { username: string; email: string; fullName: string; password: string },
) {
  await page.fill('#username', user.username);
  await page.fill('#email', user.email);
  await page.fill('#fullName', user.fullName);
  await page.fill('#password', user.password);
  await page.fill('#confirmPassword', user.password);
  await page.click('#signup-btn');
}

/**
 * Fill the login form and submit.
 */
export async function fillLoginForm(page: Page, identifier: string, password: string) {
  await page.fill('#identifier', identifier);
  await page.fill('#password', password);
  await page.click('#login-btn');
}

/**
 * Complete the full onboarding wizard from step 1 through the
 * provisioning completion page.
 */
export async function completeOnboarding(page: Page, workspaceName = 'e2e-workspace') {
  // Step 1 — workspace name
  await page.waitForURL('**/onboarding**');
  await page.fill('#workspaceName', workspaceName);
  await page.click('button[type="submit"]');

  // Step 2 — industry (HEALTHCARE has pre-selected jurisdictions)
  await page.waitForURL('**/onboarding/industry**');
  await page.click('#card-HEALTHCARE');
  await page.click('#continue-btn');

  // Step 3 — compliance (just pick a jurisdiction checkbox + entity and submit)
  await page.waitForURL('**/onboarding/compliance**');
  const firstJurCheckbox = page.locator('input[name="jurisdictions"]').first();
  if (!(await firstJurCheckbox.isChecked())) {
    await firstJurCheckbox.check();
  }
  const firstEntityCheckbox = page.locator('input[name="entities"]').first();
  if (!(await firstEntityCheckbox.isChecked())) {
    await firstEntityCheckbox.check();
  }
  await page.click('button[type="submit"]');

  // Provisioning completion page — wait for redirect to dashboard
  await page.waitForURL('**/dashboard**', { timeout: 60_000 });
}

/**
 * Perform a full signup + auto-login + onboarding flow for a fresh user,
 * returning the user credentials.
 */
export async function signupAndOnboard(page: Page) {
  const user = uniqueUser();
  await page.goto('/signup');
  await fillSignupForm(page, user);

  // Signup JS auto-logs in and redirects to /onboarding
  await page.waitForURL('**/onboarding**', { timeout: 30_000 });
  await completeOnboarding(page);

  return user;
}

/**
 * Set the authenticated session state in the page via sessionStorage.
 * Use this after navigating to any page when you need to inject tokens
 * obtained programmatically (e.g., in tests that don't use storageState).
 */
export async function injectAuthState(
  page: Page,
  tokens: { accessToken: string; refreshToken: string; tokenType?: string; expiresIn?: number; subject?: string },
  user: { username: string; email: string },
) {
  await page.evaluate(
    ({ tokens: t, user: u }) => {
      sessionStorage.setItem('cveil_access_token', t.accessToken);
      sessionStorage.setItem('cveil_refresh_token', t.refreshToken);
      sessionStorage.setItem('cveil_token_type', t.tokenType || 'DPoP');
      sessionStorage.setItem('cveil_expires_at', String(Date.now() + (t.expiresIn || 900) * 1000));

      const sessionPayload = btoa(JSON.stringify({ u: u.username, e: u.email, s: t.subject || '', t: 'DPoP' }));
      document.cookie = `cveil_session=${sessionPayload}; path=/; SameSite=Lax; max-age=${t.expiresIn || 900}`;
    },
    { tokens, user },
  );
}
