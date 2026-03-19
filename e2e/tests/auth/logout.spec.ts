import { test, expect } from '@playwright/test';
import { uniqueUser, fillSignupForm, completeOnboarding } from '../../helpers';

test.describe('Logout @auth', () => {
  test('logs out from dashboard and redirects to login', async ({ page }) => {
    // Create a fully onboarded user
    const user = uniqueUser('logout');
    await page.goto('/signup');
    await fillSignupForm(page, user);
    await page.waitForURL('**/onboarding**', { timeout: 30_000 });
    await completeOnboarding(page);

    // Now on dashboard — click logout
    await expect(page.locator('#logout-btn')).toBeVisible();
    await page.click('#logout-btn');

    // Should redirect to /login
    await page.waitForURL('**/login**', { timeout: 15_000 });
    await expect(page.locator('h1')).toContainText('Welcome back');

    // Verify auth state is cleared — sessionStorage tokens removed
    const accessToken = await page.evaluate(() => sessionStorage.getItem('cveil_access_token'));
    expect(accessToken).toBeNull();
  });
});
