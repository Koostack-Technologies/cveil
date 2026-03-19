import { test, expect } from '../../helpers/authenticated-test';
import { WEBHOOKS } from '../../helpers';

test.describe('Webhooks Module @webhooks', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/webhooks');
    // Wait for the page to finish loading — either empty state, table, or loading disappears
    await expect(page.locator(WEBHOOKS.loading)).toBeHidden({ timeout: 15_000 });
  });

  test('page loads and shows empty state or endpoints table', async ({ page }) => {
    const empty = page.locator(WEBHOOKS.empty);
    const table = page.locator(WEBHOOKS.tableWrap);

    // One of these must be visible
    const emptyVisible = await empty.isVisible();
    const tableVisible = await table.isVisible();
    expect(emptyVisible || tableVisible).toBe(true);
  });

  test('create button is visible', async ({ page }) => {
    await expect(page.locator(WEBHOOKS.createBtn)).toBeVisible();
  });

  test('clicking create opens the modal with correct title', async ({ page }) => {
    await page.click(WEBHOOKS.createBtn);

    const modal = page.locator(WEBHOOKS.modal);
    await expect(modal).toBeVisible({ timeout: 5_000 });

    await expect(page.locator(WEBHOOKS.modalTitle)).toHaveText('Add webhook endpoint');
    await expect(page.locator(WEBHOOKS.submitBtn)).toHaveText('Create');
  });

  test('modal shows event type checkboxes', async ({ page }) => {
    await page.click(WEBHOOKS.createBtn);
    await expect(page.locator(WEBHOOKS.modal)).toBeVisible({ timeout: 5_000 });

    const checkboxes = page.locator(`${WEBHOOKS.eventsContainer} input[type="checkbox"]`);
    const count = await checkboxes.count();
    // Should have at least the wildcard + a few event types
    expect(count).toBeGreaterThanOrEqual(5);
  });

  test('modal validation rejects empty URL', async ({ page }) => {
    await page.click(WEBHOOKS.createBtn);
    await expect(page.locator(WEBHOOKS.modal)).toBeVisible({ timeout: 5_000 });

    // Clear URL and try to submit — HTML5 required attribute prevents submission
    await page.fill(WEBHOOKS.urlInput, '');
    await page.click(WEBHOOKS.submitBtn);

    // Modal should still be visible (form did not submit)
    await expect(page.locator(WEBHOOKS.modal)).toBeVisible();
  });

  test('modal validation rejects no event types selected', async ({ page }) => {
    await page.click(WEBHOOKS.createBtn);
    await expect(page.locator(WEBHOOKS.modal)).toBeVisible({ timeout: 5_000 });

    await page.fill(WEBHOOKS.urlInput, 'https://example.com/hook');

    // Uncheck all event type checkboxes
    const checkboxes = page.locator(`${WEBHOOKS.eventsContainer} input[type="checkbox"]:checked`);
    const checkedCount = await checkboxes.count();
    for (let i = 0; i < checkedCount; i++) {
      await checkboxes.nth(0).uncheck();
    }

    await page.click(WEBHOOKS.submitBtn);

    // Error message should appear
    await expect(page.locator(WEBHOOKS.formError)).toBeVisible({ timeout: 5_000 });
    await expect(page.locator(WEBHOOKS.formError)).toContainText('event type');
  });

  test('create webhook endpoint and see secret', async ({ page }) => {
    await page.click(WEBHOOKS.createBtn);
    await expect(page.locator(WEBHOOKS.modal)).toBeVisible({ timeout: 5_000 });

    await page.fill(WEBHOOKS.urlInput, 'https://httpbin.org/post');
    await page.fill(WEBHOOKS.descInput, 'E2E test endpoint');

    // Ensure wildcard is checked
    const wildcard = page.locator(`${WEBHOOKS.eventsContainer} input[value="*"]`);
    if (!(await wildcard.isChecked())) {
      await wildcard.check();
    }

    await page.click(WEBHOOKS.submitBtn);

    // Secret modal should appear with a whsec_ prefixed secret
    await expect(page.locator(WEBHOOKS.secretModal)).toBeVisible({ timeout: 10_000 });
    const secretText = await page.locator(WEBHOOKS.secretValue).textContent();
    expect(secretText).toMatch(/^whsec_/);

    // Dismiss secret modal
    await page.locator(`${WEBHOOKS.secretModal} button`).click();

    // Endpoints table should now be visible with our endpoint
    await expect(page.locator(WEBHOOKS.tableWrap)).toBeVisible({ timeout: 10_000 });
    await expect(page.locator(WEBHOOKS.tbody)).toContainText('httpbin.org');
    await expect(page.locator(WEBHOOKS.tbody)).toContainText('E2E test endpoint');
  });

  test('edit an existing webhook endpoint', async ({ page }) => {
    // Ensure at least one endpoint exists (create if needed)
    const table = page.locator(WEBHOOKS.tableWrap);
    if (!(await table.isVisible())) {
      await page.click(WEBHOOKS.createBtn);
      await expect(page.locator(WEBHOOKS.modal)).toBeVisible({ timeout: 5_000 });
      await page.fill(WEBHOOKS.urlInput, 'https://httpbin.org/post');
      const wildcard = page.locator(`${WEBHOOKS.eventsContainer} input[value="*"]`);
      if (!(await wildcard.isChecked())) await wildcard.check();
      await page.click(WEBHOOKS.submitBtn);
      await expect(page.locator(WEBHOOKS.secretModal)).toBeVisible({ timeout: 10_000 });
      await page.locator(`${WEBHOOKS.secretModal} button`).click();
      await expect(page.locator(WEBHOOKS.tableWrap)).toBeVisible({ timeout: 10_000 });
    }

    // Click Edit on the first endpoint
    const editBtn = page.locator(`${WEBHOOKS.tbody} button`).filter({ hasText: 'Edit' }).first();
    await editBtn.click();

    // Modal should open in edit mode
    await expect(page.locator(WEBHOOKS.modal)).toBeVisible({ timeout: 5_000 });
    await expect(page.locator(WEBHOOKS.modalTitle)).toHaveText('Edit webhook endpoint');
    await expect(page.locator(WEBHOOKS.submitBtn)).toHaveText('Save');

    // Update description
    await page.fill(WEBHOOKS.descInput, 'Updated by E2E');
    await page.click(WEBHOOKS.submitBtn);

    // Modal should close and table should reflect the update
    await expect(page.locator(WEBHOOKS.modal)).toBeHidden({ timeout: 5_000 });
    await expect(page.locator(WEBHOOKS.tbody)).toContainText('Updated by E2E');
  });

  test('test ping an endpoint', async ({ page }) => {
    // Ensure at least one endpoint exists
    const table = page.locator(WEBHOOKS.tableWrap);
    if (!(await table.isVisible())) {
      await page.click(WEBHOOKS.createBtn);
      await expect(page.locator(WEBHOOKS.modal)).toBeVisible({ timeout: 5_000 });
      await page.fill(WEBHOOKS.urlInput, 'https://httpbin.org/post');
      const wildcard = page.locator(`${WEBHOOKS.eventsContainer} input[value="*"]`);
      if (!(await wildcard.isChecked())) await wildcard.check();
      await page.click(WEBHOOKS.submitBtn);
      await expect(page.locator(WEBHOOKS.secretModal)).toBeVisible({ timeout: 10_000 });
      await page.locator(`${WEBHOOKS.secretModal} button`).click();
      await expect(page.locator(WEBHOOKS.tableWrap)).toBeVisible({ timeout: 10_000 });
    }

    // Listen for the dialog (alert) triggered by test ping
    page.on('dialog', (dialog) => dialog.accept());

    const testBtn = page.locator(`${WEBHOOKS.tbody} button`).filter({ hasText: 'Test' }).first();
    await testBtn.click();

    // The alert is automatically accepted above; just verify the page is still functional
    await expect(page.locator(WEBHOOKS.tableWrap)).toBeVisible();
  });

  test('rotate secret shows new secret', async ({ page }) => {
    // Ensure at least one endpoint exists
    const table = page.locator(WEBHOOKS.tableWrap);
    if (!(await table.isVisible())) {
      await page.click(WEBHOOKS.createBtn);
      await expect(page.locator(WEBHOOKS.modal)).toBeVisible({ timeout: 5_000 });
      await page.fill(WEBHOOKS.urlInput, 'https://httpbin.org/post');
      const wildcard = page.locator(`${WEBHOOKS.eventsContainer} input[value="*"]`);
      if (!(await wildcard.isChecked())) await wildcard.check();
      await page.click(WEBHOOKS.submitBtn);
      await expect(page.locator(WEBHOOKS.secretModal)).toBeVisible({ timeout: 10_000 });
      await page.locator(`${WEBHOOKS.secretModal} button`).click();
      await expect(page.locator(WEBHOOKS.tableWrap)).toBeVisible({ timeout: 10_000 });
    }

    // Accept the confirmation dialog
    page.on('dialog', (dialog) => dialog.accept());

    const rotateBtn = page.locator(`${WEBHOOKS.tbody} button`).filter({ hasText: 'Rotate' }).first();
    await rotateBtn.click();

    // Secret modal should appear with a new whsec_ prefixed secret
    await expect(page.locator(WEBHOOKS.secretModal)).toBeVisible({ timeout: 10_000 });
    const secretText = await page.locator(WEBHOOKS.secretValue).textContent();
    expect(secretText).toMatch(/^whsec_/);

    await page.locator(`${WEBHOOKS.secretModal} button`).click();
  });

  test('delete a webhook endpoint', async ({ page }) => {
    // Ensure at least one endpoint exists
    const table = page.locator(WEBHOOKS.tableWrap);
    if (!(await table.isVisible())) {
      await page.click(WEBHOOKS.createBtn);
      await expect(page.locator(WEBHOOKS.modal)).toBeVisible({ timeout: 5_000 });
      await page.fill(WEBHOOKS.urlInput, 'https://httpbin.org/post');
      await page.fill(WEBHOOKS.descInput, 'To be deleted');
      const wildcard = page.locator(`${WEBHOOKS.eventsContainer} input[value="*"]`);
      if (!(await wildcard.isChecked())) await wildcard.check();
      await page.click(WEBHOOKS.submitBtn);
      await expect(page.locator(WEBHOOKS.secretModal)).toBeVisible({ timeout: 10_000 });
      await page.locator(`${WEBHOOKS.secretModal} button`).click();
      await expect(page.locator(WEBHOOKS.tableWrap)).toBeVisible({ timeout: 10_000 });
    }

    // Count rows before delete
    const rowsBefore = await page.locator(`${WEBHOOKS.tbody} tr`).count();

    // Accept the confirmation dialog
    page.on('dialog', (dialog) => dialog.accept());

    const deleteBtn = page.locator(`${WEBHOOKS.tbody} button`).filter({ hasText: 'Delete' }).first();
    await deleteBtn.click();

    // Wait for the table to update
    await page.waitForTimeout(2_000);

    if (rowsBefore === 1) {
      // Should show empty state
      await expect(page.locator(WEBHOOKS.empty)).toBeVisible({ timeout: 10_000 });
    } else {
      const rowsAfter = await page.locator(`${WEBHOOKS.tbody} tr`).count();
      expect(rowsAfter).toBe(rowsBefore - 1);
    }
  });

  test('cancel button closes the modal without changes', async ({ page }) => {
    await page.click(WEBHOOKS.createBtn);
    await expect(page.locator(WEBHOOKS.modal)).toBeVisible({ timeout: 5_000 });

    await page.fill(WEBHOOKS.urlInput, 'https://should-not-save.com/hook');

    // Click cancel
    await page.locator(`${WEBHOOKS.modal} button`).filter({ hasText: 'Cancel' }).click();

    // Modal should be hidden
    await expect(page.locator(WEBHOOKS.modal)).toBeHidden({ timeout: 5_000 });
  });
});
