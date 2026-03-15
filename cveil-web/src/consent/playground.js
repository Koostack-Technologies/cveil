import { esc } from '../shared/index.js';
import { createApi } from '../shared/api.js';
import { MaskingType, MASKING_TYPES, maskJsonTree, extractFieldPaths } from './masking.js';
import { WasmBridge } from './wasm-bridge.js';

const catalogueApi = createApi('/api/v1/catalogue');
const governanceApi = createApi('/api/v1/governance');
const consentApi = createApi('/api/v1/subjects');

// ── State ──────────────────────────────────────────────────────
let entities = [];
let purposes = [];
let categories = [];
let subjects = [];
let lastDecisions = [];   // array of { path, status, resolvedBy, maskingType, detail }
let fieldOverrides = {};  // path → MaskingType (user overrides)
let lastInputData = null;
let lastMaskedData = null;
let playgroundLoaded = false;

/** @type {WasmBridge|null} */
let bridge = null;
let wasmReady = false;

const WASM_URL = '/wasm/cveil_sdk_core.wasm';

const SAMPLE_TEMPLATES = {
    User: {
        name: 'Alice Smith',
        email: 'alice@example.com',
        ssn: '123-45-6789',
        age: 30,
        address: {
            street: '123 Main St',
            city: 'Springfield',
            zip: '62704',
        },
    },
    Patient: {
        patientId: 'P-78291',
        firstName: 'John',
        lastName: 'Doe',
        dateOfBirth: '1985-03-14',
        diagnosis: 'Hypertension',
        insurance: {
            provider: 'BlueCross',
            policyNumber: 'BC-44921-X',
        },
    },
    Order: {
        orderId: 'ORD-10042',
        customerEmail: 'jane@shop.com',
        phone: '+1-555-867-5309',
        total: 149.99,
        items: [
            { sku: 'WIDGET-A', name: 'Widget Alpha', qty: 2 },
        ],
        shipping: {
            street: '456 Oak Ave',
            city: 'Portland',
            state: 'OR',
        },
    },
};

// ── WASM Initialization ────────────────────────────────────────
async function initWasm() {
    if (wasmReady) return;
    try {
        bridge = await WasmBridge.load(WASM_URL);
        const w = bridge.exports;

        // Initialize SDK core with minimal config
        bridge.call1(w.cveil_init, JSON.stringify({
            apiBaseUrl: window.location.origin,
            apiToken: '',
            defaultJurisdiction: '',
        }));

        // Ingest the full consent graph (paginated)
        let cursor = null;
        let hasMore = true;
        while (hasMore) {
            const url = cursor
                ? `/api/v1/consent/graph?limit=500&cursor=${encodeURIComponent(cursor)}`
                : '/api/v1/consent/graph?limit=500';
            const res = await fetch(url);
            if (!res.ok) throw new Error('Graph fetch failed');
            const page = await res.json();
            const result = JSON.parse(bridge.call1(w.cveil_ingest_graph_page, JSON.stringify(page)));
            hasMore = result.hasMore;
            cursor = page.pageInfo?.nextCursor || null;
            if (!cursor) hasMore = false;
        }

        wasmReady = true;
        updateWasmBadge(true);
        showPlaygroundMsg('WASM engine loaded — using real masking SDK', false);
        setTimeout(() => showPlaygroundMsg('', false), 3000);
    } catch (e) {
        console.warn('WASM init failed, falling back to JS masking:', e);
        updateWasmBadge(false);
        showPlaygroundMsg('WASM unavailable — using JS masking fallback', false);
        setTimeout(() => showPlaygroundMsg('', false), 4000);
    }
}

// ── Initialization (lazy, called on first tab visit) ───────────
export async function initPlayground() {
    if (playgroundLoaded) return;
    playgroundLoaded = true;

    try {
        const [entRes, purRes, catRes, subRes] = await Promise.all([
            catalogueApi('/entities'),
            governanceApi('/purposes'),
            catalogueApi('/categories'),
            consentApi(''),
            initWasm(),
        ]);
        entities = entRes || [];
        purposes = purRes || [];
        categories = catRes || [];
        subjects = Array.isArray(subRes) ? subRes : (subRes?.items || []);

        populateSelect('pg-subject', subjects.map(s => s.name || s.subjectName || s));
        populateSelect('pg-entity', entities.map(e => e.name || e.entityName || e));
        populateSelect('pg-purpose', purposes.map(p => p.name || p.purposeName || p));
        populateTemplateButtons();
    } catch (e) {
        showPlaygroundMsg(`Failed to load reference data: ${e.message}`, true);
    }
}

function populateSelect(id, items) {
    const sel = document.getElementById(id);
    if (!sel) return;
    const current = sel.value;
    sel.innerHTML = '<option value="">— select —</option>' +
        items.map(i => `<option value="${esc(String(i))}">${esc(String(i))}</option>`).join('');
    if (current && items.includes(current)) sel.value = current;
}

function populateTemplateButtons() {
    const container = document.getElementById('pg-templates');
    if (!container) return;
    container.innerHTML = Object.keys(SAMPLE_TEMPLATES)
        .map(name => `<button onclick="loadSampleTemplate('${name}')"
            class="px-2 py-1 text-xs border border-gray-300 rounded text-gray-600 hover:bg-gray-100 hover:text-gray-900 transition-colors">${esc(name)}</button>`)
        .join('');
}

export function loadSampleTemplate(name) {
    const tpl = SAMPLE_TEMPLATES[name];
    if (!tpl) return;
    const ta = document.getElementById('pg-json-input');
    if (ta) ta.value = JSON.stringify(tpl, null, 2);
    clearResults();
}

// ── Evaluate & Mask ────────────────────────────────────────────
export async function evaluateAndMask() {
    const subjectId = document.getElementById('pg-subject')?.value;
    const entityName = document.getElementById('pg-entity')?.value;
    const purposeName = document.getElementById('pg-purpose')?.value;
    const jsonStr = document.getElementById('pg-json-input')?.value?.trim();

    if (!subjectId) return showPlaygroundMsg('Select a subject.', true);
    if (!entityName) return showPlaygroundMsg('Select an entity.', true);
    if (!purposeName) return showPlaygroundMsg('Select a purpose.', true);
    if (!jsonStr) return showPlaygroundMsg('Enter JSON data.', true);

    let data;
    try {
        data = JSON.parse(jsonStr);
    } catch (e) {
        return showPlaygroundMsg(`Invalid JSON: ${e.message}`, true);
    }
    lastInputData = data;

    const fieldPaths = extractFieldPaths(data);
    if (fieldPaths.length === 0) return showPlaygroundMsg('No leaf fields found in JSON.', true);

    showPlaygroundMsg('Evaluating consent…', false);
    const btn = document.getElementById('pg-evaluate-btn');
    if (btn) btn.disabled = true;

    try {
        if (wasmReady) {
            await evaluateWithWasm(subjectId, entityName, purposeName, data, fieldPaths);
        } else {
            await evaluateWithRest(subjectId, entityName, purposeName, data, fieldPaths);
        }
        showPlaygroundMsg('', false);
    } catch (e) {
        showPlaygroundMsg(`Evaluate failed: ${e.message}`, true);
    } finally {
        if (btn) btn.disabled = false;
    }
}

/** Use the real WASM SDK: cveil_mask does evaluation + masking in one call. */
async function evaluateWithWasm(subject, entity, purpose, data, fieldPaths) {
    const w = bridge.exports;

    // First call cveil_mask — it will return masked data + needsFetch
    let resultJson = bridge.call4(w.cveil_mask, subject, entity, JSON.stringify(data), purpose);
    let result = JSON.parse(resultJson);

    // If some fields need consent decisions fetched from the server, do that
    if (result.needsFetch && result.needsFetch.length > 0) {
        const policyNames = [...new Set(result.needsFetch.flatMap(nf => nf.policyNames || []))];
        if (policyNames.length > 0) {
            // Fetch decisions from the REST evaluate endpoint
            const scopes = fieldPaths.map(path => ({
                fieldName: path.split('.').pop(),
                entityName: entity,
                purposeName: purpose,
            }));
            try {
                const evalResult = await consentApi(
                    `/${encodeURIComponent(subject)}/evaluate`,
                    { method: 'POST', body: JSON.stringify({ scopes }) },
                );
                // Build decision entries for WASM ingestion
                const decisions = buildDecisionEntries(evalResult);
                if (decisions.length > 0) {
                    bridge.call2(w.cveil_ingest_decisions, subject, JSON.stringify(decisions));
                }
                // Re-mask with ingested decisions
                resultJson = bridge.call4(w.cveil_mask, subject, entity, JSON.stringify(data), purpose);
                result = JSON.parse(resultJson);
            } catch (e) {
                console.warn('Decision fetch failed, using deny-by-default masking:', e);
            }
        }
    }

    lastMaskedData = result.data;

    // Also get per-field evaluation details via cveil_evaluate
    const evalJson = bridge.call4(w.cveil_evaluate, subject, entity, JSON.stringify(fieldPaths), purpose);
    const evalResults = JSON.parse(evalJson);

    lastDecisions = fieldPaths.map((path, i) => {
        const er = evalResults[i] || {};
        const mt = er.masking_type || er.maskingType || MaskingType.RedactAll;
        const needsFetch = er.needs_fetch || er.needsFetch || false;
        return {
            path,
            fieldName: er.field || path.split('.').pop(),
            status: (mt === MaskingType.Plaintext) ? 'GRANTED' : 'DENIED',
            resolvedBy: needsFetch ? 'PENDING' : (er.policy_names?.length ? 'POLICY' : 'GRAPH'),
            maskingType: mt,
            detail: er,
        };
    });

    fieldOverrides = {};
    renderDecisions();
    renderMaskedOutput(result.data);
}

/** Build decision entries from REST evaluate response for WASM ingestion. */
function buildDecisionEntries(evalResult) {
    const entries = [];
    const details = evalResult.details || evalResult.reasons || {};
    const granted = new Set((evalResult.granted || []).map(s => s.fieldName || s));

    for (const [key, detail] of Object.entries(details)) {
        if (detail.policyName || detail.policy_name) {
            entries.push({
                policyName: detail.policyName || detail.policy_name,
                action: granted.has(key) ? 'granted' : 'denied',
                expiresAt: detail.expiresAt || detail.expires_at || null,
            });
        }
    }
    return entries;
}

/** Fallback: REST evaluate + JS masking (when WASM is unavailable). */
async function evaluateWithRest(subject, entity, purpose, data, fieldPaths) {
    // Look up fields → categories for scope building
    let fieldMeta = [];
    try {
        const allFields = await catalogueApi(`/entities/${encodeURIComponent(entity)}/fields`);
        fieldMeta = allFields || [];
    } catch { /* ignore — we'll fall back to no category */ }

    const fieldCatMap = {};
    for (const f of fieldMeta) {
        fieldCatMap[(f.name || f.fieldName)] = f.categoryName || f.category || '';
    }

    const scopes = fieldPaths.map(path => {
        const fieldName = path.split('.').pop();
        return { fieldName, entityName: entity, purposeName: purpose, category: fieldCatMap[fieldName] || '' };
    });

    const result = await consentApi(`/${encodeURIComponent(subject)}/evaluate`, {
        method: 'POST',
        body: JSON.stringify({ scopes }),
    });

    const granted = new Set((result.granted || []).map(s => s.fieldName || s));
    const details = result.details || result.reasons || {};

    lastDecisions = fieldPaths.map(path => {
        const fieldName = path.split('.').pop();
        const isGranted = granted.has(fieldName);
        const detail = details[fieldName] || details[path] || {};
        const policyMask = detail.maskingType || (isGranted ? MaskingType.Plaintext : MaskingType.RedactAll);
        return {
            path, fieldName,
            status: isGranted ? 'GRANTED' : 'DENIED',
            resolvedBy: detail.resolvedBy || 'NONE',
            maskingType: policyMask, detail,
        };
    });

    fieldOverrides = {};
    renderDecisions();
    await applyMaskingJs();
}

async function applyMaskingJs() {
    if (!lastInputData || lastDecisions.length === 0) return;
    const resolutions = {};
    for (const d of lastDecisions) {
        resolutions[d.path] = fieldOverrides[d.path] || d.maskingType;
    }
    const masked = await maskJsonTree(lastInputData, resolutions);
    lastMaskedData = masked;
    renderMaskedOutput(masked);
}

export async function remask() {
    for (const d of lastDecisions) {
        const sel = document.getElementById(`pg-override-${CSS.escape(d.path)}`);
        if (sel && sel.value) fieldOverrides[d.path] = sel.value;
    }
    // Re-mask: if WASM is available and no overrides differ, use WASM; otherwise JS fallback
    const hasOverrides = Object.keys(fieldOverrides).length > 0;
    if (wasmReady && !hasOverrides) {
        const subjectId = document.getElementById('pg-subject')?.value;
        const entityName = document.getElementById('pg-entity')?.value;
        const purposeName = document.getElementById('pg-purpose')?.value;
        if (subjectId && entityName && purposeName && lastInputData) {
            try {
                const w = bridge.exports;
                const resultJson = bridge.call4(w.cveil_mask, subjectId, entityName, JSON.stringify(lastInputData), purposeName);
                const result = JSON.parse(resultJson);
                lastMaskedData = result.data;
                renderMaskedOutput(result.data);
                return;
            } catch { /* fall through to JS */ }
        }
    }
    // JS fallback for overrides or WASM unavailability
    await applyMaskingJs();
}

// ── Rendering ──────────────────────────────────────────────────
function renderDecisions() {
    const container = document.getElementById('pg-decisions');
    if (!container || lastDecisions.length === 0) return;

    const rows = lastDecisions.map(d => {
        const effectiveMask = fieldOverrides[d.path] || d.maskingType;
        const statusBadge = d.status === 'GRANTED'
            ? '<span class="inline-flex items-center gap-1 text-xs font-medium text-green-400"><span class="w-1.5 h-1.5 rounded-full bg-green-500"></span>GRANTED</span>'
            : '<span class="inline-flex items-center gap-1 text-xs font-medium text-red-400"><span class="w-1.5 h-1.5 rounded-full bg-red-500"></span>DENIED</span>';

        const overrideOpts = MASKING_TYPES.map(mt =>
            `<option value="${mt}" ${mt === effectiveMask ? 'selected' : ''}>${mt}</option>`
        ).join('');

        return `<tr class="border-t border-gray-200">
            <td class="px-3 py-2 font-mono text-xs text-gray-600">${esc(d.path)}</td>
            <td class="px-3 py-2">${statusBadge}</td>
            <td class="px-3 py-2 text-xs text-gray-500">${esc(d.resolvedBy)}</td>
            <td class="px-3 py-2">
                <select id="pg-override-${CSS.escape(d.path)}" onchange="remask()"
                    class="bg-white border border-gray-300 rounded-md px-2 py-1 text-xs text-gray-700 focus:border-cv-500 focus:outline-none">
                    ${overrideOpts}
                </select>
            </td>
        </tr>`;
    }).join('');

    container.innerHTML = `<table class="w-full text-sm">
        <thead class="text-xs text-gray-500 uppercase">
            <tr>
                <th class="px-3 py-2 text-left">Field Path</th>
                <th class="px-3 py-2 text-left">Status</th>
                <th class="px-3 py-2 text-left">Resolved By</th>
                <th class="px-3 py-2 text-left">Masking Type</th>
            </tr>
        </thead>
        <tbody>${rows}</tbody>
    </table>`;
}

function renderMaskedOutput(masked) {
    const container = document.getElementById('pg-masked-output');
    if (!container) return;

    const pretty = JSON.stringify(masked, null, 2);
    // Color-code masked values by comparing to original
    const originalPretty = JSON.stringify(lastInputData, null, 2);
    const origLines = originalPretty.split('\n');
    const maskLines = pretty.split('\n');

    let html = '';
    for (let i = 0; i < maskLines.length; i++) {
        const orig = i < origLines.length ? origLines[i] : '';
        const masked_line = maskLines[i];
        if (orig !== masked_line) {
            html += `<div class="bg-red-900/20 text-red-300">${esc(masked_line)}</div>`;
        } else {
            html += `<div class="text-gray-300">${esc(masked_line)}</div>`;
        }
    }

    container.innerHTML = `<div class="flex items-center justify-between mb-2">
        <span class="text-xs text-gray-500 uppercase tracking-wide">Masked Output</span>
        <button onclick="copyMaskedOutput()" class="text-xs text-cv-500 hover:text-cv-400 transition-colors">Copy</button>
    </div>
    <pre class="font-mono text-xs leading-relaxed overflow-auto" style="max-height:340px">${html}</pre>`;
}

export function copyMaskedOutput() {
    if (!lastMaskedData) return;
    navigator.clipboard.writeText(JSON.stringify(lastMaskedData, null, 2));
    showPlaygroundMsg('Copied to clipboard!', false);
    setTimeout(() => showPlaygroundMsg('', false), 2000);
}

function clearResults() {
    lastDecisions = [];
    fieldOverrides = {};
    lastInputData = null;
    lastMaskedData = null;
    const dec = document.getElementById('pg-decisions');
    const out = document.getElementById('pg-masked-output');
    if (dec) dec.innerHTML = '<p class="text-gray-500 text-xs px-3 py-6 text-center">Run an evaluation to see consent decisions.</p>';
    if (out) out.innerHTML = '<p class="text-gray-500 text-xs px-3 py-6 text-center">Masked output will appear here.</p>';
}

function updateWasmBadge(active) {
    const badge = document.getElementById('pg-wasm-badge');
    if (!badge) return;
    if (active) {
        badge.textContent = 'WASM SDK';
        badge.className = 'text-xs px-2 py-0.5 rounded-full bg-green-100 text-green-700';
    } else {
        badge.textContent = 'JS Fallback';
        badge.className = 'text-xs px-2 py-0.5 rounded-full bg-amber-100 text-amber-700';
    }
}

function showPlaygroundMsg(text, isError) {
    const el = document.getElementById('playground-msg');
    if (!el) return;
    if (!text) { el.className = 'hidden'; el.textContent = ''; return; }
    el.className = `text-xs px-3 py-2 rounded-md ${isError ? 'bg-red-50 text-red-600' : 'bg-gray-100 text-gray-500'}`;
    el.textContent = text;
}
