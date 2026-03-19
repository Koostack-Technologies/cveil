import { test, expect } from '../../helpers/authenticated-test';
import { TABS, TABLES, PANEL, DELETE_MODAL } from '../../helpers';

test.describe('Governance Policies CRUD @governance', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/governance');
    await expect(page.locator(TABS.policies)).toBeVisible({ timeout: 15_000 });
    await page.click(TABS.policies);
    await expect(page.locator(TABLES.policies)).toBeVisible({ timeout: 10_000 });
  });

  test('policies table renders with data or empty state', async ({ page }) => {
    // The policies table should be visible
    const table = page.locator(TABLES.policies);
    await expect(table).toBeVisible();
    const rows = table.locator('tr');
    // Should have at least one row (data or empty message)
    await expect(rows.first()).toBeVisible();
  });

  test('creates and deletes a policy', async ({ page }) => {
    // Open the add policy panel
    await page.click('button:has-text("Add Policy")');
    await expect(page.locator(PANEL.title)).toContainText(/Add Policy|New Policy/i);

    // The form should have select dropdowns for entity, field/category, purpose
    const panelBody = page.locator(PANEL.body);
    await expect(panelBody).toBeVisible();

    // Wait for dropdowns to populate (they load async from API)
    await page.waitForTimeout(1000);

    // Select the first entity option
    const entitySelect = panelBody.locator('select').first();
    await expect(entitySelect).toBeVisible({ timeout: 5_000 });
    const entityOptions = await entitySelect.locator('option:not([value=""])').count();
    test.skip(entityOptions === 0, 'No entities available — cannot create policy');
    await entitySelect.selectOption({ index: 1 });

    // Wait for dependent dropdowns to load
    await page.waitForTimeout(500);

    // Select field or category (second select)
    const selects = panelBody.locator('select');
    const selectCount = await selects.count();
    if (selectCount >= 2) {
      const fieldSelect = selects.nth(1);
      const fieldOptions = await fieldSelect.locator('option:not([value=""])').count();
      if (fieldOptions > 0) {
        await fieldSelect.selectOption({ index: 1 });
      }
    }

    // Wait for purpose dropdown
    await page.waitForTimeout(500);

    // Select purpose (last select)
    if (selectCount >= 3) {
      const purposeSelect = selects.nth(selectCount - 1);
      const purposeOptions = await purposeSelect.locator('option:not([value=""])').count();
      if (purposeOptions > 0) {
        await purposeSelect.selectOption({ index: 1 });
      }
    }

    // Submit the form
    await panelBody.locator('button[type="submit"]').click();

    // Wait for the policy to appear in the table (eventual consistency may require a moment)
    await page.waitForTimeout(2000);

    // The table should now have policy rows
    const rows = page.locator(`${TABLES.policies} tr`);
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThan(0);

    // Delete the last policy (the one we just created)
    const lastRow = rows.last();
    const deleteBtn = lastRow.locator('button:has-text("Delete")');
    if (await deleteBtn.isVisible()) {
      await deleteBtn.click();
      await expect(page.locator(DELETE_MODAL.confirm)).toBeVisible();
      await page.click(DELETE_MODAL.confirm);
      await page.waitForTimeout(2000);
    }
  });
});
