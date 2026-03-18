import { test, expect } from '@playwright/test';
import { uniqueUser, fillSignupForm } from '../../helpers';

test.describe('Interrupted Onboarding @auth', () => {
  test('resumes at step 1 with warning after completing only step 1 then navigating away', async ({ page }) => {
    const user = uniqueUser('intr1');

    // Signup → auto-login → onboarding step 1
    await page.goto('/signup');
    await fillSignupForm(page, user);
    await page.waitForURL('**/onboarding**', { timeout: 30_000 });

    // Complete step 1 only
    await page.fill('#workspaceName', 'interrupted-ws');
    await page.click('button[type="submit"]');
    await page.waitForURL('**/onboarding/industry**');

    // "Interrupt" — navigate away to dashboard
    await page.goto('/dashboard');

    // Server redirects incomplete onboarding back to /onboarding (step 1)
    await page.waitForURL('**/onboarding**', { timeout: 15_000 });
    // Should show a warning about incomplete setup
    await expect(page.locator('text=complete onboarding')).toBeVisible({ timeout: 5_000 });
  });

  test('resumes at step 1 with warning after completing steps 1 and 2 then navigating away', async ({ page }) => {
    const user = uniqueUser('intr2');

    // Signup → auto-login → onboarding step 1
    await page.goto('/signup');
    await fillSignupForm(page, user);
    await page.waitForURL('**/onboarding**', { timeout: 30_000 });

    // Complete step 1
    await page.fill('#workspaceName', 'interrupted-ws2');
    await page.click('button[type="submit"]');

    // Complete step 2
    await page.waitForURL('**/onboarding/industry**');
    await page.click('#card-HEALTHCARE');
    await page.click('#continue-btn');
    await page.waitForURL('**/onboarding/compliance**');

    // "Interrupt" — navigate away to dashboard
    await page.goto('/dashboard');

    // Server redirects incomplete onboarding back to /onboarding (step 1)
    await page.waitForURL('**/onboarding**', { timeout: 15_000 });
    await expect(page.locator('text=complete onboarding')).toBeVisible({ timeout: 5_000 });
  });
});
