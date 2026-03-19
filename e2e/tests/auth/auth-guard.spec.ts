import { test, expect } from '@playwright/test';

test.describe('Auth Guard @auth', () => {
  test('redirects unauthenticated user from /dashboard to /login', async ({ page }) => {
    // Clear any cookies that might exist
    await page.context().clearCookies();

    await page.goto('/dashboard');

    // Should redirect to /login
    await page.waitForURL('**/login**', { timeout: 15_000 });
    await expect(page.locator('h1')).toContainText('Welcome back');
  });

  test('redirects unauthenticated user from /dashboard/consent to /login', async ({ page }) => {
    await page.context().clearCookies();

    await page.goto('/dashboard/consent');

    await page.waitForURL('**/login**', { timeout: 15_000 });
  });

  test('redirects unauthenticated user from /dashboard/governance to /login', async ({ page }) => {
    await page.context().clearCookies();

    await page.goto('/dashboard/governance');

    await page.waitForURL('**/login**', { timeout: 15_000 });
  });
});
