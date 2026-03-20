/** Shared test user credentials used by global setup and available in tests. */
export const TEST_USER = {
  username: `e2euser_${Date.now()}`,
  email: `e2e_${Date.now()}@test.local`,
  fullName: 'E2E Test User',
  password: 'TestPass123!',
  plan: 'free',
};

/** Selectors for email verification UI elements. */
export const VERIFY = {
  banner: '#verify-email-banner',
  bannerResendBtn: '#verify-resend-btn',
  bannerDismissBtn: '#verify-dismiss-btn',
  bannerResendMsg: '#verify-resend-msg',
  badge: '#email-verify-badge',
  accountResendBtn: '#account-resend-btn',
  accountVerifyMsg: '#account-verify-msg',
} as const;
