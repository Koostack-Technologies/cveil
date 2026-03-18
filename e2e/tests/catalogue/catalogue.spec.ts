import { test, expect } from '@playwright/test';
import { TABS, TABLES, PANEL, DELETE_MODAL } from '../../helpers';

test.describe('Catalogue Module @catalogue', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/catalogue');
    await expect(page.locator(TABS.entities)).toBeVisible({ timeout: 15_000 });
  });

  test.describe('Entities CRUD', () => {
    const entName = `e2e_entity_${Date.now()}`;

    test('creates, verifies, edits, and deletes an entity', async ({ page }) => {
      await page.click(TABS.entities);
      await expect(page.locator(TABLES.entities)).toBeVisible();

      // Add
      await page.click('button:has-text("Add Entity")');
      await expect(page.locator(PANEL.title)).toContainText('Add Entity');
      await page.fill('#pf-ent-name', entName);
      await page.fill('#pf-ent-desc', 'E2E test entity');
      await page.click(`${PANEL.body} button[type="submit"]`);

      // Verify
      await expect(page.locator(TABLES.entities)).toContainText(entName, { timeout: 10_000 });

      // Edit
      const row = page.locator(TABLES.entities).locator('tr', { hasText: entName });
      await row.locator('button:has-text("Edit")').click();
      await expect(page.locator(PANEL.title)).toContainText('Edit Entity');
      await page.fill('#pf-ent-desc', 'Updated by E2E');
      await page.click(`${PANEL.body} button[type="submit"]`);
      await expect(row).toContainText('Updated by E2E', { timeout: 10_000 });

      // Delete
      await row.locator('button:has-text("Delete")').click();
      await expect(page.locator(DELETE_MODAL.confirm)).toBeVisible();
      await page.click(DELETE_MODAL.confirm);
      await expect(page.locator(TABLES.entities)).not.toContainText(entName, { timeout: 10_000 });
    });
  });

  test.describe('Fields CRUD', () => {
    const fldName = `e2e_field_${Date.now()}`;

    test('creates, verifies, and deletes a field', async ({ page }) => {
      await page.click(TABS.fields);
      await expect(page.locator(TABLES.fields)).toBeVisible();

      // Add
      await page.click('button:has-text("Add Field")');
      await expect(page.locator(PANEL.title)).toContainText('Add Field');
      await page.fill('#pf-fld-name', fldName);

      // Select the first available entity in the dropdown
      const entitySelect = page.locator('#pf-fld-entity');
      await entitySelect.selectOption({ index: 1 }).catch(async () => {
        // If no entities exist, the dropdown may only have a placeholder
        // — select whatever is available
        const options = await entitySelect.locator('option').all();
        if (options.length > 0) {
          await entitySelect.selectOption({ index: 0 });
        }
      });

      await page.fill('#pf-fld-desc', 'E2E test field');
      await page.click(`${PANEL.body} button[type="submit"]`);

      // Verify
      await expect(page.locator(TABLES.fields)).toContainText(fldName, { timeout: 10_000 });

      // Delete
      const row = page.locator(TABLES.fields).locator('tr', { hasText: fldName });
      await row.locator('button:has-text("Delete")').click();
      await expect(page.locator(DELETE_MODAL.confirm)).toBeVisible();
      await page.click(DELETE_MODAL.confirm);
      await expect(page.locator(TABLES.fields)).not.toContainText(fldName, { timeout: 10_000 });
    });
  });

  test.describe('Categories CRUD', () => {
    const catName = `e2e_cat_${Date.now()}`;

    test('creates, verifies, edits, and deletes a category', async ({ page }) => {
      await page.click(TABS.categories);
      await expect(page.locator(TABLES.categories)).toBeVisible();

      // Add
      await page.click('button:has-text("Add Category")');
      await expect(page.locator(PANEL.title)).toContainText('Add Category');
      await page.fill('#pf-cat-name', catName);
      await page.fill('#pf-cat-desc', 'E2E test category');
      await page.click(`${PANEL.body} button[type="submit"]`);

      // Verify
      await expect(page.locator(TABLES.categories)).toContainText(catName, { timeout: 10_000 });

      // Edit
      const row = page.locator(TABLES.categories).locator('tr', { hasText: catName });
      await row.locator('button:has-text("Edit")').click();
      await expect(page.locator(PANEL.title)).toContainText('Edit Category');
      await page.fill('#pf-cat-desc', 'Updated by E2E');
      await page.click(`${PANEL.body} button[type="submit"]`);
      await expect(row).toContainText('Updated by E2E', { timeout: 10_000 });

      // Delete
      await row.locator('button:has-text("Delete")').click();
      await expect(page.locator(DELETE_MODAL.confirm)).toBeVisible();
      await page.click(DELETE_MODAL.confirm);
      await expect(page.locator(TABLES.categories)).not.toContainText(catName, { timeout: 10_000 });
    });
  });
});
