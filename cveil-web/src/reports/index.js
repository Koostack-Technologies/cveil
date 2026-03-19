import { createApi } from '../shared/index.js';

const api = createApi('/api/v1/reports');

let auditOffset = 0;
const auditLimit = 50;

export function init() {
    loadConsentSummary();
    loadConsentActivity();
    loadGovernanceSnapshot();
    loadAuditLog();
}

// -- Consent Summary (D3 bar chart) ----------------------------------------

export async function loadConsentSummary() {
    const el = document.getElementById('consent-summary-chart');
    if (!el) return;
    try {
        const data = await api('/consent-summary');
        renderConsentSummaryChart(el, data.purposes || []);
    } catch (e) {
        el.textContent = 'Failed to load: ' + e.message;
    }
}

function renderConsentSummaryChart(container, purposes) {
    container.innerHTML = '';
    if (!purposes.length) {
        container.innerHTML = '<p class="text-on-surface-muted text-sm">No purposes configured yet.</p>';
        return;
    }
    const d3 = window.d3;
    if (!d3) { container.innerHTML = '<p class="text-on-surface-muted text-sm">D3.js not loaded.</p>'; return; }

    const margin = { top: 20, right: 20, bottom: 60, left: 50 };
    const width = container.clientWidth - margin.left - margin.right;
    const height = 220 - margin.top - margin.bottom;

    const svg = d3.select(container).append('svg')
        .attr('width', width + margin.left + margin.right)
        .attr('height', height + margin.top + margin.bottom)
        .append('g').attr('transform', `translate(${margin.left},${margin.top})`);

    const keys = ['active', 'revoked', 'expired'];
    const colors = { active: '#10b981', revoked: '#ef4444', expired: '#f59e0b' };

    const x0 = d3.scaleBand().domain(purposes.map(d => d.purposeName)).range([0, width]).padding(0.2);
    const x1 = d3.scaleBand().domain(keys).range([0, x0.bandwidth()]).padding(0.05);
    const y = d3.scaleLinear().domain([0, d3.max(purposes, d => Math.max(d.active, d.revoked, d.expired)) || 1]).nice().range([height, 0]);

    svg.append('g').attr('transform', `translate(0,${height})`).call(d3.axisBottom(x0))
        .selectAll('text').attr('transform', 'rotate(-30)').style('text-anchor', 'end').style('font-size', '10px');
    svg.append('g').call(d3.axisLeft(y).ticks(5));

    purposes.forEach(d => {
        keys.forEach(key => {
            svg.append('rect')
                .attr('x', x0(d.purposeName) + x1(key))
                .attr('y', y(d[key]))
                .attr('width', x1.bandwidth())
                .attr('height', height - y(d[key]))
                .attr('fill', colors[key]);
        });
    });

    // Legend
    const legend = svg.append('g').attr('transform', `translate(${width - 150}, -10)`);
    keys.forEach((key, i) => {
        legend.append('rect').attr('x', i * 55).attr('width', 10).attr('height', 10).attr('fill', colors[key]);
        legend.append('text').attr('x', i * 55 + 14).attr('y', 9).text(key).style('font-size', '10px').attr('fill', '#9ca3af');
    });
}

// -- Consent Activity (D3 line chart) --------------------------------------

export async function loadConsentActivity() {
    const el = document.getElementById('consent-activity-chart');
    if (!el) return;
    const range = document.getElementById('activity-range')?.value || '30d';
    const days = range === '7d' ? 7 : range === '90d' ? 90 : 30;
    const from = new Date(Date.now() - days * 86400000).toISOString();

    try {
        const data = await api(`/consent-activity?from=${from}&interval=1d`);
        renderActivityChart(el, data.data || []);
    } catch (e) {
        el.textContent = 'Failed to load: ' + e.message;
    }
}

function renderActivityChart(container, data) {
    container.innerHTML = '';
    if (!data.length) {
        container.innerHTML = '<p class="text-on-surface-muted text-sm">No activity data for this period.</p>';
        return;
    }
    const d3 = window.d3;
    if (!d3) return;

    const margin = { top: 20, right: 20, bottom: 30, left: 50 };
    const width = container.clientWidth - margin.left - margin.right;
    const height = 220 - margin.top - margin.bottom;

    const svg = d3.select(container).append('svg')
        .attr('width', width + margin.left + margin.right)
        .attr('height', height + margin.top + margin.bottom)
        .append('g').attr('transform', `translate(${margin.left},${margin.top})`);

    const parseDate = d => new Date(d.bucket);
    const x = d3.scaleTime().domain(d3.extent(data, parseDate)).range([0, width]);
    const y = d3.scaleLinear().domain([0, d3.max(data, d => Math.max(d.grants, d.revocations)) || 1]).nice().range([height, 0]);

    svg.append('g').attr('transform', `translate(0,${height})`).call(d3.axisBottom(x).ticks(6).tickFormat(d3.timeFormat('%b %d')));
    svg.append('g').call(d3.axisLeft(y).ticks(5));

    // Grants line
    svg.append('path').datum(data)
        .attr('fill', 'none').attr('stroke', '#10b981').attr('stroke-width', 2)
        .attr('d', d3.line().x(d => x(parseDate(d))).y(d => y(d.grants)));
    // Revocations line
    svg.append('path').datum(data)
        .attr('fill', 'none').attr('stroke', '#ef4444').attr('stroke-width', 2)
        .attr('d', d3.line().x(d => x(parseDate(d))).y(d => y(d.revocations)));

    // Legend
    const legend = svg.append('g').attr('transform', `translate(${width - 130}, -10)`);
    [['Grants', '#10b981'], ['Revocations', '#ef4444']].forEach(([label, color], i) => {
        legend.append('rect').attr('x', i * 80).attr('width', 10).attr('height', 10).attr('fill', color);
        legend.append('text').attr('x', i * 80 + 14).attr('y', 9).text(label).style('font-size', '10px').attr('fill', '#9ca3af');
    });
}

// -- Governance Snapshot ---------------------------------------------------

export async function loadGovernanceSnapshot() {
    try {
        const data = await api('/governance-snapshot');
        ['jurisdictions', 'regulations', 'purposes', 'entities', 'subjects'].forEach(key => {
            const el = document.getElementById('gov-' + key);
            if (el) el.textContent = data[key] ?? '—';
        });
    } catch (_) { /* silent */ }
}

// -- Audit Log (paginated table) ------------------------------------------

export async function loadAuditLog() {
    const body = document.getElementById('audit-log-body');
    if (!body) return;
    const subject = document.getElementById('audit-subject')?.value || '';
    const params = `?limit=${auditLimit}&offset=${auditOffset}` + (subject ? `&subject=${encodeURIComponent(subject)}` : '');
    try {
        const data = await api('/audit-log' + params);
        const events = data.events || [];
        if (!events.length) {
            body.innerHTML = '<tr><td colspan="4" class="py-8 text-center text-on-surface-muted">No events found.</td></tr>';
        } else {
            body.innerHTML = events.map(e =>
                `<tr class="text-xs">
                    <td class="py-2 pr-4 text-on-surface-muted">${new Date(e.createdAt).toLocaleString()}</td>
                    <td class="py-2 pr-4"><span class="px-1.5 py-0.5 bg-surface-container rounded-md text-on-surface font-technical">${esc(e.eventType)}</span></td>
                    <td class="py-2 pr-4 text-on-surface-muted font-technical">${esc(e.aggregateType)}/${esc(e.aggregateId?.substring(0,8) || '')}</td>
                    <td class="py-2 text-on-surface-muted">v${e.version}</td>
                </tr>`
            ).join('');
        }
        document.getElementById('audit-page-info').textContent = `Showing ${auditOffset + 1}–${auditOffset + events.length}`;
        document.getElementById('audit-prev').disabled = auditOffset === 0;
        document.getElementById('audit-next').disabled = events.length < auditLimit;
    } catch (e) {
        body.innerHTML = `<tr><td colspan="4" class="py-8 text-center text-error">${esc(e.message)}</td></tr>`;
    }
}

export function auditPrev() {
    auditOffset = Math.max(0, auditOffset - auditLimit);
    loadAuditLog();
}

export function auditNext() {
    auditOffset += auditLimit;
    loadAuditLog();
}

function esc(s) {
    if (!s) return '';
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
