import { defineConfig } from '@playwright/test';

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:8080';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: [['html', { open: 'never' }], ['list']],
  timeout: 30_000,
  expect: { timeout: 10_000 },

  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  globalSetup: './helpers/global-setup.ts',

  projects: [
    {
      name: 'auth',
      testDir: './tests/auth',
      use: {
        // Auth tests manage their own auth state — no storageState
      },
    },
    {
      name: 'authenticated',
      testDir: './tests',
      testIgnore: '**/auth/**',
      dependencies: ['auth'],
      use: {
        storageState: './helpers/.auth/user.json',
      },
    },
  ],
});
