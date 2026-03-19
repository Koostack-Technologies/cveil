import {
 esc, showTab as _showTab, createApi, confirmDelete, closeDeleteModal, initDeleteModal,
 activatePanel, closePanel, showMsg, fmtTime, ready,
 retryUntilChanged, setTableLoading,
} from '../shared/index.js';

import {
 initPlayground, evaluateAndMask, loadSampleTemplate, copyMaskedOutput,
} from './playground.js';

// ── Graph Explorer State ───────────────────────────────────────
const graphApi = createApi('/api/v1/consent');
const NODE_COLORS = {
 purpose: '#14b8a6',
 regulation: '#3b82f6',
 jurisdiction: '#f59e0b',
 field: '#a855f7',
 subject: '#ec4899',
};

let graphLoaded = false;
let graphMode = 'force';
let graphData = { nodes: [], edges: [] };
let graphCursor = null;
let graphHasMore = false;
let simulation = null;

// ── Ledger State ───────────────────────────────────────────────
const SSE_URL = '/api/v1/consent/events';
let eventSource = null;
let sseInitialized = false;
let ledgerPaused = false;
let ledgerEvents = [];
const MAX_EVENTS = 500;

const ACTION_STYLES = {
 GRANTED: { badge: 'bg-secondary-container text-primary border border-primary/20', dot: 'bg-primary', label: 'Granted' },
 REVOKED: { badge: 'bg-error-container text-error border border-error/20', dot: 'bg-error', label: 'Revoked' },
 ALL_REVOKED: { badge: 'bg-error-container text-error border border-error/20', dot: 'bg-error', label: 'All Revoked' },
 CONSENT_ENTRY_CHANGED: { badge: 'bg-warning-container text-warning border border-warning/20', dot: 'bg-warning', label: 'Changed' },
};

// ── Subjects State ─────────────────────────────────────────────
const subjectsApi = createApi('/api/v1/subjects');
let subjectsCache = [];
let subjectsLoaded = false;

async function fetchSubjectsList() {
 return subjectsApi('');
}

async function reloadSubjects() {
 setTableLoading('subjects-table', true);
 subjectsCache = await retryUntilChanged(fetchSubjectsList, subjectsCache);
 subjectsLoaded = true;
 renderSubjects();
 setTableLoading('subjects-table', false);
}
let currentSubject = null;

// ── Tab switching (consent-specific hooks) ─────────────────────
function showTab(name) {
 _showTab(name);
 if (name === 'graph' && !graphLoaded) loadGraph();
 if (name === 'ledger') initSSE();
 if (name === 'subjects' && !subjectsLoaded) loadSubjects();
 if (name === 'playground') initPlayground();
}

// ── Helpers ────────────────────────────────────────────────────
function getActionStyle(action) {
 if (action === 'GRANTED') return ACTION_STYLES.GRANTED;
 if (action === 'REVOKED' || action === 'ALL_REVOKED') return ACTION_STYLES.REVOKED;
 return ACTION_STYLES.CONSENT_ENTRY_CHANGED;
}

// ================================================================
// GRAPH EXPLORER
// ================================================================
function setGraphMode(mode) {
 graphMode = mode;
 document.getElementById('graph-mode-force').className = 'px-3 py-1.5 text-xs font-medium rounded-md transition-colors ' +
 (mode === 'force' ? 'bg-primary text-white' : 'text-on-surface-muted hover:text-white');
 document.getElementById('graph-mode-tree').className = 'px-3 py-1.5 text-xs font-medium rounded-md transition-colors ' +
 (mode === 'tree' ? 'bg-primary text-white' : 'text-on-surface-muted hover:text-white');
 renderGraph();
}

async function loadGraph() {
 const search = document.getElementById('graph-search').value.trim();
 const rootType = document.getElementById('graph-root-type').value;

 const params = new URLSearchParams();
 params.set('limit', '200');
 if (search && rootType) { params.set('rootType', rootType); params.set('rootName', search); params.set('depth', '3'); }
 else if (search) { params.set('rootName', search); params.set('depth', '3'); }

 document.getElementById('graph-loading').classList.remove('hidden');
 document.getElementById('graph-empty').classList.add('hidden');

 try {
 const data = await graphApi(`/graph?${params}`);
 graphData = data;
 graphCursor = data.pageInfo?.nextCursor || null;
 graphHasMore = data.pageInfo?.hasMore || false;
 graphLoaded = true;

 if (!data.nodes || data.nodes.length === 0) {
 document.getElementById('graph-loading').classList.add('hidden');
 document.getElementById('graph-empty').classList.remove('hidden');
 document.getElementById('graph-pagination').classList.add('hidden');
 return;
 }

 renderGraph();
 updateGraphPagination();
 document.getElementById('graph-loading').classList.add('hidden');
 } catch (e) {
 document.getElementById('graph-loading').innerHTML =
 `<span class="text-error text-sm">${esc(e.message)}</span>`;
 }
}

async function loadMoreGraph() {
 if (!graphCursor) return;
 const params = new URLSearchParams();
 params.set('limit', '200');
 params.set('cursor', graphCursor);

 try {
 const data = await graphApi(`/graph?${params}`);

 const existingNodes = new Set(graphData.nodes.map(n => n.type + ':' + n.name));
 data.nodes.forEach(n => { if (!existingNodes.has(n.type + ':' + n.name)) graphData.nodes.push(n); });
 const existingEdges = new Set(graphData.edges.map(e => e.sourceType + ':' + e.sourceName + '->' + e.targetType + ':' + e.targetName));
 data.edges.forEach(e => { if (!existingEdges.has(e.sourceType + ':' + e.sourceName + '->' + e.targetType + ':' + e.targetName)) graphData.edges.push(e); });

 graphCursor = data.pageInfo?.nextCursor || null;
 graphHasMore = data.pageInfo?.hasMore || false;

 renderGraph();
 updateGraphPagination();
 } catch (e) {
 const stats = document.getElementById('graph-stats');
 if (stats) stats.textContent += ' \u2014 failed to load more';
 }
}

function resetGraph() {
 document.getElementById('graph-search').value = '';
 document.getElementById('graph-root-type').value = '';
 graphCursor = null;
 loadGraph();
}

function updateGraphPagination() {
 const pg = document.getElementById('graph-pagination');
 const stats = document.getElementById('graph-stats');
 const pi = graphData.pageInfo || {};
 stats.textContent = `${graphData.nodes.length} nodes, ${graphData.edges.length} edges` +
 (pi.totalNodes ? ` (of ${pi.totalNodes} total)` : '');
 pg.classList.toggle('hidden', !graphHasMore);
}

function renderGraph() {
 if (graphMode === 'force') renderForceGraph();
 else renderTreeGraph();
}

function renderForceGraph() {
 const d3 = window.d3;
 const svg = d3.select('#graph-svg');
 svg.selectAll('*').remove();
 const container = document.getElementById('graph-container');
 const width = container.clientWidth;
 const height = container.clientHeight;
 svg.attr('viewBox', [0, 0, width, height]);

 const nodeMap = new Map();
 graphData.nodes.forEach((n, i) => {
 nodeMap.set(n.type + ':' + n.name, i);
 });

 const nodes = graphData.nodes.map(n => ({
 id: n.type + ':' + n.name,
 type: n.type,
 name: n.name,
 properties: n.properties || {},
 }));

 const links = graphData.edges.filter(e =>
 nodeMap.has(e.sourceType + ':' + e.sourceName) && nodeMap.has(e.targetType + ':' + e.targetName)
 ).map(e => ({
 source: e.sourceType + ':' + e.sourceName,
 target: e.targetType + ':' + e.targetName,
 properties: e.properties || {},
 }));

 const g = svg.append('g');

 svg.call(d3.zoom().scaleExtent([0.1, 4]).on('zoom', (event) => {
 g.attr('transform', event.transform);
 }));

 const link = g.append('g').selectAll('line')
 .data(links).join('line')
 .attr('stroke', '#374151').attr('stroke-width', 1).attr('stroke-opacity', 0.6);

 const node = g.append('g').selectAll('circle')
 .data(nodes).join('circle')
 .attr('r', d => d.type === 'subject' ? 6 : 8)
 .attr('fill', d => NODE_COLORS[d.type] || '#6b7280')
 .attr('stroke', '#111827').attr('stroke-width', 1.5)
 .style('cursor', 'pointer')
 .call(d3.drag()
 .on('start', (e, d) => { if (!e.active) simulation.alphaTarget(0.3).restart(); d.fx = d.x; d.fy = d.y; })
 .on('drag', (e, d) => { d.fx = e.x; d.fy = e.y; })
 .on('end', (e, d) => { if (!e.active) simulation.alphaTarget(0); d.fx = null; d.fy = null; }));

 const label = g.append('g').selectAll('text')
 .data(nodes).join('text')
 .text(d => d.name.length > 20 ? d.name.slice(0, 18) + '…' : d.name)
 .attr('font-size', 10).attr('fill', '#9ca3af')
 .attr('dx', 12).attr('dy', 4)
 .style('pointer-events', 'none');

 node.on('mouseover', (event, d) => {
 const tt = document.getElementById('graph-tooltip');
 document.getElementById('tooltip-type').textContent = d.type;
 document.getElementById('tooltip-name').textContent = d.name;
 const props = document.getElementById('tooltip-props');
 props.innerHTML = Object.entries(d.properties).map(([k, v]) =>
 `<div class="flex justify-between gap-4"><span class="text-on-surface-muted">${esc(k)}</span><span class="text-white">${esc(String(v))}</span></div>`
 ).join('');
 tt.classList.remove('hidden');
 tt.style.left = (event.pageX + 12) + 'px';
 tt.style.top = (event.pageY - 10) + 'px';
 }).on('mouseout', () => {
 document.getElementById('graph-tooltip').classList.add('hidden');
 });

 if (simulation) simulation.stop();
 simulation = d3.forceSimulation(nodes)
 .force('link', d3.forceLink(links).id(d => d.id).distance(80))
 .force('charge', d3.forceManyBody().strength(-200))
 .force('center', d3.forceCenter(width / 2, height / 2))
 .force('collision', d3.forceCollide(15))
 .on('tick', () => {
 link.attr('x1', d => d.source.x).attr('y1', d => d.source.y)
 .attr('x2', d => d.target.x).attr('y2', d => d.target.y);
 node.attr('cx', d => d.x).attr('cy', d => d.y);
 label.attr('x', d => d.x).attr('y', d => d.y);
 });
}

function renderTreeGraph() {
 const d3 = window.d3;
 const svg = d3.select('#graph-svg');
 svg.selectAll('*').remove();
 const container = document.getElementById('graph-container');
 const width = container.clientWidth;
 const height = container.clientHeight;
 svg.attr('viewBox', [0, 0, width, height]);

 const typeOrder = ['jurisdiction', 'regulation', 'purpose', 'field', 'subject'];
 const root = { name: 'Consent Graph', type: 'root', children: [] };
 const typeGroups = {};
 typeOrder.forEach(t => { typeGroups[t] = []; });
 graphData.nodes.forEach(n => {
 if (typeGroups[n.type]) typeGroups[n.type].push({ name: n.name, type: n.type, properties: n.properties || {} });
 else {
 if (!typeGroups['other']) typeGroups['other'] = [];
 typeGroups['other'].push({ name: n.name, type: n.type, properties: n.properties || {} });
 }
 });

 const childMap = new Map();
 graphData.edges.forEach(e => {
 const srcKey = e.sourceType + ':' + e.sourceName;
 const tgtKey = e.targetType + ':' + e.targetName;
 if (!childMap.has(srcKey)) childMap.set(srcKey, []);
 childMap.get(srcKey).push(tgtKey);
 });

 const visited = new Set();
 function buildNode(key) {
 if (visited.has(key)) return null;
 visited.add(key);
 const [type, ...nameParts] = key.split(':');
 const name = nameParts.join(':');
 const treeNode = { name, type, children: [] };
 const children = childMap.get(key) || [];
 children.forEach(cKey => {
 const child = buildNode(cKey);
 if (child) treeNode.children.push(child);
 });
 return treeNode;
 }

 typeOrder.forEach(type => {
 typeGroups[type]?.forEach(n => {
 const key = type + ':' + n.name;
 if (!visited.has(key)) {
 const treeNode = buildNode(key);
 if (treeNode) root.children.push(treeNode);
 }
 });
 });

 if (root.children.length === 0) {
 document.getElementById('graph-empty').classList.remove('hidden');
 return;
 }

 const hierarchy = d3.hierarchy(root);
 const treeLayout = d3.tree().size([height - 40, width - 200]);
 treeLayout(hierarchy);

 const g = svg.append('g').attr('transform', 'translate(80, 20)');
 svg.call(d3.zoom().scaleExtent([0.1, 4]).on('zoom', (event) => {
 g.attr('transform', event.transform);
 }));

 g.selectAll('.tree-link')
 .data(hierarchy.links())
 .join('path')
 .attr('class', 'tree-link')
 .attr('d', d3.linkHorizontal().x(d => d.y).y(d => d.x))
 .attr('fill', 'none')
 .attr('stroke', '#374151').attr('stroke-width', 1.5);

 const nodeG = g.selectAll('.tree-node')
 .data(hierarchy.descendants())
 .join('g')
 .attr('class', 'tree-node')
 .attr('transform', d => `translate(${d.y},${d.x})`);

 nodeG.append('circle')
 .attr('r', 6)
 .attr('fill', d => NODE_COLORS[d.data.type] || '#6b7280')
 .attr('stroke', '#111827').attr('stroke-width', 1.5);

 nodeG.append('text')
 .attr('dy', 4).attr('x', d => d.children ? -10 : 10)
 .attr('text-anchor', d => d.children ? 'end' : 'start')
 .attr('font-size', 10).attr('fill', '#9ca3af')
 .text(d => d.data.name === 'Consent Graph' ? '' : (d.data.name.length > 25 ? d.data.name.slice(0, 23) + '…' : d.data.name));
}

// ================================================================
// LEDGER FEED (SSE)
// ================================================================
function initSSE() {
 if (sseInitialized) return;
 sseInitialized = true;

 try {
 eventSource = new EventSource(SSE_URL);

 eventSource.onopen = () => {
 updateSSEStatus('connected');
 };

 eventSource.onmessage = (event) => {
 try {
 const data = JSON.parse(event.data);
 const consentActions = ['GRANTED', 'REVOKED', 'ALL_REVOKED', 'CONSENT_ENTRY_CHANGED'];
 if (!consentActions.includes(data.action)) return;
 addLedgerEvent(data);
 } catch (e) {
 console.warn('Failed to parse SSE event:', e);
 }
 };

 eventSource.onerror = () => {
 updateSSEStatus('disconnected');
 setTimeout(() => {
 if (eventSource && eventSource.readyState !== EventSource.CLOSED) {
 updateSSEStatus('reconnecting');
 }
 }, 2000);
 };
 } catch (e) {
 updateSSEStatus('error');
 console.error('SSE initialization failed:', e);
 }
}

function updateSSEStatus(status) {
 const el = document.getElementById('sse-status');
 const states = {
 connected: { dot: 'bg-primary', text: 'text-primary', label: 'Live' },
 disconnected: { dot: 'bg-error', text: 'text-error', label: 'Disconnected' },
 reconnecting: { dot: 'bg-warning animate-pulse', text: 'text-warning', label: 'Reconnecting…' },
 error: { dot: 'bg-error', text: 'text-error', label: 'Error' },
 };
 const s = states[status] || states.disconnected;
 el.innerHTML = `<span class="w-2 h-2 rounded-full ${s.dot}"></span><span class="${s.text}">${s.label}</span>`;
}

function addLedgerEvent(data) {
 const event = {
 ...data,
 receivedAt: new Date().toISOString(),
 };
 ledgerEvents.unshift(event);
 if (ledgerEvents.length > MAX_EVENTS) ledgerEvents.pop();
 renderLedgerFeed();
}

function renderLedgerFeed() {
 const feed = document.getElementById('ledger-feed');
 const empty = document.getElementById('ledger-empty');
 const filtered = getFilteredEvents();

 if (filtered.length === 0) {
 empty.classList.remove('hidden');
 feed.querySelectorAll('.ledger-event').forEach(el => el.remove());
 document.getElementById('ledger-count').textContent = '';
 return;
 }

 empty.classList.add('hidden');
 feed.innerHTML = filtered.map(ev => {
 const style = getActionStyle(ev.action);
 return `
 <div class="ledger-event flex items-start gap-4 px-5 py-3 hover:bg-surface-container transition-colors">
 <div class="mt-1.5 flex-shrink-0">
 <span class="block w-2.5 h-2.5 rounded-full ${style.dot}"></span>
 </div>
 <div class="flex-1 min-w-0">
 <div class="flex items-center gap-2 mb-1">
 <span class="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium ${style.badge}">${style.label}</span>
 ${ev.subject ? `<span class="text-xs text-white font-technical truncate">${esc(ev.subject)}</span>` : ''}
 </div>
 <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-on-surface-muted">
 ${ev.purpose ? `<span>Purpose: <span class="text-on-surface">${esc(ev.purpose)}</span></span>` : ''}
 ${ev.field ? `<span>Field: <span class="text-on-surface">${esc(ev.field)}</span></span>` : ''}
 ${ev.entity ? `<span>Entity: <span class="text-on-surface">${esc(ev.entity)}</span></span>` : ''}
 ${ev.category ? `<span>Category: <span class="text-on-surface">${esc(ev.category)}</span></span>` : ''}
 </div>
 </div>
 <div class="flex-shrink-0 text-xs text-on-surface-variant whitespace-nowrap">
 ${fmtTime(ev.receivedAt)}
 </div>
 </div>`;
 }).join('');

 document.getElementById('ledger-count').textContent = `${filtered.length} events shown` +
 (filtered.length < ledgerEvents.length ? ` (${ledgerEvents.length} total)` : '');

 if (!ledgerPaused) feed.scrollTop = 0;
}

function getFilteredEvents() {
 const actionFilter = document.getElementById('ledger-filter-action').value;
 const subjectFilter = document.getElementById('ledger-filter-subject').value.trim().toLowerCase();
 const purposeFilter = document.getElementById('ledger-filter-purpose').value.trim().toLowerCase();

 return ledgerEvents.filter(ev => {
 if (actionFilter && ev.action !== actionFilter) return false;
 if (subjectFilter && (!ev.subject || !ev.subject.toLowerCase().includes(subjectFilter))) return false;
 if (purposeFilter && (!ev.purpose || !ev.purpose.toLowerCase().includes(purposeFilter))) return false;
 return true;
 });
}

function applyLedgerFilters() { renderLedgerFeed(); }

function toggleLedgerPause() {
 ledgerPaused = !ledgerPaused;
 const btn = document.getElementById('ledger-pause-btn');
 btn.classList.toggle('text-primary', ledgerPaused);
 btn.classList.toggle('border-primary/30', ledgerPaused);
 btn.title = ledgerPaused ? 'Resume auto-scroll' : 'Pause auto-scroll';
}

// ================================================================
// SUBJECTS
// ================================================================
async function loadSubjects() {
 try {
 subjectsCache = await subjectsApi('');
 subjectsLoaded = true;
 renderSubjects();
 } catch (e) {
 document.getElementById('subjects-table').innerHTML =
 `<tr><td colspan="4" class="px-6 py-8 text-center text-error">${esc(e.message)}</td></tr>`;
 }
}

function renderSubjects(filter) {
 const tb = document.getElementById('subjects-table');
 let data = subjectsCache;
 if (filter) {
 const f = filter.toLowerCase();
 data = data.filter(s => s.subjectName.toLowerCase().includes(f) || (s.description || '').toLowerCase().includes(f));
 }
 if (!data.length) {
 tb.innerHTML = `<tr><td colspan="4" class="px-6 py-8 text-center text-on-surface-muted">${filter ? 'No matching subjects.' : 'No subjects registered yet.'}</td></tr>`;
 return;
 }
 tb.innerHTML = data.map(s => `
 <tr class="hover:bg-surface-container transition-colors cursor-pointer" onclick="viewSubject('${esc(s.subjectName)}')">
 <td class="px-6 py-4 font-medium text-on-surface font-technical">${esc(s.subjectName)}</td>
 <td class="px-6 py-4 text-on-surface-muted">${esc(s.description || '—')}</td>
 <td class="px-6 py-4 text-on-surface-muted text-xs">${fmtTime(s.createdAt)}</td>
 <td class="px-6 py-4 text-right" onclick="event.stopPropagation()">
 <button onclick='openSubjectPanel("edit", ${JSON.stringify(s).replace(/'/g,"&#39;")})' class="text-on-surface-muted hover:text-primary mr-3 transition-colors text-xs font-medium">Edit</button>
 <button onclick='deleteSubject("${esc(s.subjectName)}")' class="text-on-surface-muted hover:text-error transition-colors text-xs font-medium">Delete</button>
 </td>
 </tr>`).join('');
}

function filterSubjects() {
 renderSubjects(document.getElementById('subjects-search').value.trim());
}

async function viewSubject(name) {
 currentSubject = name;
 document.getElementById('subject-detail').classList.remove('hidden');
 document.getElementById('subject-detail-title').textContent = name;
 showDetailTab('summary');
}

function closeSubjectDetail() {
 document.getElementById('subject-detail').classList.add('hidden');
 currentSubject = null;
}

function showDetailTab(tab) {
 document.querySelectorAll('.dtab-btn').forEach(b => {
 b.classList.remove('border-primary', 'text-primary');
 b.classList.add('border-transparent', 'text-on-surface-muted');
 });
 document.getElementById('dtab-' + tab).classList.add('border-primary', 'text-primary');
 document.getElementById('dtab-' + tab).classList.remove('border-transparent', 'text-on-surface-muted');

 if (tab === 'summary') loadSubjectSummary();
 else if (tab === 'history') loadSubjectHistory();
 else if (tab === 'compliance') loadComplianceTab();
}

async function loadSubjectSummary() {
 const body = document.getElementById('subject-detail-body');
 body.innerHTML = '<p class="text-on-surface-muted text-sm">Loading\u2026</p>';
 try {
 const data = await subjectsApi(`/${encodeURIComponent(currentSubject)}/summary`);
 const entries = data.entries || [];
 if (!entries.length) {
 body.innerHTML = '<p class="text-on-surface-muted text-sm">No active consent entries for this subject.</p>';
 return;
 }
 body.innerHTML = `
 <div class="space-y-0 divide-y divide-gray-700/30">
 ${entries.map(e => {
 const style = getActionStyle(e.action);
 return `
 <div class="flex items-center gap-4 py-3">
 <span class="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium ${style.badge}">${esc(e.action)}</span>
 <div class="flex-1 text-sm">
 <span class="text-white">${esc(e.purposeName)}</span>
 <span class="text-on-surface-muted mx-1">→</span>
 <span class="text-on-surface-muted">${esc(e.policyType === 'FIELD' ? (e.entityName ? e.entityName + '/' : '') + (e.fieldName || '') : e.categoryName || '')}</span>
 </div>
 <span class="text-xs text-on-surface-variant">${fmtTime(e.createdAt)}</span>
 ${e.expiresAt ? `<span class="text-xs text-warning/70">expires ${fmtTime(e.expiresAt)}</span>` : ''}
 </div>`;
 }).join('')}
 </div>`;
 } catch (e) {
 body.innerHTML = `<p class="text-error text-sm">${esc(e.message)}</p>`;
 }
}

async function loadSubjectHistory() {
 const body = document.getElementById('subject-detail-body');
 body.innerHTML = '<p class="text-on-surface-muted text-sm">Loading\u2026</p>';
 try {
 const data = await subjectsApi(`/${encodeURIComponent(currentSubject)}/history`);
 const entries = data.entries || [];
 if (!entries.length) {
 body.innerHTML = '<p class="text-on-surface-muted text-sm">No history for this subject.</p>';
 return;
 }
 body.innerHTML = `
 <div class="overflow-x-auto">
 <table class="w-full text-xs text-left">
 <thead class="text-on-surface-muted uppercase tracking-wide">
 <tr>
 <th class="py-2 pr-4">Action</th>
 <th class="py-2 pr-4">Purpose</th>
 <th class="py-2 pr-4">Scope</th>
 <th class="py-2 pr-4">Actor</th>
 <th class="py-2 pr-4">IP</th>
 <th class="py-2">Time</th>
 </tr>
 </thead>
 <tbody class="divide-y divide-surface-high">
 ${entries.map(e => {
 const style = getActionStyle(e.action);
 return `
 <tr class="hover:bg-surface-container">
 <td class="py-2 pr-4"><span class="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium ${style.badge}">${esc(e.action)}</span></td>
 <td class="py-2 pr-4 text-on-surface">${esc(e.purposeName)}</td>
 <td class="py-2 pr-4 text-on-surface-variant">${esc(e.policyType === 'FIELD' ? (e.entityName ? e.entityName + '/' : '') + (e.fieldName || '') : e.categoryName || '')}</td>
 <td class="py-2 pr-4 text-on-surface-muted">${esc(e.actor || '—')}</td>
 <td class="py-2 pr-4 text-on-surface-muted font-technical">${esc(e.ipAddress || '—')}</td>
 <td class="py-2 text-on-surface-muted">${fmtTime(e.createdAt)}</td>
 </tr>`;
 }).join('')}
 </tbody>
 </table>
 </div>`;
 } catch (e) {
 body.innerHTML = `<p class="text-error text-sm">${esc(e.message)}</p>`;
 }
}

async function exportSubjectData() {
 if (!currentSubject) return;
 try {
 const data = await subjectsApi(`/${encodeURIComponent(currentSubject)}/export`);
 const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
 const url = URL.createObjectURL(blob);
 const a = document.createElement('a');
 a.href = url;
 a.download = `dsar-export-${currentSubject}.json`;
 a.click();
 URL.revokeObjectURL(url);
 } catch (e) {
 showMsg(document.getElementById('pf-sub-msg'), 'Export failed: ' + e.message, true);
 }
}

// ── Compliance Tab ─────────────────────────────────────────────

let complianceErasureComplete = false;

async function loadComplianceTab() {
 const body = document.getElementById('subject-detail-body');
 body.innerHTML = '<p class="text-on-surface-muted text-sm">Loading…</p>';
 complianceErasureComplete = false;

 try {
 const [exportData, auditData] = await Promise.all([
 subjectsApi(`/${encodeURIComponent(currentSubject)}/export`).catch(() => null),
 subjectsApi(`/${encodeURIComponent(currentSubject)}/audit`).catch(() => ({ entries: [] })),
 ]);

 const entries = exportData?.consent?.entries || [];
 const auditEntries = auditData?.entries || [];
 const hasErasureHistory = auditEntries.length > 0;

 body.innerHTML = `
 <div class="space-y-8">
 <!-- Export Section -->
 <div>
 <div class="flex items-center justify-between mb-3">
 <h4 class="text-sm font-semibold text-on-surface">Export Data</h4>
 <button onclick="exportSubjectData()" class="px-3 py-1.5 text-xs text-primary border border-primary/30 rounded hover:bg-primary/10 transition-colors inline-flex items-center gap-1">
 <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"></path></svg>
 Download JSON
 </button>
 </div>
 ${entries.length ? `
 <div class="border ghost-border rounded-md overflow-hidden">
 <table class="w-full text-xs text-left">
 <thead class="bg-surface-base text-on-surface-muted uppercase tracking-wide">
 <tr>
 <th class="px-4 py-2">Status</th>
 <th class="px-4 py-2">Purpose</th>
 <th class="px-4 py-2">Scope</th>
 <th class="px-4 py-2">Date</th>
 </tr>
 </thead>
 <tbody class="divide-y divide-surface-high">
 ${entries.slice(0, 20).map(e => {
 const style = getActionStyle(e.action);
 const scope = e.policyType === 'FIELD'
 ? (e.entityName ? e.entityName + '/' : '') + (e.fieldName || '')
 : e.categoryName || '';
 return `
 <tr class="hover:bg-surface-container">
 <td class="px-4 py-2"><span class="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium ${style.badge}">${esc(e.action)}</span></td>
 <td class="px-4 py-2 text-on-surface">${esc(e.purposeName)}</td>
 <td class="px-4 py-2 text-on-surface-muted">${esc(scope)}</td>
 <td class="px-4 py-2 text-on-surface-muted">${fmtTime(e.createdAt)}</td>
 </tr>`;
 }).join('')}
 </tbody>
 </table>
 ${entries.length > 20 ? `<div class="px-4 py-2 bg-surface-base text-xs text-on-surface-muted">Showing 20 of ${entries.length} entries. Download JSON for full export.</div>` : ''}
 </div>` : `<p class="text-on-surface-muted text-sm">No consent entries to export.</p>`}
 </div>

 <!-- Shred Section -->
 <div>
 <h4 class="text-sm font-semibold text-on-surface mb-1">Right to Erasure</h4>
 <p class="text-xs text-on-surface-muted mb-4">Permanently erase all data for this subject. This is a two-step, irreversible process.</p>

 <div class="space-y-4">
 <!-- Step 1 -->
 <div id="compliance-step1" class="border ghost-border rounded-md p-4">
 <div class="flex items-center justify-between">
 <div>
 <div class="flex items-center gap-2 mb-1">
 <span class="text-xs font-semibold text-on-surface-muted uppercase">Step 1</span>
 <span id="compliance-step1-status" class="text-xs px-2 py-0.5 rounded-full bg-surface-container text-on-surface-muted">Not started</span>
 </div>
 <p class="text-sm font-medium text-on-surface">Revoke All Consent &amp; Purge Cache</p>
 <p class="text-xs text-on-surface-muted mt-0.5">Revokes all consent, deletes cached decisions, removes the subject record, and logs an audit entry.</p>
 </div>
 <button onclick="openErasureModal()" id="compliance-step1-btn" class="btn-danger text-xs whitespace-nowrap">
 Begin Erasure
 </button>
 </div>
 <div id="compliance-step1-result" class="hidden mt-3 text-xs"></div>
 </div>

 <!-- Step 2 -->
 <div id="compliance-step2" class="border ghost-border rounded-md p-4 opacity-50">
 <div class="flex items-center justify-between">
 <div>
 <div class="flex items-center gap-2 mb-1">
 <span class="text-xs font-semibold text-on-surface-muted uppercase">Step 2</span>
 <span id="compliance-step2-status" class="text-xs px-2 py-0.5 rounded-full bg-surface-container text-on-surface-muted">Waiting for Step 1</span>
 </div>
 <p class="text-sm font-medium text-on-surface">Shred Encryption Key</p>
 <p class="text-xs text-on-surface-muted mt-0.5">Deletes the per-subject encryption key — all encrypted event payloads become permanently unreadable.</p>
 </div>
 <button onclick="shredSubjectKey()" id="compliance-step2-btn" class="btn-danger text-xs whitespace-nowrap disabled:opacity-50 disabled:cursor-not-allowed" disabled>
 Shred Key
 </button>
 </div>
 <div id="compliance-step2-result" class="hidden mt-3 text-xs"></div>
 </div>
 </div>
 </div>

 <!-- Audit Log Section -->
 <div>
 <h4 class="text-sm font-semibold text-on-surface mb-3">Erasure Audit Log</h4>
 ${auditEntries.length ? `
 <div class="border ghost-border rounded-md overflow-hidden">
 <table class="w-full text-xs text-left">
 <thead class="bg-surface-base text-on-surface-muted uppercase tracking-wide">
 <tr>
 <th class="px-4 py-2">Status</th>
 <th class="px-4 py-2">Request ID</th>
 <th class="px-4 py-2">Policies</th>
 <th class="px-4 py-2">Cache Keys</th>
 <th class="px-4 py-2">Actor</th>
 <th class="px-4 py-2">Time</th>
 </tr>
 </thead>
 <tbody class="divide-y divide-surface-high">
 ${auditEntries.map(e => `
 <tr class="hover:bg-surface-container">
 <td class="px-4 py-2">
 <span class="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium ${
 e.status === 'SUCCESS' || e.status === 'PENDING_SHRED'
 ? 'bg-secondary-container text-on-secondary-container border border-primary/20'
 : 'bg-error-container text-on-error-container border border-error/20'
 }">${esc(e.status)}</span>
 </td>
 <td class="px-4 py-2 text-on-surface-muted font-technical">${esc((e.deletionRequestId || '').substring(0, 8))}…</td>
 <td class="px-4 py-2 text-on-surface">${e.policiesDeleted}</td>
 <td class="px-4 py-2 text-on-surface">${e.cachedKeysDeleted}</td>
 <td class="px-4 py-2 text-on-surface-muted">${esc(e.actor || '—')}</td>
 <td class="px-4 py-2 text-on-surface-muted">${fmtTime(e.createdAt)}</td>
 </tr>`).join('')}
 </tbody>
 </table>
 </div>` : `<p class="text-on-surface-muted text-sm">No erasure requests have been made for this subject.</p>`}
 </div>
 </div>`;
 } catch (e) {
 body.innerHTML = `<p class="text-error text-sm">${esc(e.message)}</p>`;
 }
}

function openErasureModal() {
 if (!currentSubject) return;
 const token = 'DELETE_' + currentSubject;
 document.getElementById('erasure-modal-token').textContent = token;
 document.getElementById('erasure-modal-input').value = '';
 document.getElementById('erasure-modal-confirm').disabled = true;
 const errEl = document.getElementById('erasure-modal-error');
 errEl.textContent = '';
 errEl.classList.add('hidden');
 document.getElementById('erasure-modal').classList.remove('hidden');

 const input = document.getElementById('erasure-modal-input');
 input.setAttribute('placeholder', token);
 input.oninput = () => {
 document.getElementById('erasure-modal-confirm').disabled = input.value !== token;
 };
}

function closeErasureModal() {
 document.getElementById('erasure-modal').classList.add('hidden');
}

function initErasureModal() {
 const btn = document.getElementById('erasure-modal-confirm');
 btn.addEventListener('click', async () => {
 const token = 'DELETE_' + currentSubject;
 const input = document.getElementById('erasure-modal-input');
 if (input.value !== token) return;

 btn.disabled = true;
 btn.textContent = 'Erasing…';
 const errEl = document.getElementById('erasure-modal-error');
 errEl.classList.add('hidden');

 try {
 await beginErasureRequest();
 closeErasureModal();
 } catch (e) {
 errEl.textContent = e.message;
 errEl.classList.remove('hidden');
 } finally {
 btn.disabled = false;
 btn.textContent = 'Begin Erasure';
 }
 });
}

async function beginErasureRequest() {
 if (!currentSubject) return;
 const token = 'DELETE_' + currentSubject;

 const result = await subjectsApi(
 `/${encodeURIComponent(currentSubject)}/audit?confirm=${encodeURIComponent(token)}`,
 { method: 'DELETE' }
 );

 complianceErasureComplete = true;

 // Update Step 1 UI
 const s1Status = document.getElementById('compliance-step1-status');
 s1Status.textContent = result.status || 'PENDING_SHRED';
 s1Status.className = 'text-xs px-2 py-0.5 rounded-full bg-secondary-container text-on-secondary-container';

 const s1Result = document.getElementById('compliance-step1-result');
 s1Result.classList.remove('hidden');
 s1Result.innerHTML = `
 <div class="bg-secondary-container border border-primary/20 rounded p-2 text-on-secondary-container">
 Erasure complete — ${result.ledgerEntriesRevoked || 0} entries revoked, ${result.cachedKeysDeleted || 0} cache keys deleted.
 <span class="font-technical text-xs text-on-secondary-container">Request: ${esc((result.deletionRequestId || '').substring(0, 8))}…</span>
 </div>`;

 const s1Btn = document.getElementById('compliance-step1-btn');
 s1Btn.disabled = true;
 s1Btn.classList.add('opacity-50', 'cursor-not-allowed');
 s1Btn.textContent = 'Completed';

 // Enable Step 2
 document.getElementById('compliance-step2').classList.remove('opacity-50');
 const s2Status = document.getElementById('compliance-step2-status');
 s2Status.textContent = 'Ready';
 s2Status.className = 'text-xs px-2 py-0.5 rounded-full bg-warning-container text-on-warning-container';
 document.getElementById('compliance-step2-btn').disabled = false;
}

async function shredSubjectKey() {
 if (!currentSubject || !complianceErasureComplete) return;

 const btn = document.getElementById('compliance-step2-btn');
 btn.disabled = true;
 btn.textContent = 'Shredding…';

 try {
 await subjectsApi(
 `/${encodeURIComponent(currentSubject)}/dsar-key`,
 { method: 'DELETE' }
 );

 const s2Status = document.getElementById('compliance-step2-status');
 s2Status.textContent = 'Shredded';
 s2Status.className = 'text-xs px-2 py-0.5 rounded-full bg-secondary-container text-on-secondary-container';

 btn.textContent = 'Completed';
 btn.classList.add('opacity-50', 'cursor-not-allowed');

 const s2Result = document.getElementById('compliance-step2-result');
 s2Result.classList.remove('hidden');
 s2Result.innerHTML = `
 <div class="bg-secondary-container border border-primary/20 rounded p-2 text-on-secondary-container">
 Encryption key permanently destroyed. All encrypted event payloads for this subject are now unreadable.
 </div>`;
 } catch (e) {
 btn.disabled = false;
 btn.textContent = 'Shred Key';

 const s2Result = document.getElementById('compliance-step2-result');
 s2Result.classList.remove('hidden');

 if (e.message && e.message.includes('EVENTS_PENDING')) {
 const s2Status = document.getElementById('compliance-step2-status');
 s2Status.textContent = 'Events pending';
 s2Status.className = 'text-xs px-2 py-0.5 rounded-full bg-warning-container text-on-warning-container';
 s2Result.innerHTML = `
 <div class="bg-warning-container border border-warning/20 rounded p-2 text-on-warning-container">
 Events are still being processed by the outbox. Please wait a moment and try again.
 </div>`;
 } else {
 s2Result.innerHTML = `
 <div class="bg-error-container border border-error/20 rounded p-2 text-on-error-container">
 Shred failed: ${esc(e.message)}
 </div>`;
 }
 }
}

function openSubjectPanel(type, data) {
 const title = document.getElementById('panel-title');
 const body = document.getElementById('panel-body');

 if (type === 'add') {
 title.textContent = 'Register Subject';
 body.innerHTML = subjectForm();
 } else if (type === 'edit') {
 title.textContent = 'Edit Subject';
 body.innerHTML = subjectForm(data);
 }

 activatePanel();
}

function closeSubjectPanel() {
 closePanel();
}

function subjectForm(existing) {
 const n = existing ? esc(existing.subjectName) : '';
 const d = existing ? esc(existing.description || '') : '';
 return `
 <form onsubmit="saveSubject(event, ${existing ? 'true' : 'false'}, '${n}')">
 <div class="space-y-4">
 <div>
 <label class="block text-sm text-on-surface-muted mb-1">Subject Name</label>
 <input id="pf-sub-name" value="${n}" ${existing ? 'disabled' : ''} required
 class="w-full input-field disabled:opacity-50" />
 </div>
 <div>
 <label class="block text-sm text-on-surface-muted mb-1">Description</label>
 <textarea id="pf-sub-desc" rows="3"
 class="w-full input-field">${d}</textarea>
 </div>
 </div>
 <div class="mt-6 flex gap-3">
 <button type="submit" class="px-4 py-2 btn-primary transition-colors">
 ${existing ? 'Update' : 'Register'}
 </button>
 <button type="button" onclick="closeSubjectPanel()" class="px-4 py-2 text-sm btn-secondary transition-colors">Cancel</button>
 </div>
 <span id="pf-sub-msg" class="hidden"></span>
 </form>`;
}

async function saveSubject(e, isEdit, origName) {
 e.preventDefault();
 const name = document.getElementById('pf-sub-name').value.trim();
 const desc = document.getElementById('pf-sub-desc').value.trim();
 const msg = document.getElementById('pf-sub-msg');
 try {
 if (isEdit) {
 await subjectsApi(`/${encodeURIComponent(origName)}`, { method: 'PUT', body: JSON.stringify({ description: desc }) });
 } else {
 await subjectsApi('', { method: 'POST', body: JSON.stringify({ subjectName: name, description: desc }) });
 }
 closeSubjectPanel();
 await reloadSubjects();
 } catch (err) {
 showMsg(msg, err.message, true);
 }
}

function deleteSubject(name) {
 confirmDelete(`Delete subject "${name}"? This cannot be undone.`, async () => {
 await subjectsApi(`/${encodeURIComponent(name)}`, { method: 'DELETE' });
 await reloadSubjects();
 if (currentSubject === name) closeSubjectDetail();
 });
}

// ── Init ───────────────────────────────────────────────────────
export function init() {
 Object.assign(window, {
 showTab, closeDeleteModal,
 setGraphMode, loadGraph, resetGraph, loadMoreGraph,
 applyLedgerFilters, toggleLedgerPause,
 filterSubjects,
 openSubjectPanel, closeSubjectPanel,
 viewSubject, closeSubjectDetail,
 showDetailTab, exportSubjectData,
 saveSubject, deleteSubject,
 // Compliance
 openErasureModal, closeErasureModal,
 beginErasureRequest, shredSubjectKey,
 // Playground
 evaluateAndMask, loadSampleTemplate, copyMaskedOutput,
 });

 initDeleteModal();
 initErasureModal();

 ready(() => {
 const hash = window.location.hash.replace('#', '');
 if (['graph', 'ledger', 'subjects', 'playground'].includes(hash)) {
 showTab(hash);
 } else {
 loadGraph();
 }
 });
}
