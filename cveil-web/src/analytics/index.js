import { createApi } from '../shared/index.js';

const reportsApi = createApi('/api/v1/reports');
const metricsApi = createApi('/api/v1/metrics');

export function init() {
    refresh();
}

export async function refresh() {
    const period = document.getElementById('analytics-period')?.value || '30d';
    const days = period === '7d' ? 7 : period === '90d' ? 90 : 30;
    const from = new Date(Date.now() - days * 86400000).toISOString();

    await Promise.allSettled([
        loadSummaryCards(from),
        loadApiUsageChart(from),
        loadConsentFlowChart(from),
        loadConsentDistribution()
    ]);
}

// -- Summary Cards --------------------------------------------------------

async function loadSummaryCards(from) {
    try {
        const [metrics, summary] = await Promise.all([
            metricsApi(`?metric=api_requests&from=${from}&limit=500`).catch(() => null),
            reportsApi('/consent-summary').catch(() => null)
        ]);

        // API requests total
        if (metrics?.data) {
            const total = metrics.data.reduce((sum, r) => sum + (r.metricValue || 0), 0);
            setText('metric-api-requests', total.toLocaleString());
        }

        // Consent metrics from summary
        if (summary?.purposes) {
            const totalActive = summary.purposes.reduce((s, p) => s + (p.active || 0), 0);
            const totalAll = summary.purposes.reduce((s, p) => s + (p.total || 0), 0);
            const subjects = new Set(summary.purposes.map(p => p.purposeName)).size;

            setText('metric-active-subjects', subjects.toLocaleString());
            const rate = totalAll > 0 ? ((totalActive / totalAll) * 100).toFixed(1) + '%' : '—';
            setText('metric-consent-rate', rate);
        }

        // Avg latency from metrics
        if (metrics?.data) {
            const latencyRows = metrics.data.filter(r => r.metricName === 'api_latency_ms');
            if (latencyRows.length) {
                const avg = latencyRows.reduce((s, r) => s + (r.metricValue || 0), 0) / latencyRows.length;
                setText('metric-avg-latency', avg.toFixed(0) + 'ms');
            }
        }
    } catch (_) { /* silent */ }
}

// -- API Usage Line Chart -------------------------------------------------

async function loadApiUsageChart(from) {
    const el = document.getElementById('api-usage-chart');
    if (!el) return;

    try {
        const data = await metricsApi(`?metric=api_requests&from=${from}&limit=500`);
        renderLineChart(el, (data.data || []).map(r => ({
            date: new Date(r.timeBucket || r.publishedAt),
            value: r.metricValue || 0
        })), '#6366f1', 'Requests');
    } catch (e) {
        el.innerHTML = `<p class="text-gray-400 text-sm">No API usage data available.</p>`;
    }
}

// -- Consent Flow Stacked Bar Chart ---------------------------------------

async function loadConsentFlowChart(from) {
    const el = document.getElementById('consent-flow-chart');
    if (!el) return;

    try {
        const data = await reportsApi(`/consent-activity?from=${from}&interval=1d`);
        renderStackedBarChart(el, data.data || []);
    } catch (e) {
        el.innerHTML = `<p class="text-gray-400 text-sm">No consent activity data.</p>`;
    }
}

// -- Consent Distribution Donut Chart ------------------------------------

async function loadConsentDistribution() {
    const el = document.getElementById('consent-distribution-chart');
    if (!el) return;

    try {
        const data = await reportsApi('/consent-summary');
        renderDonutChart(el, (data.purposes || []).filter(p => p.total > 0));
    } catch (e) {
        el.innerHTML = `<p class="text-gray-400 text-sm">No consent data available.</p>`;
    }
}

// -- D3 Chart Renderers ---------------------------------------------------

function renderLineChart(container, data, color, label) {
    container.innerHTML = '';
    const d3 = window.d3;
    if (!d3 || !data.length) {
        container.innerHTML = '<p class="text-gray-400 text-sm text-center pt-20">No data available.</p>';
        return;
    }

    const margin = { top: 20, right: 20, bottom: 30, left: 50 };
    const width = container.clientWidth - margin.left - margin.right;
    const height = 220 - margin.top - margin.bottom;

    const svg = d3.select(container).append('svg')
        .attr('width', width + margin.left + margin.right)
        .attr('height', height + margin.top + margin.bottom)
        .append('g').attr('transform', `translate(${margin.left},${margin.top})`);

    const x = d3.scaleTime().domain(d3.extent(data, d => d.date)).range([0, width]);
    const y = d3.scaleLinear().domain([0, d3.max(data, d => d.value) || 1]).nice().range([height, 0]);

    svg.append('g').attr('transform', `translate(0,${height})`).call(d3.axisBottom(x).ticks(6).tickFormat(d3.timeFormat('%b %d')));
    svg.append('g').call(d3.axisLeft(y).ticks(5));

    // Area fill
    svg.append('path').datum(data)
        .attr('fill', color).attr('fill-opacity', 0.1)
        .attr('d', d3.area().x(d => x(d.date)).y0(height).y1(d => y(d.value)));

    // Line
    svg.append('path').datum(data)
        .attr('fill', 'none').attr('stroke', color).attr('stroke-width', 2)
        .attr('d', d3.line().x(d => x(d.date)).y(d => y(d.value)));
}

function renderStackedBarChart(container, data) {
    container.innerHTML = '';
    const d3 = window.d3;
    if (!d3 || !data.length) {
        container.innerHTML = '<p class="text-gray-400 text-sm text-center pt-20">No data available.</p>';
        return;
    }

    const margin = { top: 20, right: 20, bottom: 30, left: 50 };
    const width = container.clientWidth - margin.left - margin.right;
    const height = 220 - margin.top - margin.bottom;

    const svg = d3.select(container).append('svg')
        .attr('width', width + margin.left + margin.right)
        .attr('height', height + margin.top + margin.bottom)
        .append('g').attr('transform', `translate(${margin.left},${margin.top})`);

    const x = d3.scaleBand().domain(data.map(d => d.bucket)).range([0, width]).padding(0.3);
    const y = d3.scaleLinear().domain([0, d3.max(data, d => d.grants + d.revocations) || 1]).nice().range([height, 0]);

    svg.append('g').attr('transform', `translate(0,${height})`).call(
        d3.axisBottom(x).tickFormat(d => { const dt = new Date(d); return (dt.getMonth()+1)+'/'+dt.getDate(); }).tickValues(x.domain().filter((_, i) => i % Math.ceil(data.length / 8) === 0))
    );
    svg.append('g').call(d3.axisLeft(y).ticks(5));

    // Grants bars
    svg.selectAll('.bar-grant').data(data).enter().append('rect')
        .attr('x', d => x(d.bucket)).attr('y', d => y(d.grants + d.revocations))
        .attr('width', x.bandwidth()).attr('height', d => height - y(d.grants))
        .attr('fill', '#10b981');

    // Revocations stacked on top
    svg.selectAll('.bar-revoke').data(data).enter().append('rect')
        .attr('x', d => x(d.bucket)).attr('y', d => y(d.revocations))
        .attr('width', x.bandwidth()).attr('height', d => height - y(d.revocations))
        .attr('fill', '#ef4444').attr('transform', d => `translate(0,${y(d.grants + d.revocations) - y(d.grants)})`);

    // Legend
    const legend = svg.append('g').attr('transform', `translate(${width - 130}, -10)`);
    [['Grants', '#10b981'], ['Revocations', '#ef4444']].forEach(([lbl, clr], i) => {
        legend.append('rect').attr('x', i * 80).attr('width', 10).attr('height', 10).attr('fill', clr);
        legend.append('text').attr('x', i * 80 + 14).attr('y', 9).text(lbl).style('font-size', '10px').attr('fill', '#666');
    });
}

function renderDonutChart(container, purposes) {
    container.innerHTML = '';
    const d3 = window.d3;
    if (!d3 || !purposes.length) {
        container.innerHTML = '<p class="text-gray-400 text-sm text-center pt-20">No consent data yet.</p>';
        return;
    }

    const width = container.clientWidth;
    const height = 260;
    const radius = Math.min(width, height) / 2 - 30;

    const svg = d3.select(container).append('svg')
        .attr('width', width).attr('height', height)
        .append('g').attr('transform', `translate(${width / 2},${height / 2})`);

    const color = d3.scaleOrdinal(d3.schemeTableau10);
    const pie = d3.pie().value(d => d.total).sort(null);
    const arc = d3.arc().innerRadius(radius * 0.5).outerRadius(radius);

    svg.selectAll('path').data(pie(purposes)).enter().append('path')
        .attr('d', arc)
        .attr('fill', (_, i) => color(i))
        .attr('stroke', '#fff').attr('stroke-width', 2);

    // Labels
    const labelArc = d3.arc().innerRadius(radius * 0.8).outerRadius(radius * 0.8);
    svg.selectAll('text').data(pie(purposes)).enter().append('text')
        .attr('transform', d => `translate(${labelArc.centroid(d)})`)
        .attr('text-anchor', 'middle')
        .style('font-size', '10px').attr('fill', '#333')
        .text(d => d.data.total > 0 ? d.data.purposeName : '');
}

// -- Helpers --------------------------------------------------------------

function setText(id, text) {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
}
