import { test, expect } from '../../helpers/authenticated-test';
import { TABS } from '../../helpers';

test.describe('Consent Graph Explorer @consent', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard/consent');
    await expect(page.locator(TABS.graph)).toBeVisible({ timeout: 15_000 });
    await page.click(TABS.graph);
    await expect(page.locator('#section-graph')).toBeVisible();
  });

  test('graph renders SVG with nodes', async ({ page }) => {
    // Wait for loading to finish — either nodes render or empty state
    await page.waitForFunction(() => {
      const loading = document.getElementById('graph-loading');
      return loading && loading.classList.contains('hidden');
    }, null, { timeout: 15_000 });

    // Either the SVG has circles (nodes) or the empty state is shown
    const svg = page.locator('#graph-svg');
    await expect(svg).toBeVisible();

    const hasNodes = await svg.locator('circle').count() > 0;
    const isEmpty = await page.locator('#graph-empty').isVisible();
    expect(hasNodes || isEmpty).toBeTruthy();
  });

  test('graph mode switches between force and tree', async ({ page }) => {
    // Wait for initial load
    await page.waitForFunction(() => {
      const loading = document.getElementById('graph-loading');
      return loading && loading.classList.contains('hidden');
    }, null, { timeout: 15_000 });

    // Switch to tree mode
    await page.click('#graph-mode-tree');
    await expect(page.locator('#graph-mode-tree')).toHaveClass(/bg-primary/);

    // Switch back to force
    await page.click('#graph-mode-force');
    await expect(page.locator('#graph-mode-force')).toHaveClass(/bg-primary/);
  });

  test('graph search triggers a reload and reset clears input', async ({ page }) => {
    const searchInput = page.locator('#graph-search');
    await expect(searchInput).toBeVisible();

    // Type a search term and trigger search
    await searchInput.fill('test_search_term');
    await page.locator('button[onclick="loadGraph()"]').click();

    // Verify loading indicator appears then resolves
    await page.waitForFunction(() => {
      const loading = document.getElementById('graph-loading');
      return loading && loading.classList.contains('hidden');
    }, null, { timeout: 10_000 });

    // SVG should still be visible (graph rendered or empty state)
    await expect(page.locator('#graph-svg')).toBeVisible();

    // Reset clears the search input
    await page.locator('button[onclick="resetGraph()"]').click();
    await page.waitForFunction(() => {
      const loading = document.getElementById('graph-loading');
      return loading && loading.classList.contains('hidden');
    }, null, { timeout: 10_000 });

    await expect(searchInput).toHaveValue('');
  });
});
