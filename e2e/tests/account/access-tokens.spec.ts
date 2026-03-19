import { test, expect } from '../../helpers/authenticated-test';
import { NAV, TABS, TABLES, TOKEN } from '../../helpers';

test.describe('Access Tokens @account', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/account');
    await page.click(TABS.tokens);
    await expect(page.locator('#section-tokens')).toBeVisible({ timeout: 15_000 });
  });

  test('displays the access tokens section with empty state', async ({ page }) => {
    await expect(page.locator(TOKEN.createBtn)).toBeVisible();
    await expect(page.locator(TABLES.tokens)).toContainText(
      /(No access tokens|Loading tokens)/,
      { timeout: 15_000 },
    );
  });

  test('opens and closes the create token modal', async ({ page }) => {
    await page.click(TOKEN.createBtn);
    await expect(page.locator(TOKEN.createModal)).toBeVisible();
    await expect(page.locator(TOKEN.nameInput)).toBeVisible();
    await expect(page.locator(TOKEN.scopeDisplay)).toBeVisible();
    await expect(page.locator(TOKEN.submitBtn)).toBeVisible();

    // Cancel should hide the modal
    await page.click(`${TOKEN.createModal} button:has-text("Cancel")`);
    await expect(page.locator(TOKEN.createModal)).toBeHidden();
  });

  test('requires a token name to submit', async ({ page }) => {
    await page.click(TOKEN.createBtn);
    await expect(page.locator(TOKEN.createModal)).toBeVisible();

    // Attempt to submit with an empty name — HTML required attribute prevents submission
    await page.click(TOKEN.submitBtn);

    // Modal should still be open (form not submitted)
    await expect(page.locator(TOKEN.createModal)).toBeVisible();
  });

  test.describe('Token CRUD', () => {
    const tokenName = `e2e_pat_${Date.now()}`;

    test('creates a personal access token, verifies it appears in the list, and revokes it', async ({ page }) => {
      // --- Create ---
      await page.click(TOKEN.createBtn);
      await expect(page.locator(TOKEN.createModal)).toBeVisible();

      await page.fill(TOKEN.nameInput, tokenName);

      // Select 7-day expiry
      await page.click(`${TOKEN.expiryOptions} button:has-text("7 days")`);

      await page.click(TOKEN.submitBtn);

      // --- Show-once modal ---
      await expect(page.locator(TOKEN.resultModal)).toBeVisible({ timeout: 15_000 });
      await expect(page.locator(TOKEN.resultName)).toContainText(tokenName);

      // Token value should be a non-empty string
      const tokenValue = await page.locator(TOKEN.resultValue).textContent();
      expect(tokenValue?.trim().length).toBeGreaterThan(0);

      // Close the show-once modal
      await page.click(TOKEN.resultCloseBtn);
      await expect(page.locator(TOKEN.resultModal)).toBeHidden();

      // --- Verify in list ---
      await expect(page.locator(TABLES.tokens)).toContainText(tokenName, { timeout: 10_000 });

      const row = page.locator(`${TABLES.tokens} tr`, { hasText: tokenName });
      await expect(row).toBeVisible();
      await expect(row).toContainText('Active');

      // --- Revoke ---
      page.on('dialog', (dialog) => dialog.accept());
      await row.locator('button:has-text("Revoke")').click();

      // After revocation the row should show "Revoked" status badge
      await expect(row).toContainText('Revoked', { timeout: 10_000 });
      // The revoke button should be gone (only the status badge remains)
      await expect(row.locator('button:has-text("Revoke")')).toBeHidden();
    });
  });

  test('selects different expiry options', async ({ page }) => {
    await page.click(TOKEN.createBtn);
    await expect(page.locator(TOKEN.createModal)).toBeVisible();

    // 30 days is selected by default — verify it has the active style
    const btn30 = page.locator(`${TOKEN.expiryOptions} button:has-text("30 days")`);
    await expect(btn30).toHaveClass(/bg-primary-dim/);

    // Click 90 days
    const btn90 = page.locator(`${TOKEN.expiryOptions} button:has-text("90 days")`);
    await btn90.click();
    await expect(btn90).toHaveClass(/bg-primary-dim/);
    // 30 days should no longer be active
    await expect(btn30).not.toHaveClass(/bg-primary-dim/);

    await page.click(`${TOKEN.createModal} button:has-text("Cancel")`);
  });
});
