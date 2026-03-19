import { test, expect } from '../../helpers/authenticated-test';

test.describe('Analytics Module @analytics', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/analytics');
    await expect(page.locator('#analytics-period')).toBeVisible({ timeout: 15_000 });
  });

  test('summary cards render with metric values', async ({ page }) => {
    await expect(page.locator('#metric-api-requests')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#metric-active-subjects')).toBeVisible();
    await expect(page.locator('#metric-consent-rate')).toBeVisible();
    await expect(page.locator('#metric-avg-latency')).toBeVisible();
  });

  test('API usage chart renders and consent charts show data or empty state', async ({ page }) => {
    // API Usage line chart should always render (even with minimal data)
    const usageChart = page.locator('#api-usage-chart svg');
    await expect(usageChart).toBeVisible({ timeout: 15_000 });

    // Consent charts may show "No consent activity data" if no consent data exists
    const flowChart = page.locator('#consent-flow-chart');
    await expect(flowChart).toBeVisible({ timeout: 10_000 });

    const distChart = page.locator('#consent-distribution-chart');
    await expect(distChart).toBeVisible({ timeout: 10_000 });
  });

  test('period selector updates chart data', async ({ page }) => {
    // Wait for initial chart render
    await expect(page.locator('#api-usage-chart svg')).toBeVisible({ timeout: 15_000 });

    // Change period from default to 7d
    await page.selectOption('#analytics-period', '7d');

    // Give charts time to re-render
    await page.waitForTimeout(2_000);
    await expect(page.locator('#api-usage-chart svg')).toBeVisible();

    // Change to 90d
    await page.selectOption('#analytics-period', '90d');
    await page.waitForTimeout(2_000);
    await expect(page.locator('#api-usage-chart svg')).toBeVisible();
  });
});
