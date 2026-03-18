import { test, expect } from '@playwright/test';
import { uniqueUser, fillSignupForm, completeOnboarding } from '../../helpers';

test.describe('Onboarding @auth', () => {
  test('completes full onboarding wizard after signup', async ({ page }) => {
    const user = uniqueUser('onboard');

    // Signup (auto-logs-in and redirects to /onboarding)
    await page.goto('/signup');
    await fillSignupForm(page, user);
    await page.waitForURL('**/onboarding**', { timeout: 30_000 });

    // Step 1 — Workspace name
    await expect(page.locator('#workspaceName')).toBeVisible();
    await page.fill('#workspaceName', `onboard-ws-${Date.now()}`);
    await page.click('button[type="submit"]');

    // Step 2 — Industry
    await page.waitForURL('**/onboarding/industry**');
    await expect(page.locator('#industry-form')).toBeVisible();
    await page.click('#card-HEALTHCARE');
    await expect(page.locator('#continue-btn')).toBeEnabled();
    await page.click('#continue-btn');

    // Step 3 — Compliance
    await page.waitForURL('**/onboarding/compliance**');
    await expect(page.locator('#compliance-form')).toBeVisible();
    const firstJur = page.locator('input[name="jurisdictions"]').first();
    if (!(await firstJur.isChecked())) {
      await firstJur.check();
    }
    const firstEntity = page.locator('input[name="entities"]').first();
    if (!(await firstEntity.isChecked())) {
      await firstEntity.check();
    }
    await page.click('button[type="submit"]');

    // Provisioning + redirect to dashboard
    await page.waitForURL('**/dashboard**', { timeout: 60_000 });
    await expect(page.locator('h1')).toContainText('Welcome back');
  });
});
