import { test, expect } from '../../helpers/authenticated-test';
import { TABS, TABLES, DELETE_MODAL } from '../../helpers';

/** Poll until the governance read model has caught up after writes. */
async function waitForProjection(
  page: import('@playwright/test').Page,
  headers: Record<string, string>,
  baseUrl: string,
  { field, purpose }: { field?: string; purpose?: string },
) {
  for (let i = 0; i < 15; i++) {
    await page.waitForTimeout(500);
    let ready = true;
    if (field) {
      const r = await page.request.get(`${baseUrl}/api/v1/catalogue/fields`, { headers });
      const data = await r.json();
      if (!Array.isArray(data) || !data.some((f: any) => f.fieldName === field)) ready = false;
    }
    if (purpose) {
      const r = await page.request.get(`${baseUrl}/api/v1/governance/purposes`, { headers });
      const data = await r.json();
      if (!Array.isArray(data) || !data.some((p: any) => p.purposeName === purpose)) ready = false;
    }
    if (ready) return;
  }
}

test.describe('Governance Policies CRUD @governance', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/governance');
    await expect(page.locator(TABS.policies)).toBeVisible({ timeout: 15_000 });
    await page.click(TABS.policies);
    await expect(page.locator(TABLES.policies)).toBeVisible({ timeout: 10_000 });
  });

  test('policies table renders with data or empty state', async ({ page }) => {
    const table = page.locator(TABLES.policies);
    await expect(table).toBeVisible();
    const rows = table.locator('tr');
    await expect(rows.first()).toBeVisible();
  });

  test('creates and deletes a policy', async ({ page }) => {
    const tokens = await page.evaluate(() => ({
      accessToken: sessionStorage.getItem('cveil_access_token'),
      orgCookie: document.cookie.split(';').find(c => c.trim().startsWith('cveil_active_org='))?.split('=')[1] || '',
    }));
    const baseUrl = 'http://localhost:8080';
    const headers: Record<string, string> = {
      'Authorization': `Bearer ${tokens.accessToken}`,
      'Content-Type': 'application/json',
    };
    if (tokens.orgCookie) headers['X-Org-Id'] = tokens.orgCookie;

    const ts = Date.now();
    const entityName = `e2e_pol_${ts}_ent`;
    const fieldName = `e2e_pol_${ts}_fld`;
    const purposeName = `E2E_POL_${ts}`;

    try {
      // Seed catalogue + governance data via API
      await page.request.post(`${baseUrl}/api/v1/catalogue/entities`, {
        headers, data: { entityName, description: 'E2E policy test entity' },
      });
      await page.request.post(`${baseUrl}/api/v1/catalogue/fields`, {
        headers, data: { fieldName, entityName, description: 'E2E policy test field' },
      });
      await page.request.post(`${baseUrl}/api/v1/governance/purposes`, {
        headers, data: { purposeName, description: 'E2E policy test purpose' },
      });

      // Wait for projections to catch up
      await waitForProjection(page, headers, baseUrl, { field: fieldName, purpose: purposeName });

      // Reload page so JS dropdowns pick up new data
      await page.reload();
      await expect(page.locator(TABS.policies)).toBeVisible({ timeout: 15_000 });
      await page.click(TABS.policies);
      await expect(page.locator(TABLES.policies)).toBeVisible({ timeout: 10_000 });

      // Open Add Policy panel
      await page.click('button:has-text("Add Policy")');
      await expect(page.locator('#pf-pol-purpose')).toBeVisible({ timeout: 5_000 });

      // Fill form: purpose, type (defaults to FIELD), field
      await page.locator('#pf-pol-purpose').selectOption(purposeName);
      await expect(page.locator('#pf-pol-field-select')).toBeVisible();
      const fieldOptions = page.locator('#pf-pol-field-select option:not([value=""])');
      await expect(fieldOptions.first()).toBeAttached({ timeout: 5_000 });
      await page.locator('#pf-pol-field-select').selectOption({ index: 1 });

      // Submit (governance panel id is #panel, not #slide-panel)
      await page.locator('#panel-body button[type="submit"]').click();

      // Verify policy appears in table
      await expect(page.locator(TABLES.policies)).toContainText(purposeName, { timeout: 10_000 });

      // Delete via UI: click "Remove" on the policy row, confirm in modal
      const policyRow = page.locator(`${TABLES.policies} tr`, { hasText: purposeName });
      await policyRow.locator('button:has-text("Remove")').click();
      await expect(page.locator(DELETE_MODAL.confirm)).toBeVisible();
      await page.click(DELETE_MODAL.confirm);
      await page.waitForTimeout(1000);
    } finally {
      // Cleanup via API (the UI delete may not match due to field/entity mapping)
      await page.request.fetch(`${baseUrl}/api/v1/governance/purposes/policies`, {
        method: 'DELETE', headers,
        data: { purposeName, policyType: 'FIELD', fieldName, entityName, categoryName: null },
      });
      await page.request.delete(`${baseUrl}/api/v1/governance/purposes/${purposeName}`, { headers });
      await page.request.delete(`${baseUrl}/api/v1/catalogue/fields/${entityName}/${fieldName}`, { headers });
      await page.request.delete(`${baseUrl}/api/v1/catalogue/entities/${entityName}`, { headers });
    }
  });
});
