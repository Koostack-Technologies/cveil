import { test, expect } from '@playwright/test';
import { uniqueUser, fillSignupForm, fillLoginForm, completeOnboarding } from '../../helpers';
import { VERIFY } from '../../helpers/constants';
import {
  waitForMessage,
  extractVerificationLink,
  deleteAllMessages,
} from '../../helpers/mailcrab';

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:8080';

test.describe('Email Verification @auth @email', () => {
  test.beforeEach(async () => {
    await deleteAllMessages();
  });

  test('registration sends a verification email', async ({ page }) => {
    const user = uniqueUser('verify');
    await page.goto('/signup');
    await fillSignupForm(page, user);
    await page.waitForURL('**/onboarding**', { timeout: 30_000 });

    // MailCrab should have captured a verification email
    const msg = await waitForMessage(user.email, { timeout: 30_000 });
    expect(msg.subject).toBeTruthy();
    const link = extractVerificationLink(msg);
    expect(link).toContain('/verify-email/callback?key=');
  });

  test('following verification link marks the user as verified', async ({ page }) => {
    // 1. Register and complete onboarding
    const user = uniqueUser('vlink');
    await page.goto('/signup');
    await fillSignupForm(page, user);
    await page.waitForURL('**/onboarding**', { timeout: 30_000 });
    await completeOnboarding(page);

    // 2. Get the verification email and extract the link
    const msg = await waitForMessage(user.email, { timeout: 30_000 });
    const link = extractVerificationLink(msg);

    // 3. Follow the verification link — goes directly to our callback endpoint
    //    which decodes the JWT key and verifies via Keycloak admin API
    await page.goto(link);
    await page.waitForURL('**/verify-email/callback**', { timeout: 15_000 });
    await page.waitForTimeout(2_000);

    // 4. Log out and log back in to get a fresh JWT with email_verified=true
    await page.goto('/login');
    await fillLoginForm(page, user.email, user.password);
    await page.waitForURL('**/dashboard**', { timeout: 30_000 });

    // 5. Poll the /me API — projection is eventually consistent (2-3s lag)
    const accessToken = await page.evaluate(() => sessionStorage.getItem('cveil_access_token'));
    const tokenType = await page.evaluate(() => sessionStorage.getItem('cveil_token_type')) || 'DPoP';
    expect(accessToken).toBeTruthy();
    await expect(async () => {
      const meRes = await page.request.get(`${BASE_URL}/api/v1/platform/iam/me`, {
        headers: { Authorization: `${tokenType} ${accessToken}` },
      });
      expect(meRes.ok()).toBeTruthy();
      const me = await meRes.json();
      expect(me.emailVerified).toBe(true);
    }).toPass({ intervals: [1_000, 1_000, 2_000, 2_000], timeout: 15_000 });
  });

  test('resend verification sends another email', async ({ page }) => {
    // 1. Register and complete onboarding
    const user = uniqueUser('resend');
    await page.goto('/signup');
    await fillSignupForm(page, user);
    await page.waitForURL('**/onboarding**', { timeout: 30_000 });
    await completeOnboarding(page);

    // Consume the first email
    await waitForMessage(user.email, { timeout: 30_000 });
    await deleteAllMessages();

    // 2. Call the resend endpoint
    const accessToken = await page.evaluate(() => sessionStorage.getItem('cveil_access_token'));
    const tokenType = await page.evaluate(() => sessionStorage.getItem('cveil_token_type')) || 'DPoP';
    const resendRes = await page.request.post(
      `${BASE_URL}/api/v1/platform/iam/me/verify-email/resend`,
      { headers: { Authorization: `${tokenType} ${accessToken}` } },
    );
    expect(resendRes.ok()).toBeTruthy();

    // 3. A new verification email should arrive
    const msg = await waitForMessage(user.email, { timeout: 30_000 });
    expect(msg.subject).toBeTruthy();
    const link = extractVerificationLink(msg);
    expect(link).toContain('/verify-email/callback?key=');
  });

  test('unverified user sees verification banner on dashboard', async ({ page }) => {
    const user = uniqueUser('banner');
    await page.goto('/signup');
    await fillSignupForm(page, user);
    await page.waitForURL('**/onboarding**', { timeout: 30_000 });
    await completeOnboarding(page);

    // Dashboard should show the verification banner
    await expect(page.locator(VERIFY.banner)).toBeVisible({ timeout: 10_000 });
    await expect(page.locator(VERIFY.bannerResendBtn)).toBeVisible();
    await expect(page.locator(VERIFY.bannerDismissBtn)).toBeVisible();
  });

  test('verified user does not see verification banner', async ({ page }) => {
    // 1. Register and complete onboarding
    const user = uniqueUser('nobanner');
    await page.goto('/signup');
    await fillSignupForm(page, user);
    await page.waitForURL('**/onboarding**', { timeout: 30_000 });
    await completeOnboarding(page);

    // 2. Verify the email — link goes directly to our callback endpoint
    const msg = await waitForMessage(user.email, { timeout: 30_000 });
    const link = extractVerificationLink(msg);
    await page.goto(link);
    await page.waitForURL('**/verify-email/callback**', { timeout: 15_000 });
    await page.waitForTimeout(2_000);

    // 3. Log back in to get a fresh session with updated emailVerified
    await page.goto('/login');
    await fillLoginForm(page, user.email, user.password);
    await page.waitForURL('**/dashboard**', { timeout: 30_000 });

    // Banner should NOT be visible (login set ev='1' from JWT claim)
    await expect(page.locator(VERIFY.banner)).not.toBeVisible({ timeout: 5_000 });
  });

  test('login response includes emailVerified field', async ({ page }) => {
    const user = uniqueUser('loginev');
    await page.goto('/signup');
    await fillSignupForm(page, user);
    await page.waitForURL('**/onboarding**', { timeout: 30_000 });
    await completeOnboarding(page);

    // Log out and go to login
    await page.goto('/login');

    // Intercept the login API response to capture the body before navigation
    let capturedBody: Record<string, unknown> | null = null;
    await page.route('**/api/v1/platform/iam/login', async (route) => {
      const response = await route.fetch();
      const body = await response.json();
      capturedBody = body;
      await route.fulfill({ response });
    });

    await fillLoginForm(page, user.email, user.password);
    await page.waitForURL('**/dashboard**', { timeout: 30_000 });

    expect(capturedBody).not.toBeNull();
    expect(capturedBody).toHaveProperty('emailVerified');
    expect(typeof capturedBody!.emailVerified).toBe('boolean');
  });
});
