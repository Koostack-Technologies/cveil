/**
 * D3.js multi-line time-series chart for billing usage metrics.
 *
 * Renders into #usage-chart with interactive legend in #chart-legend.
 * Supports toggling individual lines, tooltips, and responsive resizing.
 */

const METRIC_CONFIG = {
    consent_grants:      { label: 'Consent Grants',      color: '#14b8a6' },
    consent_revocations: { label: 'Consent Revocations',  color: '#f59e0b' },
    consent_grant_all:   { label: 'Consent Grant All',    color: '#0d9488' },
    consent_revoke_all:  { label: 'Consent Revoke All',   color: '#d97706' },
    subject_created:     { label: 'Subject Created',      color: '#3b82f6' },
    subject_updated:     { label: 'Subject Updated',      color: '#6366f1' },
    subject_deleted:     { label: 'Subject Deleted',      color: '#ef4444' },
    dsar_exports:        { label: 'DSAR Exports',         color: '#8b5cf6' },
    dsar_deletions:      { label: 'DSAR Deletions',       color: '#ec4899' },
    dsar_shred:          { label: 'DSAR Shred',           color: '#a855f7' },
};

const MARGIN = { top: 16, right: 24, bottom: 32, left: 48 };

let svg, xScale, yScale, line, chartG, tooltip;
let hiddenMetrics = new Set();
let currentData = {};

/**
 * Initialise the chart SVG inside the given container.
 * @param {string} selector  CSS selector for the chart container (e.g. '#usage-chart')
 */
export function create(selector) {
    const container = document.querySelector(selector);
    if (!container) return;

    container.innerHTML = '';

    const rect = container.getBoundingClientRect();
    const width = rect.width;
    const height = rect.height || 360;

    svg = d3.select(container)
        .append('svg')
        .attr('width', width)
        .attr('height', height)
        .attr('viewBox', `0 0 ${width} ${height}`)
        .attr('preserveAspectRatio', 'xMidYMid meet');

    chartG = svg.append('g')
        .attr('transform', `translate(${MARGIN.left},${MARGIN.top})`);

    const innerW = width - MARGIN.left - MARGIN.right;
    const innerH = height - MARGIN.top - MARGIN.bottom;

    xScale = d3.scaleTime().range([0, innerW]);
    yScale = d3.scaleLinear().range([innerH, 0]);

    // Axes groups
    chartG.append('g')
        .attr('class', 'x-axis')
        .attr('transform', `translate(0,${innerH})`);
    chartG.append('g')
        .attr('class', 'y-axis');

    // Line generator
    line = d3.line()
        .curve(d3.curveMonotoneX)
        .x(d => xScale(d.time))
        .y(d => yScale(d.value));

    // Tooltip
    tooltip = d3.select(container)
        .append('div')
        .style('position', 'absolute')
        .style('pointer-events', 'none')
        .style('background', 'rgba(26, 34, 54, 0.85)')
        .style('border', '1px solid rgba(60, 73, 71, 0.15)')
        .style('border-radius', '4px')
        .style('padding', '8px 12px')
        .style('font-size', '12px')
        .style('box-shadow', '0 20px 40px rgba(0, 0, 0, 0.4)')
        .style('backdrop-filter', 'blur(12px)')
        .style('-webkit-backdrop-filter', 'blur(12px)')
        .style('display', 'none')
        .style('z-index', '10');

    // Hover overlay
    chartG.append('rect')
        .attr('class', 'overlay')
        .attr('width', innerW)
        .attr('height', innerH)
        .attr('fill', 'none')
        .attr('pointer-events', 'all')
        .on('mousemove', onMouseMove)
        .on('mouseleave', () => tooltip.style('display', 'none'));

    // Resize observer
    const ro = new ResizeObserver(() => {
        const r = container.getBoundingClientRect();
        if (r.width > 0) {
            svg.attr('width', r.width).attr('viewBox', `0 0 ${r.width} ${height}`);
            const iw = r.width - MARGIN.left - MARGIN.right;
            xScale.range([0, iw]);
            chartG.select('.overlay').attr('width', iw);
            redraw();
        }
    });
    ro.observe(container);
}

/**
 * Update the chart with new data.
 * @param {Array<{timeBucket: string, metricName: string, metricValue: number}>} rows
 */
export function update(rows) {
    // Group data by metric name → array of {time, value}
    currentData = {};
    for (const row of rows) {
        const name = row.metricName;
        if (!METRIC_CONFIG[name]) continue;
        if (!currentData[name]) currentData[name] = [];
        currentData[name].push({
            time: new Date(row.timeBucket),
            value: row.metricValue,
        });
    }
    // Sort each series by time
    for (const arr of Object.values(currentData)) {
        arr.sort((a, b) => a.time - b.time);
    }
    redraw();
}

function redraw() {
    if (!chartG) return;

    const visibleData = {};
    for (const [name, pts] of Object.entries(currentData)) {
        if (!hiddenMetrics.has(name)) visibleData[name] = pts;
    }

    // Compute domains from visible data
    const allPts = Object.values(visibleData).flat();
    if (allPts.length === 0) {
        xScale.domain([new Date(), new Date()]);
        yScale.domain([0, 1]);
    } else {
        xScale.domain(d3.extent(allPts, d => d.time));
        yScale.domain([0, d3.max(allPts, d => d.value) * 1.1 || 1]).nice();
    }

    // Axes
    const innerW = xScale.range()[1];
    chartG.select('.x-axis')
        .transition().duration(300)
        .call(d3.axisBottom(xScale).ticks(Math.min(innerW / 80, 10)).tickSizeOuter(0))
        .selectAll('text').attr('fill', '#9ca3af').style('font-size', '11px');
    chartG.select('.y-axis')
        .transition().duration(300)
        .call(d3.axisLeft(yScale).ticks(5).tickSizeOuter(0))
        .selectAll('text').attr('fill', '#9ca3af').style('font-size', '11px');

    // Style axis lines
    chartG.selectAll('.domain').attr('stroke', 'rgba(60, 73, 71, 0.15)');
    chartG.selectAll('.tick line').attr('stroke', 'rgba(60, 73, 71, 0.1)');

    // Lines — data join
    const series = Object.entries(visibleData);
    const lines = chartG.selectAll('.metric-line').data(series, d => d[0]);

    lines.exit().transition().duration(200).style('opacity', 0).remove();

    lines.enter()
        .append('path')
        .attr('class', 'metric-line')
        .attr('fill', 'none')
        .attr('stroke-width', 2)
        .attr('stroke', d => METRIC_CONFIG[d[0]]?.color || '#999')
        .attr('d', d => line(d[1]))
        .style('opacity', 0)
        .transition().duration(400).style('opacity', 1);

    lines.transition().duration(300)
        .attr('d', d => line(d[1]))
        .attr('stroke', d => METRIC_CONFIG[d[0]]?.color || '#999');
}

function onMouseMove(event) {
    if (!xScale || !currentData) return;
    const [mx] = d3.pointer(event);
    const date = xScale.invert(mx);

    let html = `<div style="font-weight:600;margin-bottom:4px;color:#e8eaed">${d3.timeFormat('%b %d, %Y %H:%M')(date)}</div>`;
    let hasData = false;

    for (const [name, pts] of Object.entries(currentData)) {
        if (hiddenMetrics.has(name) || !pts.length) continue;
        const bisect = d3.bisector(d => d.time).left;
        let i = bisect(pts, date);
        if (i >= pts.length) i = pts.length - 1;
        if (i > 0) {
            const d0 = pts[i - 1], d1 = pts[i];
            if (date - d0.time < d1.time - date) i = i - 1;
        }
        const cfg = METRIC_CONFIG[name];
        html += `<div style="display:flex;align-items:center;gap:6px">
            <span style="width:8px;height:8px;border-radius:50%;background:${cfg.color};display:inline-block"></span>
            <span style="color:#9ca3af">${cfg.label}:</span>
            <span style="font-weight:600;color:#e8eaed">${Math.round(pts[i].value).toLocaleString()}</span>
        </div>`;
        hasData = true;
    }

    if (!hasData) { tooltip.style('display', 'none'); return; }

    const container = svg.node().parentElement.getBoundingClientRect();
    const svgRect = svg.node().getBoundingClientRect();
    const px = event.clientX - container.left;
    const py = event.clientY - container.top;

    tooltip.html(html)
        .style('display', 'block')
        .style('left', `${Math.min(px + 12, container.width - 180)}px`)
        .style('top', `${py - 10}px`);
}

/**
 * Build the interactive legend into the given container.
 * @param {string} selector  CSS selector for legend container
 */
export function buildLegend(selector) {
    const el = document.querySelector(selector);
    if (!el) return;
    el.innerHTML = '';

    for (const [name, cfg] of Object.entries(METRIC_CONFIG)) {
        const item = document.createElement('button');
        item.className = 'flex items-center gap-1.5 text-xs transition-opacity cursor-pointer';
        item.dataset.metric = name;
        item.innerHTML = `<span style="width:10px;height:10px;border-radius:50%;background:${cfg.color};display:inline-block"></span>
            <span class="text-on-surface-muted">${cfg.label}</span>`;
        item.onclick = () => toggleMetric(name);
        el.appendChild(item);
    }
}

function toggleMetric(name) {
    if (hiddenMetrics.has(name)) {
        hiddenMetrics.delete(name);
    } else {
        hiddenMetrics.add(name);
    }
    updateLegendStyles();
    redraw();
}

function updateLegendStyles() {
    document.querySelectorAll('#chart-legend button').forEach(btn => {
        const metric = btn.dataset.metric;
        btn.style.opacity = hiddenMetrics.has(metric) ? '0.3' : '1';
    });
}

export function showAll() {
    hiddenMetrics.clear();
    updateLegendStyles();
    redraw();
}

export function hideAll() {
    for (const name of Object.keys(METRIC_CONFIG)) hiddenMetrics.add(name);
    updateLegendStyles();
    redraw();
}

export { METRIC_CONFIG };
