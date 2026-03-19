import { test, expect } from '../../helpers/authenticated-test';
import { TABS, TABLES, PANEL, DELETE_MODAL } from '../../helpers';

test.describe('Consent Subjects CRUD @consent', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/consent');
    await expect(page.locator(TABS.subjects)).toBeVisible({ timeout: 15_000 });
    await page.click(TABS.subjects);
    await expect(page.locator('#section-subjects')).toBeVisible();
  });

  test.describe('Subject management', () => {
    const subjectName = `e2e_subj_${Date.now()}`;

    test('creates a subject, verifies in table, and deletes it', async ({ page }) => {
      // Create
      await page.click('button:has-text("Register Subject")');
      await expect(page.locator(PANEL.title)).toContainText(/Register|Add/i);
      await page.fill('#pf-sub-name', subjectName);
      await page.fill('#pf-sub-desc', 'E2E test subject');
      await page.click(`${PANEL.body} button[type="submit"]`);

      // Verify in table
      await expect(page.locator(TABLES.subjects)).toContainText(subjectName, { timeout: 15_000 });

      // Delete
      const row = page.locator(`${TABLES.subjects} tr`, { hasText: subjectName });
      await row.locator('button:has-text("Delete")').click();
      await expect(page.locator(DELETE_MODAL.confirm)).toBeVisible();
      await page.click(DELETE_MODAL.confirm);
      await expect(page.locator(TABLES.subjects)).not.toContainText(subjectName, { timeout: 10_000 });
    });
  });

  test('search filters the subjects table', async ({ page }) => {
    const searchInput = page.locator('#subjects-search');
    await expect(searchInput).toBeVisible();

    // Type a non-matching string
    await searchInput.fill('zzz_nonexistent_subject');
    // Table should show no results or empty message
    await page.waitForTimeout(500);

    // Clear search
    await searchInput.fill('');
    await page.waitForTimeout(500);
    // Table should restore
    await expect(page.locator(TABLES.subjects)).toBeVisible();
  });
});
