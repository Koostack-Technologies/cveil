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
  webhooks: 'aside a[href="/dashboard/webhooks"]',
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
  // Billing
  usage: '#tab-usage',
  plan: '#tab-plan',
  invoices: '#tab-invoices',
  payment: '#tab-payment',
  // DSAR has no tabs (single page)
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
  dsarTable: '#dsar-table-body',
  auditLog: '#audit-log-body',
  invoicesTable: '#invoices-table',
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

/** Consent ledger (SSE) selectors. */
export const LEDGER = {
  sseStatus: '#sse-status',
  feed: '#ledger-feed',
  empty: '#ledger-empty',
  count: '#ledger-count',
  filterAction: '#ledger-filter-action',
  filterSubject: '#ledger-filter-subject',
  filterPurpose: '#ledger-filter-purpose',
  pauseBtn: '#ledger-pause-btn',
} as const;

/** Account profile/orgs selectors. */
export const ACCOUNT = {
  displayEmail: '#display-email',
  displayFullname: '#display-fullname',
  displayRoles: '#display-roles',
  profileForm: '#profile-form',
  fullNameInput: '#fullName',
  emailInput: '#emailInput',
  profileSaveBtn: '#profile-save-btn',
  profileMsg: '#profile-msg',
  passwordForm: '#password-form',
  newPassword: '#newPassword',
  confirmPassword: '#confirmPassword',
  passwordSaveBtn: '#password-save-btn',
  passwordMsg: '#password-msg',
  orgsList: '#orgs-list',
} as const;

/** DSAR page selectors. */
export const DSAR = {
  countPending: '#dsar-count-pending',
  countInProgress: '#dsar-count-in-progress',
  countCompleted: '#dsar-count-completed',
  countOverdue: '#dsar-count-overdue',
  filterStatus: '#dsar-filter-status',
  filterType: '#dsar-filter-type',
  createModal: '#dsar-create-modal',
  detailModal: '#dsar-detail-modal',
  detailContent: '#dsar-detail-content',
  pageInfo: '#dsar-page-info',
  prevBtn: '#dsar-prev',
  nextBtn: '#dsar-next',
} as const;

/** Billing page selectors. */
export const BILLING = {
  currentPlanName: '#current-plan-name',
  planStatusBadge: '#plan-status-badge',
  limitRequests: '#limit-requests',
  limitRequestsBar: '#limit-requests-bar',
  limitSubjects: '#limit-subjects',
  limitSubjectsBar: '#limit-subjects-bar',
  plansGrid: '#plans-grid',
  usageChart: '#usage-chart',
  chartLoading: '#chart-loading',
} as const;

/** Reports page selectors. */
export const REPORTS = {
  consentSummaryChart: '#consent-summary-chart',
  consentActivityChart: '#consent-activity-chart',
  activityRange: '#activity-range',
  govSnapshot: '#governance-snapshot',
  auditSubject: '#audit-subject',
  auditPageInfo: '#audit-page-info',
  auditPrev: '#audit-prev',
  auditNext: '#audit-next',
} as const;

/** Webhooks page selectors. */
export const WEBHOOKS = {
  loading: '#webhooks-loading',
  empty: '#webhooks-empty',
  tableWrap: '#webhooks-table-wrap',
  tbody: '#webhooks-tbody',
  createBtn: '#btn-create-webhook',
  modal: '#webhook-modal',
  modalTitle: '#webhook-modal-title',
  urlInput: '#webhook-url',
  descInput: '#webhook-desc',
  eventsContainer: '#webhook-events',
  formError: '#webhook-form-error',
  submitBtn: '#webhook-submit-btn',
  editIdInput: '#webhook-edit-id',
  secretModal: '#secret-modal',
  secretValue: '#secret-value',
} as const;

/** Sidebar navigation group selectors. */
export const NAV_GROUPS = {
  workspace: '.nav-group[data-group="workspace"]',
  privacy: '.nav-group[data-group="privacy"]',
  insights: '.nav-group[data-group="insights"]',
  settings: '.nav-group[data-group="settings"]',
} as const;
