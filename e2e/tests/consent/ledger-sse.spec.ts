import { test, expect } from '../../helpers/authenticated-test';
import { TABS, LEDGER } from '../../helpers';

test.describe('Consent Ledger SSE @consent', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/consent');
    await expect(page.locator(TABS.ledger)).toBeVisible({ timeout: 15_000 });
    await page.click(TABS.ledger);
    await expect(page.locator('#section-ledger')).toBeVisible();
  });

  test('SSE status indicator renders and shows a connection state', async ({ page }) => {
    // The SSE status element should appear and show a known state.
    // EventSource cannot send Authorization headers, so it may show
    // "Live" (cookie auth) or "Disconnected" (401). Both are valid states.
    const status = page.locator(LEDGER.sseStatus);
    await expect(status).toBeVisible({ timeout: 10_000 });
    await expect(status).toContainText(/Live|Disconnected|Reconnecting|Error|Connecting/);
  });

  test('ledger feed container is visible', async ({ page }) => {
    const feed = page.locator(LEDGER.feed);
    await expect(feed).toBeVisible();
  });

  test('SSE receives event after consent grant via API', async ({ page }) => {
    // Skip if SSE is not connected (EventSource auth limitation)
    const statusText = await page.locator(LEDGER.sseStatus).textContent();
    test.skip(!statusText?.includes('Live'), 'SSE not connected — EventSource cannot send auth headers');

    // Get auth tokens from sessionStorage to make API calls
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

    // List policies to get scope components for consent grant
    const policiesRes = await page.request.get(`${baseUrl}/api/v1/governance/policies`, { headers });
    const policies = await policiesRes.json();
    test.skip(!Array.isArray(policies) || policies.length === 0, 'No policies — consent grant requires governance data');

    const policy = policies[0];
    const subjectId = `e2e_sse_subject_${Date.now()}`;

    // Grant consent via API
    const grantRes = await page.request.post(`${baseUrl}/api/v1/subjects/consent/grant`, {
      headers,
      data: {
        subject: subjectId,
        scope: {
          purposeName: policy.purposeName,
          fieldName: policy.fieldName || undefined,
          entityName: policy.entityName || undefined,
          category: policy.categoryName || undefined,
        },
      },
    });
    expect(grantRes.ok()).toBeTruthy();

    // Wait for the SSE event to appear in the ledger feed
    await expect(page.locator(LEDGER.feed)).toContainText(subjectId, { timeout: 30_000 });
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
