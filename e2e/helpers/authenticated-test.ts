import { test as base } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';

/**
 * Extended test fixture that injects sessionStorage data before each page navigation.
 * Playwright's storageState restores cookies but NOT sessionStorage — this fills the gap.
 */
export const test = base.extend({
  page: async ({ page }, use) => {
    const ssPath = path.join(__dirname, '.auth', 'session-storage.json');
    if (fs.existsSync(ssPath)) {
      const sessionData: Record<string, string> = JSON.parse(fs.readFileSync(ssPath, 'utf-8'));

      // Inject sessionStorage before any page loads
      await page.addInitScript((data) => {
        for (const [key, value] of Object.entries(data)) {
          sessionStorage.setItem(key, value);
        }
      }, sessionData);
    }
    await use(page);
  },
});

export { expect } from '@playwright/test';
