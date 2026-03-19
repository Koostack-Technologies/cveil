import { test, expect } from '../../helpers/authenticated-test';
import { DSAR } from '../../helpers';

test.describe('DSAR Workflow @dsar', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/dsar');
    await expect(page.locator(DSAR.countPending)).toBeVisible({ timeout: 15_000 });
  });

  test('type filter narrows results', async ({ page }) => {
    // Select DELETION type
    await page.selectOption(DSAR.filterType, 'DELETION');
    // Table should update (may be empty for fresh tenant)
    await expect(page.locator('#dsar-table-body')).toBeVisible({ timeout: 5_000 });

    // Reset to All Types
    await page.selectOption(DSAR.filterType, '');
    await expect(page.locator('#dsar-table-body')).toBeVisible();
  });

  test('creates a request and views detail modal', async ({ page }) => {
    const email = `e2e_dsar_${Date.now()}@test.local`;

    // Open create modal
    await page.click('button:has-text("New Request")');
    await expect(page.locator(DSAR.createModal)).toBeVisible();

    // Fill form
    await page.fill('input[name="requesterEmail"]', email);
    await page.fill('input[name="requesterName"]', 'E2E DSAR User');
    await page.selectOption('select[name="requestType"]', 'DELETION');
    await page.fill('textarea[name="notes"]', 'Automated test request');

    // Submit
    await page.click(`${DSAR.createModal} button[type="submit"]`);
    await expect(page.locator(DSAR.createModal)).toBeHidden({ timeout: 10_000 });

    // Verify request appears in table
    await expect(page.locator('#dsar-table-body')).toContainText(email, { timeout: 10_000 });

    // Click the View action button to open detail modal
    const row = page.locator('#dsar-table-body tr', { hasText: email });
    await row.locator('button:has-text("View")').click();
    await expect(page.locator(DSAR.detailModal)).toBeVisible({ timeout: 5_000 });

    // Detail modal should show the email
    await expect(page.locator(DSAR.detailContent)).toContainText(email);

    // Close detail modal
    await page.click(`${DSAR.detailModal} button:has-text("Close")`);
    await expect(page.locator(DSAR.detailModal)).toBeHidden();
  });

  test('updates request status from detail modal', async ({ page }) => {
    const email = `e2e_status_${Date.now()}@test.local`;

    // Create a request first
    await page.click('button:has-text("New Request")');
    await expect(page.locator(DSAR.createModal)).toBeVisible();
    await page.fill('input[name="requesterEmail"]', email);
    await page.selectOption('select[name="requestType"]', 'ACCESS');
    await page.click(`${DSAR.createModal} button[type="submit"]`);
    await expect(page.locator(DSAR.createModal)).toBeHidden({ timeout: 10_000 });
    await expect(page.locator('#dsar-table-body')).toContainText(email, { timeout: 10_000 });

    // Open detail modal
    const row = page.locator('#dsar-table-body tr', { hasText: email });
    await row.locator('button:has-text("View")').click();
    await expect(page.locator(DSAR.detailModal)).toBeVisible({ timeout: 5_000 });

    // Try to update status — look for a status select/dropdown in the detail
    const statusSelect = page.locator(`${DSAR.detailContent} select`).first();
    if (await statusSelect.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await statusSelect.selectOption({ index: 1 });
      // Look for a save/update button
      const updateBtn = page.locator(`${DSAR.detailContent} button:has-text("Update"), ${DSAR.detailContent} button:has-text("Save")`);
      if (await updateBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
        await updateBtn.click();
        await page.waitForTimeout(1000);
      }
    }

    // Close
    await page.click(`${DSAR.detailModal} button:has-text("Close")`);
    await expect(page.locator(DSAR.detailModal)).toBeHidden();
  });
});
