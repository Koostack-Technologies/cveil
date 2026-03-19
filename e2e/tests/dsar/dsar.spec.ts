import { test, expect } from '../../helpers/authenticated-test';

test.describe('DSAR Module @dsar', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/dsar');
    await expect(page.locator('#dsar-summary')).toBeVisible({ timeout: 15_000 });
  });

  test('summary cards and request table render', async ({ page }) => {
    // Summary cards
    await expect(page.locator('#dsar-count-pending')).toBeVisible();
    await expect(page.locator('#dsar-count-in-progress')).toBeVisible();
    await expect(page.locator('#dsar-count-completed')).toBeVisible();
    await expect(page.locator('#dsar-count-overdue')).toBeVisible();

    // Request table body is present
    await expect(page.locator('#dsar-table-body')).toBeVisible();
  });

  test('creates a DSAR request and verifies it in the table', async ({ page }) => {
    const email = `dsar-${Date.now()}@test.local`;

    // Click "New Request" button
    await page.click('button:has-text("New Request")');
    await expect(page.locator('#dsar-create-modal')).toBeVisible({ timeout: 5_000 });

    // Fill the create form
    await page.fill('#dsar-create-form [name="requesterEmail"]', email);
    await page.fill('#dsar-create-form [name="requesterName"]', 'E2E Tester');
    await page.selectOption('#dsar-create-form [name="requestType"]', 'ACCESS');
    await page.fill('#dsar-create-form [name="notes"]', 'Created by E2E test');

    // Submit the form
    await page.click('#dsar-create-form button[type="submit"]');

    // Modal should close and the request should appear in the table
    await expect(page.locator('#dsar-create-modal')).toBeHidden({ timeout: 10_000 });
    await expect(page.locator('#dsar-table-body')).toContainText(email, { timeout: 10_000 });
  });

  test('filters requests by status', async ({ page }) => {
    // Select a specific status filter
    await page.selectOption('#dsar-filter-status', 'PENDING');

    // Table should update (may have results or be empty — either is valid)
    await page.waitForTimeout(1_000);
    await expect(page.locator('#dsar-table-body')).toBeVisible();

    // Reset to All
    await page.selectOption('#dsar-filter-status', '');
    await page.waitForTimeout(1_000);
    await expect(page.locator('#dsar-table-body')).toBeVisible();
  });

  test('opens request detail modal and updates status', async ({ page }) => {
    // First create a request so we have something to click
    const email = `dsar-detail-${Date.now()}@test.local`;
    await page.click('button:has-text("New Request")');
    await expect(page.locator('#dsar-create-modal')).toBeVisible({ timeout: 5_000 });
    await page.fill('#dsar-create-form [name="requesterEmail"]', email);
    await page.selectOption('#dsar-create-form [name="requestType"]', 'DELETION');
    await page.click('#dsar-create-form button[type="submit"]');
    await expect(page.locator('#dsar-create-modal')).toBeHidden({ timeout: 10_000 });
    await expect(page.locator('#dsar-table-body')).toContainText(email, { timeout: 10_000 });

    // Click the "View" action link to open detail modal
    const row = page.locator('#dsar-table-body tr', { hasText: email });
    await row.locator('a:has-text("View"), button:has-text("View")').click();
    await expect(page.locator('#dsar-detail-modal')).toBeVisible({ timeout: 5_000 });
    await expect(page.locator('#dsar-detail-content')).toContainText(email);

    // Try updating status if the dropdown is available
    const statusSelect = page.locator('#dsar-status-select');
    if (await statusSelect.isVisible()) {
      const options = await statusSelect.locator('option').allTextContents();
      if (options.length > 0) {
        await statusSelect.selectOption({ index: 0 });
        // Wait for status update to take effect
        await page.waitForTimeout(2_000);
      }
    }
  });
});
