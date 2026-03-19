import { test, expect } from '../../helpers/authenticated-test';
import { NAV, NAV_GROUPS } from '../../helpers';

test.describe('Sidebar Navigation Groups @sidebar', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard');
    // Wait for sidebar nav groups to render
    await expect(page.locator(NAV_GROUPS.workspace).first()).toBeVisible({ timeout: 15_000 });
  });

  test('all four nav groups are visible', async ({ page }) => {
    await expect(page.locator(NAV_GROUPS.workspace).first()).toBeVisible();
    await expect(page.locator(NAV_GROUPS.privacy).first()).toBeVisible();
    await expect(page.locator(NAV_GROUPS.insights).first()).toBeVisible();
    await expect(page.locator(NAV_GROUPS.settings).first()).toBeVisible();
  });

  test('groups display correct section labels', async ({ page }) => {
    const workspace = page.locator(`${NAV_GROUPS.workspace} button`).first();
    const privacy = page.locator(`${NAV_GROUPS.privacy} button`).first();
    const insights = page.locator(`${NAV_GROUPS.insights} button`).first();
    const settings = page.locator(`${NAV_GROUPS.settings} button`).first();

    await expect(workspace).toContainText('Workspace');
    await expect(privacy).toContainText('Privacy');
    await expect(insights).toContainText('Insights');
    await expect(settings).toContainText('Settings');
  });

  test('all groups are expanded by default', async ({ page }) => {
    // Clear any persisted collapsed state
    await page.evaluate(() => {
      localStorage.removeItem('cveil_nav_workspace');
      localStorage.removeItem('cveil_nav_privacy');
      localStorage.removeItem('cveil_nav_insights');
      localStorage.removeItem('cveil_nav_settings');
    });
    await page.reload();
    await expect(page.locator(NAV_GROUPS.workspace).first()).toBeVisible({ timeout: 15_000 });

    // All nav items should be visible
    await expect(page.locator(NAV.overview).first()).toBeVisible();
    await expect(page.locator(NAV.governance).first()).toBeVisible();
    await expect(page.locator(NAV.consent).first()).toBeVisible();
    await expect(page.locator(NAV.dsar).first()).toBeVisible();
    await expect(page.locator(NAV.analytics).first()).toBeVisible();
    await expect(page.locator(NAV.reports).first()).toBeVisible();
    await expect(page.locator(NAV.webhooks).first()).toBeVisible();
    await expect(page.locator(NAV.billing).first()).toBeVisible();
    await expect(page.locator(NAV.account).first()).toBeVisible();
  });

  test('correct items grouped under each section', async ({ page }) => {
    // Workspace group contains Overview, Governance, Catalogue
    const wsGroup = page.locator(NAV_GROUPS.workspace).first();
    await expect(wsGroup.locator('a[href="/dashboard"]')).toBeVisible();
    await expect(wsGroup.locator('a[href="/dashboard/governance"]')).toBeVisible();
    await expect(wsGroup.locator('a[href="/dashboard/catalogue"]')).toBeVisible();

    // Privacy group contains Consent, DSAR
    const privGroup = page.locator(NAV_GROUPS.privacy).first();
    await expect(privGroup.locator('a[href="/dashboard/consent"]')).toBeVisible();
    await expect(privGroup.locator('a[href="/dashboard/dsar"]')).toBeVisible();

    // Insights group contains Analytics, Reports
    const insGroup = page.locator(NAV_GROUPS.insights).first();
    await expect(insGroup.locator('a[href="/dashboard/analytics"]')).toBeVisible();
    await expect(insGroup.locator('a[href="/dashboard/reports"]')).toBeVisible();

    // Settings group contains Webhooks, Billing, Account
    const setGroup = page.locator(NAV_GROUPS.settings).first();
    await expect(setGroup.locator('a[href="/dashboard/webhooks"]')).toBeVisible();
    await expect(setGroup.locator('a[href="/dashboard/billing"]')).toBeVisible();
    await expect(setGroup.locator('a[href="/dashboard/account"]')).toBeVisible();
  });

  test('clicking a group header collapses and expands it', async ({ page }) => {
    const groupBtn = page.locator(`${NAV_GROUPS.privacy} button`).first();
    const items = page.locator(`${NAV_GROUPS.privacy} .nav-group-items`).first();

    // Should start expanded
    await expect(items).toBeVisible();

    // Collapse
    await groupBtn.click();
    await expect(items).toBeHidden({ timeout: 3_000 });

    // Expand again
    await groupBtn.click();
    await expect(items).toBeVisible({ timeout: 3_000 });
  });

  test('collapsed state persists across page navigation', async ({ page }) => {
    // Collapse the Insights group
    const groupBtn = page.locator(`${NAV_GROUPS.insights} button`).first();
    await groupBtn.click();

    const items = page.locator(`${NAV_GROUPS.insights} .nav-group-items`).first();
    await expect(items).toBeHidden({ timeout: 3_000 });

    // Navigate to a different page
    await page.click(NAV.billing);
    await page.waitForURL('**/billing**');

    // Insights should still be collapsed
    const insightsItems = page.locator(`${NAV_GROUPS.insights} .nav-group-items`).first();
    await expect(insightsItems).toBeHidden();

    // Other groups should still be expanded
    const workspaceItems = page.locator(`${NAV_GROUPS.workspace} .nav-group-items`).first();
    await expect(workspaceItems).toBeVisible();

    // Clean up: re-expand for other tests
    await page.locator(`${NAV_GROUPS.insights} button`).first().click();
  });

  test('webhooks link navigates to /dashboard/webhooks', async ({ page }) => {
    await page.click(NAV.webhooks);
    await page.waitForURL('**/webhooks**', { timeout: 10_000 });
    await expect(page).toHaveURL(/\/dashboard\/webhooks/);
  });
});
