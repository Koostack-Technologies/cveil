import { test, expect } from '../../helpers/authenticated-test';

test.describe('Billing Module @billing', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/billing');
    await expect(page.locator('#tab-usage')).toBeVisible({ timeout: 15_000 });
  });

  test('usage tab renders summary cards and chart', async ({ page }) => {
    await page.click('#tab-usage');

    // Verify usage summary cards are visible
    await expect(page.locator('#card-total-requests')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#card-consent-ops')).toBeVisible();
    await expect(page.locator('#card-subject-ops')).toBeVisible();
    await expect(page.locator('#card-dsar-ops')).toBeVisible();
    await expect(page.locator('#card-rate-limits')).toBeVisible();

    // Verify the D3 usage chart container is visible
    await expect(page.locator('#usage-chart')).toBeVisible();
  });

  test('plan tab shows current plan and usage limits', async ({ page }) => {
    await page.click('#tab-plan');

    await expect(page.locator('#section-plan')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#current-plan-name')).toBeVisible();
    await expect(page.locator('#plan-status-badge')).toBeVisible();

    // Usage limit text elements (bars may be zero-width when usage is 0)
    await expect(page.locator('#limit-requests')).toBeVisible();
    await expect(page.locator('#limit-subjects')).toBeVisible();

    // Plans grid
    await expect(page.locator('#plans-grid')).toBeVisible();
  });

  test('invoices tab renders table', async ({ page }) => {
    await page.click('#tab-invoices');

    await expect(page.locator('#section-invoices')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#invoices-table')).toBeVisible();
  });

  test('payment methods tab shows empty state and add button', async ({ page }) => {
    await page.click('#tab-payment');

    await expect(page.locator('#section-payment')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#payment-methods-list')).toBeVisible();

    // "Add Payment Method" button is visible
    const addBtn = page.locator('button:has-text("Add Payment Method")');
    await expect(addBtn).toBeVisible();

    // Clicking it opens the slide-out panel with the form
    await addBtn.click();
    await expect(page.locator('#panel')).toBeVisible({ timeout: 5_000 });
  });
});
