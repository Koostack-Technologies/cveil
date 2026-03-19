import { test, expect } from '../../helpers/authenticated-test';
import { TABS, LEDGER } from '../../helpers';

test.describe('Consent Ledger SSE @consent', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/consent');
    await expect(page.locator(TABS.ledger)).toBeVisible({ timeout: 15_000 });
    await page.click(TABS.ledger);
    await expect(page.locator('#section-ledger')).toBeVisible();
  });

  test('SSE connects and shows Live status', async ({ page }) => {
    // fetchEventSource sends DPoP/Bearer auth headers, so SSE should connect
    await expect(page.locator(LEDGER.sseStatus)).toContainText('Live', { timeout: 15_000 });
  });

  test('ledger feed container is visible', async ({ page }) => {
    const feed = page.locator(LEDGER.feed);
    await expect(feed).toBeVisible();
  });

  test('SSE receives event after consent grant via API', async ({ page }) => {
    // Wait for SSE to connect
    await expect(page.locator(LEDGER.sseStatus)).toContainText('Live', { timeout: 15_000 });

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
    const entityName = `e2e_sse_${ts}_ent`;
    const fieldName = `e2e_sse_${ts}_fld`;
    const categoryName = `E2E_SSE_${ts}_CAT`;
    const purposeName = `E2E_SSE_${ts}`;
    const subjectId = `e2e_sse_subject_${ts}`;

    try {
      // Seed full governance chain: entity → field → category → field-category → purpose → policy
      await page.request.post(`${baseUrl}/api/v1/catalogue/entities`, {
        headers, data: { entityName, description: 'E2E SSE test entity' },
      });
      await page.request.post(`${baseUrl}/api/v1/catalogue/fields`, {
        headers, data: { fieldName, entityName, description: 'E2E SSE test field' },
      });
      await page.request.post(`${baseUrl}/api/v1/catalogue/categories`, {
        headers, data: { categoryName, description: 'E2E SSE test category' },
      });
      await page.request.post(`${baseUrl}/api/v1/catalogue/fields/categories`, {
        headers, data: { fieldName, entityName, categoryName },
      });
      await page.request.post(`${baseUrl}/api/v1/governance/purposes`, {
        headers, data: { purposeName, description: 'E2E SSE test purpose' },
      });

      // Wait for projections before creating policy
      for (let i = 0; i < 15; i++) {
        await page.waitForTimeout(500);
        const r = await page.request.get(`${baseUrl}/api/v1/governance/purposes`, { headers });
        const data = await r.json();
        if (Array.isArray(data) && data.some((p: any) => p.purposeName === purposeName)) break;
      }

      await page.request.post(`${baseUrl}/api/v1/governance/purposes/policies`, {
        headers,
        data: { purposeName, policyType: 'FIELD', fieldName, entityName, categoryName: null },
      });
      await page.waitForTimeout(1500);

      // Grant consent via API (scope requires category)
      const grantRes = await page.request.post(`${baseUrl}/api/v1/subjects/consent/grant`, {
        headers,
        data: {
          subject: subjectId,
          scope: { purposeName, fieldName, entityName, category: categoryName },
        },
      });
      expect(grantRes.ok()).toBeTruthy();

      // Wait for the SSE event to appear in the ledger feed
      await expect(page.locator(LEDGER.feed)).toContainText(subjectId, { timeout: 30_000 });
    } finally {
      await page.request.fetch(`${baseUrl}/api/v1/governance/purposes/policies`, {
        method: 'DELETE', headers,
        data: { purposeName, policyType: 'FIELD', fieldName, entityName, categoryName: null },
      });
      await page.request.delete(`${baseUrl}/api/v1/governance/purposes/${purposeName}`, { headers });
      await page.request.delete(`${baseUrl}/api/v1/catalogue/fields/${entityName}/${fieldName}/categories/${categoryName}`, { headers });
      await page.request.delete(`${baseUrl}/api/v1/catalogue/fields/${entityName}/${fieldName}`, { headers });
      await page.request.delete(`${baseUrl}/api/v1/catalogue/categories/${categoryName}`, { headers });
      await page.request.delete(`${baseUrl}/api/v1/catalogue/entities/${entityName}`, { headers });
    }
  });

  test('ledger action filter dropdown works', async ({ page }) => {
    const filterAction = page.locator(LEDGER.filterAction);
    await expect(filterAction).toBeVisible();

    // Select "GRANTED" filter
    await filterAction.selectOption('GRANTED');
    await expect(page.locator(LEDGER.feed)).toBeVisible();

    // Switch to "REVOKED"
    await filterAction.selectOption('REVOKED');
    await expect(page.locator(LEDGER.feed)).toBeVisible();

    // Reset to all
    await filterAction.selectOption('');
    await expect(page.locator(LEDGER.feed)).toBeVisible();
  });
});
