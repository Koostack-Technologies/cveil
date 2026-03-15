/**
 * Billing page orchestrator — fetches metrics data, populates summary cards,
 * wires the chart + legend, time range controls, and top endpoints table.
 * Also manages Payment Methods (Stripe Elements) and Invoices tabs.
 */
import * as chart from './usage-chart.js';
import { createApi, esc, showMsg, activatePanel, closePanel } from '../shared/index.js';

const API_BASE = '/api/v1/metrics';
const billingApi = createApi('/api/v1/billing');
let currentDays = 7;

// Billing-relevant metrics for summary cards
const CONSENT_METRICS = ['consent_grants', 'consent_revocations', 'consent_grant_all', 'consent_revoke_all'];
const SUBJECT_METRICS = ['subject_created', 'subject_updated', 'subject_deleted'];
const DSAR_METRICS = ['dsar_exports', 'dsar_deletions', 'dsar_shred'];

/**
 * Entry point — called from billing.html after DOM + D3 are ready.
 */
export function init() {
    if (!document.getElementById('usage-chart')) return;
    chart.create('#usage-chart');
    chart.buildLegend('#chart-legend');
    refresh();
}

export function setRange(days) {
    currentDays = days;
    // Update button styles
    document.querySelectorAll('.range-btn').forEach(b => {
        b.classList.remove('border-cv-500', 'bg-cv-500', 'text-white');
        b.classList.add('border-gray-300', 'text-gray-600');
    });
    const btn = document.getElementById('range-' + days);
    if (btn) {
        btn.classList.add('border-cv-500', 'bg-cv-500', 'text-white');
        btn.classList.remove('border-gray-300', 'text-gray-600');
    }
    // Clear custom inputs
    const fromEl = document.getElementById('range-from');
    const toEl = document.getElementById('range-to');
    if (fromEl) fromEl.value = '';
    if (toEl) toEl.value = '';
    refresh();
}

export function setCustomRange() {
    const fromEl = document.getElementById('range-from');
    const toEl = document.getElementById('range-to');
    if (!fromEl?.value || !toEl?.value) return;
    // Clear preset buttons
    document.querySelectorAll('.range-btn').forEach(b => {
        b.classList.remove('border-cv-500', 'bg-cv-500', 'text-white');
        b.classList.add('border-gray-300', 'text-gray-600');
    });
    currentDays = 0; // signals custom range
    refresh();
}

export function showAllLines() { chart.showAll(); }
export function hideAllLines() { chart.hideAll(); }

// -----------------------------------------------------------------------
// Data fetching
// -----------------------------------------------------------------------

function getTimeRange() {
    const fromEl = document.getElementById('range-from');
    const toEl = document.getElementById('range-to');
    if (currentDays === 0 && fromEl?.value && toEl?.value) {
        return {
            from: new Date(fromEl.value + 'T00:00:00Z').toISOString(),
            to: new Date(toEl.value + 'T23:59:59Z').toISOString(),
        };
    }
    const to = new Date();
    const from = new Date(to.getTime() - currentDays * 86400000);
    return { from: from.toISOString(), to: to.toISOString() };
}

async function fetchJson(url) {
    try {
        const res = await fetch(url);
        if (!res.ok) throw new Error(res.statusText);
        return await res.json();
    } catch (e) {
        console.warn('Metrics fetch failed:', url, e.message);
        return null;
    }
}

async function refresh() {
    const { from, to } = getTimeRange();
    const loading = document.getElementById('chart-loading');
    if (loading) loading.textContent = 'Loading…';

    // Fetch summary + detail in parallel
    const [summary, detail, endpoints] = await Promise.all([
        fetchJson(`${API_BASE}/summary?from=${from}&to=${to}`),
        fetchJson(`${API_BASE}?from=${from}&to=${to}&pageSize=500`),
        fetchJson(`${API_BASE}?from=${from}&to=${to}&metricName=api_requests&pageSize=500`),
    ]);

    if (loading) loading.textContent = '';

    updateSummaryCards(summary);
    updateChart(detail);
    updateTopEndpoints(endpoints);
    updatePlanLimits(summary);
}

// -----------------------------------------------------------------------
// Summary cards
// -----------------------------------------------------------------------

function sumMetrics(summaries, names) {
    if (!summaries) return 0;
    return summaries
        .filter(s => names.includes(s.metricName))
        .reduce((acc, s) => acc + s.totalValue, 0);
}

function updateSummaryCards(summaryResp) {
    const summaries = summaryResp?.summaries || [];

    setText('card-total-requests', fmtNum(sumMetrics(summaries, ['api_requests'])));
    setText('card-consent-ops', fmtNum(sumMetrics(summaries, CONSENT_METRICS)));
    setText('card-subject-ops', fmtNum(sumMetrics(summaries, SUBJECT_METRICS)));
    setText('card-dsar-ops', fmtNum(sumMetrics(summaries, DSAR_METRICS)));
    setText('card-rate-limits', fmtNum(sumMetrics(summaries, ['rate_limit_exceeded'])));
}

// -----------------------------------------------------------------------
// Chart
// -----------------------------------------------------------------------

function updateChart(detailResp) {
    const rows = detailResp?.data || [];
    chart.update(rows);
}

// -----------------------------------------------------------------------
// Top endpoints
// -----------------------------------------------------------------------

function updateTopEndpoints(endpointsResp) {
    const body = document.getElementById('top-endpoints-body');
    if (!body) return;

    const rows = endpointsResp?.data || [];
    if (rows.length === 0) {
        body.innerHTML = '<tr><td colspan="3" class="py-6 text-center text-gray-400 text-sm">No data for this time range</td></tr>';
        return;
    }

    // Aggregate by path dimension
    const byPath = {};
    for (const row of rows) {
        const path = row.dimensions?.path || row.dimensions?.method || 'unknown';
        if (!byPath[path]) byPath[path] = { count: 0, latencySum: 0, latencyCount: 0 };
        byPath[path].count += row.metricValue;
    }

    // Also look for api_latency_ms rows
    for (const row of rows) {
        if (row.metricName === 'api_latency_ms') {
            const path = row.dimensions?.path || 'unknown';
            if (byPath[path]) {
                byPath[path].latencySum += row.metricValue;
                byPath[path].latencyCount += 1;
            }
        }
    }

    const sorted = Object.entries(byPath)
        .map(([path, d]) => ({
            path,
            count: d.count,
            avgLatency: d.latencyCount > 0 ? Math.round(d.latencySum / d.latencyCount) : '—',
        }))
        .sort((a, b) => b.count - a.count)
        .slice(0, 10);

    body.innerHTML = sorted.map(r => `
        <tr class="border-b border-gray-50 hover:bg-gray-50/50">
            <td class="py-2.5 font-mono text-xs text-gray-700">${esc(r.path)}</td>
            <td class="py-2.5 text-right text-gray-900 font-medium">${fmtNum(r.count)}</td>
            <td class="py-2.5 text-right text-gray-500">${r.avgLatency === '—' ? '—' : r.avgLatency + ' ms'}</td>
        </tr>
    `).join('');
}

// -----------------------------------------------------------------------
// Plan tab — update limit bars from summary data
// -----------------------------------------------------------------------

function updatePlanLimits(summaryResp) {
    const summaries = summaryResp?.summaries || [];
    const totalRequests = sumMetrics(summaries, ['api_requests']);
    const totalSubjects = sumMetrics(summaries, ['subject_created']);

    const reqLimit = subscriptionCache?.evalLimit || 10000;
    const subLimit = subscriptionCache?.subjectLimit || 1000;

    if (reqLimit < 0) {
        setText('limit-requests', `${fmtNum(totalRequests)} / Unlimited`);
        setWidth('limit-requests-bar', 0);
    } else {
        setText('limit-requests', `${fmtNum(totalRequests)} / ${fmtNum(reqLimit)}`);
        setWidth('limit-requests-bar', Math.min(100, (totalRequests / reqLimit) * 100));
    }

    if (subLimit < 0) {
        setText('limit-subjects', `${fmtNum(totalSubjects)} / Unlimited`);
        setWidth('limit-subjects-bar', 0);
    } else {
        setText('limit-subjects', `${fmtNum(totalSubjects)} / ${fmtNum(subLimit)}`);
        setWidth('limit-subjects-bar', Math.min(100, (totalSubjects / subLimit) * 100));
    }
}

// -----------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------

function setText(id, text) {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
}

function setWidth(id, pct) {
    const el = document.getElementById(id);
    if (el) el.style.width = pct + '%';
}

function fmtNum(n) {
    if (n == null || isNaN(n)) return '0';
    return Math.round(n).toLocaleString();
}



// -----------------------------------------------------------------------
// Payment Methods
// -----------------------------------------------------------------------

let paymentMethodsCache = [];

export async function loadPaymentMethods() {
    const container = document.getElementById('payment-methods-list');
    if (!container) return;
    try {
        paymentMethodsCache = await billingApi('/payment-methods');
        renderPaymentMethods();
    } catch (e) {
        container.innerHTML = `<div class="py-8 text-center text-red-400 text-sm">${esc(e.message)}</div>`;
    }
}

function renderPaymentMethods() {
    const container = document.getElementById('payment-methods-list');
    if (!container) return;

    if (!paymentMethodsCache.length) {
        container.innerHTML = `
            <div class="flex flex-col items-center gap-3 py-8">
                <svg class="w-12 h-12 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
                        d="M3 10h18M7 15h1m4 0h1m-7 4h12a3 3 0 003-3V8a3 3 0 00-3-3H6a3 3 0 00-3 3v8a3 3 0 003 3z" />
                </svg>
                <p class="text-sm text-gray-400">No payment methods on file</p>
                <p class="text-xs text-gray-400">Add a payment method to enable paid plans and automatic billing.</p>
            </div>`;
        return;
    }

    container.innerHTML = paymentMethodsCache.map(pm => `
        <div class="flex items-center justify-between py-3 ${pm !== paymentMethodsCache[paymentMethodsCache.length - 1] ? 'border-b border-gray-100' : ''}">
            <div class="flex items-center gap-3">
                <div class="w-10 h-7 bg-gray-100 rounded flex items-center justify-center text-xs font-bold text-gray-500 uppercase">
                    ${esc(pm.cardBrand || pm.type || '?')}
                </div>
                <div>
                    <p class="text-sm font-medium text-gray-900">
                        ${esc(pm.cardBrand || 'Card')} &bull;&bull;&bull;&bull; ${esc(pm.cardLast4 || '????')}
                        ${pm.isDefault ? '<span class="ml-2 text-xs font-medium text-cv-500">Default</span>' : ''}
                    </p>
                    <p class="text-xs text-gray-400">Expires ${pm.cardExpMonth}/${pm.cardExpYear}</p>
                </div>
            </div>
            <div class="flex items-center gap-2">
                ${!pm.isDefault ? `<button onclick="CveilWeb.billing.setDefault('${esc(pm.externalPaymentMethodId)}')"
                    class="text-xs text-gray-400 hover:text-cv-500 transition-colors">Set Default</button>` : ''}
                <button onclick="CveilWeb.billing.removePaymentMethod('${esc(pm.externalPaymentMethodId)}')"
                    class="text-xs text-gray-400 hover:text-red-400 transition-colors">Remove</button>
            </div>
        </div>
    `).join('');
}

export async function setDefault(paymentMethodId) {
    try {
        await billingApi(`/payment-methods/${paymentMethodId}/default`, { method: 'PUT' });
        await loadPaymentMethods();
    } catch (e) {
        alert('Failed to set default: ' + e.message);
    }
}

export async function removePaymentMethod(paymentMethodId) {
    if (!confirm('Remove this payment method?')) return;
    try {
        await billingApi(`/payment-methods/${paymentMethodId}`, { method: 'DELETE' });
        await loadPaymentMethods();
    } catch (e) {
        alert('Failed to remove: ' + e.message);
    }
}

// -----------------------------------------------------------------------
// Add Payment Method (Stripe Elements)
// -----------------------------------------------------------------------

let stripe = null;
let cardElement = null;

export async function openAddPaymentMethod() {
    const panelTitle = document.getElementById('panel-title');
    const panelBody = document.getElementById('panel-body');
    if (!panelTitle || !panelBody) return;

    panelTitle.textContent = 'Add Payment Method';
    panelBody.innerHTML = `
        <form id="add-pm-form" class="space-y-6">
            <div>
                <label class="block text-sm font-medium text-gray-700 mb-2">Card Details</label>
                <div id="card-element" class="border border-gray-300 rounded-md p-3 bg-white"></div>
                <div id="card-errors" class="text-sm text-red-400 mt-2 hidden"></div>
            </div>
            <div class="flex items-center gap-2">
                <input type="checkbox" id="pm-set-default" checked
                    class="rounded border-gray-300 text-cv-500 focus:ring-cv-500">
                <label for="pm-set-default" class="text-sm text-gray-600">Set as default payment method</label>
            </div>
            <button type="submit" id="pm-submit-btn"
                class="w-full px-4 py-2.5 text-sm font-medium text-white bg-cv-500 rounded hover:bg-cv-600 transition-colors">
                Add Card
            </button>
            <span id="pm-msg" class="hidden"></span>
        </form>`;

    activatePanel();

    // Clean up previous Stripe Element if re-opening
    if (cardElement) {
        cardElement.unmount();
        cardElement.destroy();
        cardElement = null;
    }

    if (!stripe) {
        try {
            const config = await billingApi('/stripe-config');
            stripe = Stripe(config.publishableKey);
        } catch (e) {
            document.getElementById('card-errors').textContent = 'Failed to load Stripe: ' + e.message;
            document.getElementById('card-errors').classList.remove('hidden');
            return;
        }
    }

    const elements = stripe.elements();
    cardElement = elements.create('card', {
        style: {
            base: {
                fontSize: '14px',
                color: '#1f2937',
                '::placeholder': { color: '#9ca3af' }
            }
        }
    });
    cardElement.mount('#card-element');

    cardElement.on('change', (event) => {
        const errEl = document.getElementById('card-errors');
        if (event.error) {
            errEl.textContent = event.error.message;
            errEl.classList.remove('hidden');
        } else {
            errEl.classList.add('hidden');
        }
    });

    document.getElementById('add-pm-form').onsubmit = handleAddCard;
}

async function handleAddCard(e) {
    e.preventDefault();
    const btn = document.getElementById('pm-submit-btn');
    const msg = document.getElementById('pm-msg');
    btn.disabled = true;
    btn.textContent = 'Processing\u2026';

    try {
        const intent = await billingApi('/setup-intent', { method: 'POST' });

        const { setupIntent, error } = await stripe.confirmCardSetup(intent.clientSecret, {
            payment_method: { card: cardElement }
        });

        if (error) {
            showMsg(msg, error.message, true);
            btn.disabled = false;
            btn.textContent = 'Add Card';
            return;
        }

        const makeDefault = document.getElementById('pm-set-default').checked;
        await billingApi(`/payment-methods/${setupIntent.payment_method}/attach?setDefault=${makeDefault}`, {
            method: 'POST'
        });

        closePanel();
        await loadPaymentMethods();
    } catch (err) {
        showMsg(msg, err.message, true);
    } finally {
        btn.disabled = false;
        btn.textContent = 'Add Card';
    }
}

// -----------------------------------------------------------------------
// Invoices
// -----------------------------------------------------------------------

let invoicesCache = [];
let invoicePage = 0;

export async function loadInvoices() {
    const tb = document.getElementById('invoices-table');
    if (!tb) return;
    try {
        invoicesCache = await billingApi(`/invoices?page=${invoicePage}&pageSize=20`);
        renderInvoices();
    } catch (e) {
        tb.innerHTML = `<tr><td colspan="5" class="py-8 text-center text-red-400 text-sm">${esc(e.message)}</td></tr>`;
    }
}

function renderInvoices() {
    const tb = document.getElementById('invoices-table');
    if (!tb) return;

    if (!invoicesCache.length) {
        tb.innerHTML = `
            <tr>
                <td colspan="5" class="py-12 text-center">
                    <div class="flex flex-col items-center gap-3">
                        <svg class="w-12 h-12 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
                                d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                        </svg>
                        <p class="text-sm text-gray-400">No invoices yet</p>
                        <p class="text-xs text-gray-400">Invoices will appear here once billing is active.</p>
                    </div>
                </td>
            </tr>`;
        return;
    }

    tb.innerHTML = invoicesCache.map(inv => {
        const date = inv.createdAt ? new Date(inv.createdAt).toLocaleDateString() : '\u2014';
        const amount = formatCurrency(inv.total, inv.currency);
        const statusBadge = invoiceStatusBadge(inv.status);
        const pdfLink = inv.invoicePdfUrl
            ? `<a href="${esc(inv.invoicePdfUrl)}" target="_blank" rel="noopener" class="text-xs text-cv-500 hover:underline">PDF</a>`
            : '';
        const viewLink = inv.hostedInvoiceUrl
            ? `<a href="${esc(inv.hostedInvoiceUrl)}" target="_blank" rel="noopener" class="text-xs text-gray-400 hover:text-cv-500 ml-2">View</a>`
            : '';

        return `
            <tr class="border-b border-gray-50 hover:bg-gray-50/50">
                <td class="py-3 text-sm text-gray-600">${date}</td>
                <td class="py-3 text-sm text-gray-900">Invoice ${esc(inv.externalInvoiceId || '')}</td>
                <td class="py-3 text-sm text-gray-900 text-right font-medium">${amount}</td>
                <td class="py-3 text-center">${statusBadge}</td>
                <td class="py-3 text-right">${pdfLink}${viewLink}</td>
            </tr>`;
    }).join('');
}

function invoiceStatusBadge(status) {
    const colors = {
        paid: 'bg-green-50 text-green-700 border-green-200',
        open: 'bg-blue-50 text-blue-700 border-blue-200',
        draft: 'bg-gray-50 text-gray-600 border-gray-200',
        void: 'bg-gray-50 text-gray-400 border-gray-200',
        uncollectible: 'bg-red-50 text-red-700 border-red-200',
    };
    const cls = colors[status] || colors.draft;
    return `<span class="inline-flex px-2 py-0.5 rounded text-xs font-medium border ${cls}">${esc(status || 'unknown')}</span>`;
}

function formatCurrency(amountCents, currency = 'usd') {
    const amount = (amountCents || 0) / 100;
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: currency.toUpperCase() }).format(amount);
}

export async function loadMoreInvoices() {
    invoicePage++;
    try {
        const more = await billingApi(`/invoices?page=${invoicePage}&pageSize=20`);
        if (more.length) {
            invoicesCache = invoicesCache.concat(more);
            renderInvoices();
        } else {
            invoicePage--; // no more pages
        }
    } catch (e) {
        invoicePage--;
        console.warn('Failed to load more invoices:', e.message);
    }
}

// -----------------------------------------------------------------------
// Plan & Subscription Management
// -----------------------------------------------------------------------

let subscriptionCache = null;
let plansCache = [];

export async function loadPlans() {
    try {
        const [sub, plans] = await Promise.all([
            billingApi('/subscription'),
            billingApi('/plans'),
        ]);
        subscriptionCache = sub;
        plansCache = plans;
        renderCurrentPlan();
        renderPlansGrid();
    } catch (e) {
        console.warn('Failed to load plans:', e.message);
    }
}

function renderCurrentPlan() {
    const name = document.getElementById('current-plan-name');
    const desc = document.getElementById('current-plan-desc');
    const badge = document.getElementById('plan-status-badge');
    const cancelBtn = document.getElementById('cancel-btn-container');
    if (!name || !subscriptionCache) return;

    const tier = subscriptionCache.planTier || 'free';
    const plan = plansCache.find(p => p.tier === tier);

    name.textContent = plan ? plan.displayName : tier.charAt(0).toUpperCase() + tier.slice(1);

    const descriptions = {
        free: 'For individual developers and early-stage startups validating the API and integrating basic consent flows.',
        pro: 'For growing businesses that need to prove GDPR/CCPA compliance and handle moderate traffic.',
        business: 'For mid-market companies with complex compliance needs across multiple regions and high throughput.',
        enterprise: 'For large enterprises requiring massive scale, strict data isolation, or on-premise/VPC deployments.',
    };
    desc.textContent = plan?.description || descriptions[tier] || '';

    const status = subscriptionCache.status || 'none';
    if (status === 'active') {
        badge.className = 'inline-flex items-center gap-1.5 px-2.5 py-1 rounded text-xs font-medium bg-green-50 text-green-700 border border-green-200';
        badge.innerHTML = '<span class="w-1.5 h-1.5 rounded-full bg-green-500"></span>Active';
    } else if (status === 'past_due') {
        badge.className = 'inline-flex items-center gap-1.5 px-2.5 py-1 rounded text-xs font-medium bg-amber-50 text-amber-700 border border-amber-200';
        badge.innerHTML = '<span class="w-1.5 h-1.5 rounded-full bg-amber-500"></span>Past Due';
    } else {
        badge.className = 'inline-flex items-center gap-1.5 px-2.5 py-1 rounded text-xs font-medium bg-gray-50 text-gray-600 border border-gray-200';
        badge.innerHTML = '<span class="w-1.5 h-1.5 rounded-full bg-gray-400"></span>No Subscription';
    }

    if (tier !== 'free' && !plan?.contactSales && (status === 'active' || status === 'past_due')) {
        cancelBtn.classList.remove('hidden');
        if (subscriptionCache.cancelAtPeriodEnd) {
            cancelBtn.innerHTML = '<p class="text-xs text-amber-600">Subscription will cancel at end of current period.</p>';
        }
    } else {
        cancelBtn.classList.add('hidden');
    }
}

function renderPlansGrid() {
    const grid = document.getElementById('plans-grid');
    if (!grid || !plansCache.length) return;

    const currentTier = subscriptionCache?.planTier || 'free';

    grid.innerHTML = plansCache.map(plan => {
        const isCurrent = plan.tier === currentTier;
        const price = plan.contactSales
            ? '<span class="text-lg font-semibold">Custom</span>'
            : plan.priceInCents === 0
                ? 'Free'
                : `$${(plan.priceInCents / 100).toFixed(0)}<span class="text-sm font-normal text-gray-400">/mo</span>`;

        const borderClass = isCurrent ? 'border-cv-500 ring-1 ring-cv-500' : 'border-gray-200';

        const featureItems = (plan.features || []).map(f => `
            <li class="flex items-start gap-2">
                <svg class="w-4 h-4 text-cv-500 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"/></svg>
                <span>${esc(f)}</span>
            </li>`).join('');

        let actionBtn;
        if (isCurrent) {
            actionBtn = `<span class="block w-full text-center px-4 py-2 text-sm font-medium text-cv-500 border border-cv-500 rounded cursor-default">Current Plan</span>`;
        } else if (plan.contactSales) {
            actionBtn = `<a href="mailto:sales@koostack.com?subject=Enterprise%20Plan%20Inquiry"
                class="block w-full text-center px-4 py-2 text-sm font-medium text-white bg-gray-800 rounded hover:bg-gray-900 transition-colors">Contact Sales</a>`;
        } else if (plan.priceInCents === 0) {
            const hasActiveSubscription = subscriptionCache
                && subscriptionCache.status !== 'none'
                && subscriptionCache.planTier !== 'free';
            if (hasActiveSubscription) {
                actionBtn = `<button onclick="CveilWeb.billing.cancelSubscription()"
                    class="w-full px-4 py-2 text-sm font-medium text-gray-600 border border-gray-300 rounded hover:border-gray-400 transition-colors">Downgrade</button>`;
            } else {
                actionBtn = `<span class="block w-full text-center px-4 py-2 text-sm font-medium text-gray-400 cursor-default">&nbsp;</span>`;
            }
        } else {
            const tierOrder = { free: 0, pro: 1, business: 2, enterprise: 3 };
            const currentRank = tierOrder[currentTier] ?? 0;
            const targetRank = tierOrder[plan.tier] ?? 0;
            const label = targetRank > currentRank ? 'Upgrade' : 'Change Plan';
            actionBtn = `<button onclick="CveilWeb.billing.changePlan('${esc(plan.tier)}')"
                class="w-full px-4 py-2 text-sm font-medium text-white bg-cv-500 rounded hover:bg-cv-600 transition-colors">${label}</button>`;
        }

        return `
            <div class="bg-white shadow-sm border ${borderClass} rounded-md p-6 flex flex-col">
                <h4 class="text-lg font-semibold text-gray-900 mb-1">${esc(plan.displayName)}</h4>
                <p class="text-xs text-gray-400 mb-3">${esc(plan.description || '')}</p>
                <p class="text-3xl font-bold text-gray-900 mb-4">${price}</p>
                <ul class="text-sm text-gray-500 space-y-2 mb-6 flex-1">
                    ${featureItems}
                </ul>
                ${actionBtn}
            </div>`;
    }).join('');
}

export async function changePlan(tier) {
    const plan = plansCache.find(p => p.tier === tier);
    if (!plan) return;

    // Check for payment method before attempting a paid subscription
    if (plan.priceInCents > 0 && (!paymentMethodsCache || !paymentMethodsCache.length)) {
        alert('Please add a payment method before upgrading to a paid plan.');
        if (typeof window.showSection === 'function') window.showSection('payment');
        return;
    }

    const current = subscriptionCache?.planTier || 'free';
    const action = current === 'free' ? 'subscribe to' : 'change your plan to';
    if (!confirm(`Are you sure you want to ${action} ${plan.displayName} ($${(plan.priceInCents / 100).toFixed(0)}/mo)?`)) return;

    try {
        if (current === 'free') {
            await billingApi('/subscription', { method: 'POST', body: JSON.stringify({ planTier: tier }) });
        } else {
            await billingApi('/subscription', { method: 'PUT', body: JSON.stringify({ planTier: tier }) });
        }
        await loadPlans();
    } catch (e) {
        alert('Failed to change plan: ' + e.message);
    }
}

export async function cancelSubscription() {
    if (!confirm('Are you sure you want to cancel your subscription? Your plan will remain active until the end of the current billing period.')) return;
    try {
        await billingApi('/subscription', { method: 'DELETE' });
        await loadPlans();
    } catch (e) {
        alert('Failed to cancel: ' + e.message);
    }
}

export function closePanelGlobal() {
    closePanel();
}
