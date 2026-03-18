import { test, expect } from '@playwright/test';
import { uniqueUser, fillSignupForm, completeOnboarding, fillLoginForm } from '../../helpers';

test.describe('Login @auth', () => {
  test('logs in with valid credentials and reaches dashboard', async ({ page }) => {
    // Create a user with completed onboarding first
    const user = uniqueUser('login');
    await page.goto('/signup');
    await fillSignupForm(page, user);
    await page.waitForURL('**/onboarding**', { timeout: 30_000 });
    await completeOnboarding(page);

    // Now logout so we can test login
    await page.click('#logout-btn');
    await page.waitForURL('**/login**', { timeout: 15_000 });

    // Login with the same credentials
    await fillLoginForm(page, user.email, user.password);
    await page.waitForURL('**/dashboard**', { timeout: 30_000 });
    await expect(page.locator('h1')).toContainText('Welcome back');
  });

  test('shows error for wrong password', async ({ page }) => {
    await page.goto('/login');

    await fillLoginForm(page, 'someone@test.local', 'WrongPassword999!');

    await expect(page.locator('#login-error')).toBeVisible({ timeout: 10_000 });
    await expect(page).toHaveURL(/\/login/);
  });

  test('shows error for non-existent user', async ({ page }) => {
    await page.goto('/login');

    await fillLoginForm(page, 'nobody@nonexistent.local', 'SomePassword1!');

    await expect(page.locator('#login-error')).toBeVisible({ timeout: 10_000 });
    await expect(page).toHaveURL(/\/login/);
  });
});
