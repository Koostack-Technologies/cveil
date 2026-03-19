export { TEST_USER } from './constants';
export { uniqueUser, fillSignupForm, fillLoginForm, completeOnboarding, signupAndOnboard, injectAuthState } from './auth';

/** Sidebar navigation link selectors (by href). */
export const NAV = {
  overview: 'aside a[href="/dashboard"]',
  governance: 'aside a[href="/dashboard/governance"]',
  consent: 'aside a[href="/dashboard/consent"]',
  catalogue: 'aside a[href="/dashboard/catalogue"]',
  billing: 'aside a[href="/dashboard/billing"]',
  reports: 'aside a[href="/dashboard/reports"]',
  analytics: 'aside a[href="/dashboard/analytics"]',
  dsar: 'aside a[href="/dashboard/dsar"]',
  account: 'aside a[href="/dashboard/account"]',
} as const;

/** Module tab button selectors (by id). */
export const TABS = {
  // Consent
  graph: '#tab-graph',
  ledger: '#tab-ledger',
  subjects: '#tab-subjects',
  playground: '#tab-playground',
  // Governance
  jurisdictions: '#tab-jurisdictions',
  regulations: '#tab-regulations',
  purposes: '#tab-purposes',
  policies: '#tab-policies',
  // Catalogue
  entities: '#tab-entities',
  fields: '#tab-fields',
  categories: '#tab-categories',
  // Account
  profile: '#tab-profile',
  organizations: '#tab-organizations',
  tokens: '#tab-tokens',
} as const;

/** Table body selectors by module section. */
export const TABLES = {
  subjects: '#subjects-table',
  jurisdictions: '#jurisdictions-table',
  regulations: '#regulations-table',
  purposes: '#purposes-table',
  policies: '#policies-table',
  entities: '#entities-table',
  fields: '#fields-table',
  categories: '#categories-table',
  tokens: '#tokens-table-body',
} as const;

/** Access token page selectors. */
export const TOKEN = {
  createBtn: '#token-create-btn',
  createModal: '#create-token-modal',
  nameInput: '#token-name',
  scopeDisplay: '#token-scope-display',
  expiryOptions: '#expiry-options',
  createMsg: '#create-token-msg',
  submitBtn: '#create-token-submit',
  // show-once modal after creation
  resultModal: '#token-modal',
  resultValue: '#token-value',
  resultName: '#token-result-name',
  resultExpires: '#token-result-expires',
  resultCloseBtn: '#token-modal button:has-text("I\'ve copied the token")',
} as const;

/** Side panel selectors. */
export const PANEL = {
  overlay: '#panel-overlay',
  container: '#panel',
  title: '#panel-title',
  body: '#panel-body',
} as const;

/** Delete confirmation modal. */
export const DELETE_MODAL = {
  container: '#delete-modal',
  confirm: '#delete-modal-confirm',
} as const;
