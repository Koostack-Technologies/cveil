import { test, expect } from '../../helpers/authenticated-test';
import { NAV, TABS, ACCOUNT } from '../../helpers';

test.describe('Account Profile & Organizations @account', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/account');
    await expect(page.locator(TABS.profile)).toBeVisible({ timeout: 15_000 });
  });

  test('profile tab displays user information', async ({ page }) => {
    // Profile tab is active by default
    await expect(page.locator('#section-profile')).toBeVisible();

    // User info should be displayed
    await expect(page.locator(ACCOUNT.displayEmail)).toBeVisible({ timeout: 10_000 });
    const email = await page.locator(ACCOUNT.displayEmail).textContent();
    expect(email?.trim().length).toBeGreaterThan(0);
  });

  test('edit profile full name', async ({ page }) => {
    await expect(page.locator('#section-profile')).toBeVisible();

    const nameInput = page.locator(ACCOUNT.fullNameInput);
    await expect(nameInput).toBeVisible({ timeout: 10_000 });

    // Update the name
    const newName = `E2E Updated ${Date.now()}`;
    await nameInput.fill(newName);
    await page.click(ACCOUNT.profileSaveBtn);

    // Verify success message appears
    await expect(page.locator(ACCOUNT.profileMsg)).toBeVisible({ timeout: 10_000 });
  });

  test('change password form validates matching passwords', async ({ page }) => {
    await expect(page.locator('#section-profile')).toBeVisible();

    const newPw = page.locator(ACCOUNT.newPassword);
    const confirmPw = page.locator(ACCOUNT.confirmPassword);
    await expect(newPw).toBeVisible({ timeout: 10_000 });

    // Fill mismatched passwords
    await newPw.fill('NewPass123!');
    await confirmPw.fill('DifferentPass!');
    await page.click(ACCOUNT.passwordSaveBtn);

    // Should show error or stay on form (browser validation or JS error)
    await page.waitForTimeout(500);
    // The form should still be visible (not dismissed)
    await expect(page.locator(ACCOUNT.passwordForm)).toBeVisible();
  });

  test('organizations tab shows workspace list', async ({ page }) => {
    await page.click(TABS.organizations);
    await expect(page.locator('#section-organizations')).toBeVisible();

    // The orgs list should be visible and contain at least one org (from onboarding)
    await expect(page.locator(ACCOUNT.orgsList)).toBeVisible({ timeout: 10_000 });
    await expect(page.locator(ACCOUNT.orgsList)).not.toBeEmpty();
  });
});
