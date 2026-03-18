/** Shared test user credentials used by global setup and available in tests. */
export const TEST_USER = {
  username: `e2euser_${Date.now()}`,
  email: `e2e_${Date.now()}@test.local`,
  fullName: 'E2E Test User',
  password: 'TestPass123!',
  plan: 'free',
};
