import { test, expect } from '@playwright/test';
import { TABS, TABLES, PANEL, DELETE_MODAL } from '../../helpers';

test.describe('Governance Module @governance', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/governance');
    await expect(page.locator(TABS.jurisdictions)).toBeVisible({ timeout: 15_000 });
  });

  test.describe('Jurisdictions CRUD', () => {
    const jurName = `e2e_jur_${Date.now()}`;

    test('creates, verifies, edits, and deletes a jurisdiction', async ({ page }) => {
      // Switch to jurisdictions tab
      await page.click(TABS.jurisdictions);
      await expect(page.locator(TABLES.jurisdictions)).toBeVisible();

      // Open "Add Jurisdiction" panel
      await page.click('button:has-text("Add Jurisdiction")');
      await expect(page.locator(PANEL.title)).toContainText('Add Jurisdiction');

      // Fill the form
      await page.fill('#pf-jur-name', jurName);
      await page.fill('#pf-jur-desc', 'E2E test jurisdiction');

      // Submit the form
      await page.click(`${PANEL.body} button[type="submit"]`);

      // Verify it appears in the table
      await expect(page.locator(TABLES.jurisdictions)).toContainText(jurName, { timeout: 10_000 });

      // Edit — click the Edit button for this row
      const row = page.locator(TABLES.jurisdictions).locator('tr', { hasText: jurName });
      await row.locator('button:has-text("Edit")').click();
      await expect(page.locator(PANEL.title)).toContainText('Edit Jurisdiction');

      // Update description
      await page.fill('#pf-jur-desc', 'Updated by E2E');
      await page.click(`${PANEL.body} button[type="submit"]`);

      // Verify the update persisted (description column updated)
      await expect(row).toContainText('Updated by E2E', { timeout: 10_000 });

      // Delete
      await row.locator('button:has-text("Delete")').click();
      await expect(page.locator(DELETE_MODAL.confirm)).toBeVisible();
      await page.click(DELETE_MODAL.confirm);

      // Verify it's removed from the table
      await expect(page.locator(TABLES.jurisdictions)).not.toContainText(jurName, { timeout: 10_000 });
    });
  });

  test.describe('Regulations CRUD', () => {
    const regName = `e2e_reg_${Date.now()}`;

    test('creates, verifies, and deletes a regulation', async ({ page }) => {
      await page.click(TABS.regulations);
      await expect(page.locator(TABLES.regulations)).toBeVisible();

      // Add
      await page.click('button:has-text("Add Regulation")');
      await expect(page.locator(PANEL.title)).toContainText('Add Regulation');
      await page.fill('#pf-reg-name', regName);
      await page.fill('#pf-reg-desc', 'E2E test regulation');
      await page.click(`${PANEL.body} button[type="submit"]`);

      // Verify
      await expect(page.locator(TABLES.regulations)).toContainText(regName, { timeout: 10_000 });

      // Delete
      const row = page.locator(TABLES.regulations).locator('tr', { hasText: regName });
      await row.locator('button:has-text("Delete")').click();
      await expect(page.locator(DELETE_MODAL.confirm)).toBeVisible();
      await page.click(DELETE_MODAL.confirm);

      await expect(page.locator(TABLES.regulations)).not.toContainText(regName, { timeout: 10_000 });
    });
  });

  test.describe('Purposes CRUD', () => {
    const purName = `e2e_pur_${Date.now()}`;

    test('creates, verifies, and deletes a purpose', async ({ page }) => {
      await page.click(TABS.purposes);
      await expect(page.locator(TABLES.purposes)).toBeVisible();

      // Add
      await page.click('button:has-text("Add Purpose")');
      await expect(page.locator(PANEL.title)).toContainText('Add Purpose');
      await page.fill('#pf-pur-name', purName);
      await page.fill('#pf-pur-desc', 'E2E test purpose');
      await page.click(`${PANEL.body} button[type="submit"]`);

      // Verify
      await expect(page.locator(TABLES.purposes)).toContainText(purName, { timeout: 10_000 });

      // Delete
      const row = page.locator(TABLES.purposes).locator('tr', { hasText: purName });
      await row.locator('button:has-text("Delete")').click();
      await expect(page.locator(DELETE_MODAL.confirm)).toBeVisible();
      await page.click(DELETE_MODAL.confirm);

      await expect(page.locator(TABLES.purposes)).not.toContainText(purName, { timeout: 10_000 });
    });
  });
});
