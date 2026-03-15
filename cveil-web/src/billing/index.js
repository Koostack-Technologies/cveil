/**
 * Billing page orchestrator — fetches metrics data, populates summary cards,
 * wires the chart + legend, time range controls, and top endpoints table.
 */
import * as chart from './usage-chart.js';

const API_BASE = '/api/v1/metrics';
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
            <td class="py-2.5 font-mono text-xs text-gray-700">${escHtml(r.path)}</td>
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

    const reqLimit = 10000;
    const subLimit = 1000;

    setText('limit-requests', `${fmtNum(totalRequests)} / ${fmtNum(reqLimit)}`);
    setWidth('limit-requests-bar', Math.min(100, (totalRequests / reqLimit) * 100));

    setText('limit-subjects', `${fmtNum(totalSubjects)} / ${fmtNum(subLimit)}`);
    setWidth('limit-subjects-bar', Math.min(100, (totalSubjects / subLimit) * 100));
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

function escHtml(s) {
    const d = document.createElement('div');
    d.textContent = s;
    return d.innerHTML;
}
