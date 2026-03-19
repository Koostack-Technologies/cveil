import { test, expect } from '../../helpers/authenticated-test';
import { TABS, TABLES, BILLING } from '../../helpers';

test.describe('Billing Extended @billing', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/billing');
    await expect(page.locator(TABS.usage)).toBeVisible({ timeout: 15_000 });
  });

  test('plan tab shows current plan details', async ({ page }) => {
    await page.click(TABS.plan);
    await expect(page.locator('#section-plan')).toBeVisible();

    // Plan name and status should be visible
    await expect(page.locator(BILLING.currentPlanName)).toBeVisible({ timeout: 10_000 });
    await expect(page.locator(BILLING.planStatusBadge)).toBeVisible();

    // Usage limit bars exist (may have 0% width for fresh tenant, so check attached not visible)
    await expect(page.locator(BILLING.limitRequestsBar)).toBeAttached();
    await expect(page.locator(BILLING.limitSubjectsBar)).toBeAttached();

    // Plans grid should show available plans
    await expect(page.locator(BILLING.plansGrid)).toBeVisible();
  });

  test('invoices tab shows table or empty state', async ({ page }) => {
    await page.click(TABS.invoices);
    await expect(page.locator('#section-invoices')).toBeVisible();

    // Either invoice rows or "No invoices yet"
    const table = page.locator(TABLES.invoicesTable);
    await expect(table).toBeVisible({ timeout: 10_000 });
  });

  test('usage time range selector updates data', async ({ page }) => {
    // Usage tab is active by default
    await expect(page.locator('#section-usage')).toBeVisible();

    // Click 7-day range
    await page.click('#range-7');
    await page.waitForTimeout(1500);
    await expect(page.locator(BILLING.usageChart)).toBeVisible();

    // Click 90-day range
    await page.click('#range-90');
    await page.waitForTimeout(1500);
    await expect(page.locator(BILLING.usageChart)).toBeVisible();

    // Click back to 30-day
    await page.click('#range-30');
    await page.waitForTimeout(1500);
    await expect(page.locator(BILLING.usageChart)).toBeVisible();
  });
});
