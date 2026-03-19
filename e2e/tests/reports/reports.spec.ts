import { test, expect } from '../../helpers/authenticated-test';

test.describe('Reports Module @reports', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/reports');
    // Wait for page to load (heading is always rendered server-side)
    await expect(page.locator('h1')).toContainText('Compliance Reports', { timeout: 15_000 });
  });

  test('consent chart sections render', async ({ page }) => {
    // Consent summary and activity sections should be present
    // They may show SVG charts or error text depending on permissions
    await expect(page.locator('#consent-summary-chart')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#consent-activity-chart')).toBeVisible({ timeout: 10_000 });

    // Period selector for activity chart is present
    await expect(page.locator('#activity-range')).toBeVisible();
  });

  test('governance snapshot section renders cards', async ({ page }) => {
    // Governance snapshot section with 5 metric labels
    await expect(page.locator('#governance-snapshot')).toBeVisible({ timeout: 10_000 });

    const labels = ['Jurisdictions', 'Regulations', 'Purposes', 'Entities', 'Subjects'];
    for (const label of labels) {
      await expect(page.locator('#governance-snapshot').locator(`text=${label}`)).toBeVisible();
    }
  });

  test('audit log section renders', async ({ page }) => {
    // Audit log table body is present (may be empty)
    await expect(page.locator('#audit-log-body')).toBeVisible({ timeout: 10_000 });

    // Subject filter input exists
    await expect(page.locator('#audit-subject')).toBeVisible();

    // Pagination buttons are present
    await expect(page.locator('#audit-prev')).toBeVisible();
    await expect(page.locator('#audit-next')).toBeVisible();
  });
});
