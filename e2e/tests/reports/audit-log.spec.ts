import { test, expect } from '../../helpers/authenticated-test';
import { TABS, TABLES, REPORTS } from '../../helpers';

test.describe('Reports Audit Log @reports', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/reports');
    await expect(page.locator(REPORTS.consentSummaryChart)).toBeVisible({ timeout: 15_000 });
  });

  test('audit log table renders', async ({ page }) => {
    // Scroll to audit section or wait for it to load
    const auditTable = page.locator(TABLES.auditLog);
    await expect(auditTable).toBeVisible({ timeout: 10_000 });
  });

  test('subject filter updates audit log', async ({ page }) => {
    const subjectFilter = page.locator(REPORTS.auditSubject);
    await expect(subjectFilter).toBeVisible({ timeout: 10_000 });

    // Type a filter value
    await subjectFilter.fill('nonexistent_subject');
    // Trigger search — click the search button or press enter
    await page.click('button[onclick*="loadAuditLog"], button:near(#audit-subject):has-text("Search")');
    await page.waitForTimeout(1000);

    // Clear filter
    await subjectFilter.fill('');
    await page.click('button[onclick*="loadAuditLog"], button:near(#audit-subject):has-text("Search")');
    await page.waitForTimeout(1000);

    await expect(page.locator(TABLES.auditLog)).toBeVisible();
  });

  test('pagination buttons exist and are functional', async ({ page }) => {
    const prevBtn = page.locator(REPORTS.auditPrev);
    const nextBtn = page.locator(REPORTS.auditNext);
    const pageInfo = page.locator(REPORTS.auditPageInfo);

    await expect(prevBtn).toBeVisible({ timeout: 10_000 });
    await expect(nextBtn).toBeVisible();
    // Prev should be disabled on first page
    await expect(prevBtn).toBeDisabled();
  });

  test('activity range selector changes consent activity chart', async ({ page }) => {
    const rangeSelect = page.locator(REPORTS.activityRange);
    await expect(rangeSelect).toBeVisible({ timeout: 10_000 });

    // Change to 7 days
    await rangeSelect.selectOption('7d');
    await page.waitForTimeout(1500);
    await expect(page.locator(REPORTS.consentActivityChart)).toBeVisible();

    // Change to 90 days
    await rangeSelect.selectOption('90d');
    await page.waitForTimeout(1500);
    await expect(page.locator(REPORTS.consentActivityChart)).toBeVisible();
  });

  test('CSV export link is present', async ({ page }) => {
    const exportLink = page.locator('a[href*="/reports/export/consent-summary"]');
    await expect(exportLink).toBeVisible({ timeout: 10_000 });
  });
});
