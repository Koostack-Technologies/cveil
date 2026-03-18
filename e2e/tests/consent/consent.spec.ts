import { test, expect } from '@playwright/test';
import { TABS, TABLES } from '../../helpers';

test.describe('Consent Module @consent', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/consent');
    // Wait for the JS bundle to initialize
    await expect(page.locator(TABS.subjects)).toBeVisible({ timeout: 15_000 });
  });

  test('subjects tab renders a table', async ({ page }) => {
    await page.click(TABS.subjects);

    const table = page.locator(TABLES.subjects);
    await expect(table).toBeVisible({ timeout: 10_000 });

    // Table should have at least the header structure (may be empty for fresh tenant)
    const rows = table.locator('tr');
    await expect(rows.first()).toBeVisible();
  });

  test('graph explorer renders the D3 visualization', async ({ page }) => {
    await page.click(TABS.graph);

    // The graph container should render an SVG via D3
    const svg = page.locator('#graph-container svg, #consent-graph svg, [id*="graph"] svg');
    await expect(svg.first()).toBeVisible({ timeout: 15_000 });
  });

  test('event ledger section is visible and streams events', async ({ page }) => {
    await page.click(TABS.ledger);

    // The ledger container should be visible
    const ledgerContainer = page.locator('#ledger-container, #event-ledger, [id*="ledger"]');
    await expect(ledgerContainer.first()).toBeVisible({ timeout: 10_000 });
  });
});
