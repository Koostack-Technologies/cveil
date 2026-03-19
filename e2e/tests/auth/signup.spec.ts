import { test, expect } from '@playwright/test';
import { uniqueUser, fillSignupForm } from '../../helpers';

test.describe('Signup @auth', () => {
  test('registers a new user and reaches the onboarding page', async ({ page }) => {
    const user = uniqueUser('signup');
    await page.goto('/signup');

    // Verify the signup page rendered
    await expect(page.locator('h1')).toContainText('Create your account');

    // Fill and submit the form
    await fillSignupForm(page, user);

    // Signup JS auto-logs-in and redirects to /onboarding
    await page.waitForURL('**/onboarding**', { timeout: 30_000 });
    await expect(page.locator('#workspaceName')).toBeVisible();
  });

  test('shows validation error for mismatched passwords', async ({ page }) => {
    await page.goto('/signup');

    await page.fill('#username', 'badpassuser');
    await page.fill('#email', 'badpass@test.local');
    await page.fill('#password', 'TestPass123!');
    await page.fill('#confirmPassword', 'WrongPass999!');
    await page.click('#signup-btn');

    // Browser native validation or JS error should prevent navigation
    // The user stays on the signup page
    await expect(page).toHaveURL(/\/signup/);
  });

  test('shows error for duplicate username', async ({ page }) => {
    const user = uniqueUser('dup');

    // Register first time
    await page.goto('/signup');
    await fillSignupForm(page, user);
    await page.waitForURL('**/onboarding**', { timeout: 30_000 });

    // Try to register again with same username
    await page.goto('/signup');
    await fillSignupForm(page, user);

    // Should show an error on the signup page
    await expect(page.locator('#signup-error')).toBeVisible({ timeout: 10_000 });
  });
});
