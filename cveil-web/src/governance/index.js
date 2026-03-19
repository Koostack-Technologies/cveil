import {
    esc, showTab, createApi, confirmDelete, closeDeleteModal, initDeleteModal,
    activatePanel, closePanel, badge, showMsg, ready,
    retryUntilChanged, setTableLoading,
} from '../shared/index.js';

const api = createApi('/api/v1/governance');
const catalogueApi = createApi('/api/v1/catalogue');

let fieldsCache = [];
let categoriesCache = [];
let jurisdictionsCache = [];
let regulationsCache = [];
let purposesCache = [];

// ── Catalogue data for policy dropdowns ────────────────────────
async function loadCatalogue() {
    try {
        const [fields, categories] = await Promise.all([
            catalogueApi('/fields').catch(() => []),
            catalogueApi('/categories').catch(() => []),
        ]);
        fieldsCache = fields || [];
        categoriesCache = categories || [];
    } catch (e) {
        console.warn('Failed to load catalogue data:', e);
    }
}

// ── Retry-aware reloaders (post-mutation eventual consistency) ──
async function reloadJurisdictions() {
    setTableLoading('jurisdictions-table', true);
    jurisdictionsCache = await retryUntilChanged(() => api('/jurisdictions'), jurisdictionsCache);
    renderJurisdictions();
    setTableLoading('jurisdictions-table', false);
}

async function reloadRegulations() {
    setTableLoading('regulations-table', true);
    regulationsCache = await retryUntilChanged(() => api('/regulations'), regulationsCache);
    renderRegulations();
    setTableLoading('regulations-table', false);
}

async function reloadPurposes() {
    setTableLoading('purposes-table', true);
    setTableLoading('policies-table', true);
    purposesCache = await retryUntilChanged(() => api('/purposes'), purposesCache);
    renderPurposes();
    renderPolicies();
    setTableLoading('purposes-table', false);
    setTableLoading('policies-table', false);
}

// ── Panel ──────────────────────────────────────────────────────
function openPanel(type, data) {
    const title = document.getElementById('panel-title');
    const body = document.getElementById('panel-body');

    const forms = {
        'jurisdiction-add': () => { title.textContent = 'Add Jurisdiction'; body.innerHTML = jurisdictionForm(); },
        'jurisdiction-edit': () => { title.textContent = 'Edit Jurisdiction'; body.innerHTML = jurisdictionForm(data); },
        'regulation-add': () => { title.textContent = 'Add Regulation'; body.innerHTML = regulationForm(); },
        'purpose-add': () => { title.textContent = 'Add Purpose'; body.innerHTML = purposeForm(); },
        'policy-add': () => { title.textContent = 'Add Policy'; body.innerHTML = policyForm(); },
        'regulation-jurisdictions': () => { title.textContent = 'Manage Jurisdictions'; body.innerHTML = regulationJurisdictionsForm(data); },
        'purpose-regulations': () => { title.textContent = 'Manage Regulations'; body.innerHTML = purposeRegulationsForm(data); },
    };

    (forms[type] || (() => { body.innerHTML = ''; }))();
    activatePanel();
}

// ── Jurisdictions ──────────────────────────────────────────────
async function loadJurisdictions() {
    try {
        jurisdictionsCache = await api('/jurisdictions');
        renderJurisdictions();
    } catch (e) {
        document.getElementById('jurisdictions-table').innerHTML =
            `<tr><td colspan="3" class="px-6 py-8 text-center text-error">${esc(e.message)}</td></tr>`;
    }
}

function renderJurisdictions() {
    const tb = document.getElementById('jurisdictions-table');
    if (!jurisdictionsCache.length) {
        tb.innerHTML = '<tr><td colspan="3" class="px-6 py-8 text-center text-on-surface-muted">No jurisdictions defined yet.</td></tr>';
        return;
    }
    tb.innerHTML = jurisdictionsCache.map(j => `
        <tr class="hover:bg-surface-container transition-colors">
            <td class="px-6 py-4 font-medium text-on-surface">${esc(j.jurisdictionName)}</td>
            <td class="px-6 py-4 text-on-surface-muted">${esc(j.description || '—')}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='openPanel("jurisdiction-edit", ${JSON.stringify(j).replace(/'/g,"&#39;")})' class="text-on-surface-muted hover:text-primary mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteJurisdiction("${esc(j.jurisdictionName)}")' class="text-on-surface-muted hover:text-error transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join('');
}

function jurisdictionForm(existing) {
    const n = existing ? esc(existing.jurisdictionName) : '';
    const d = existing ? esc(existing.description || '') : '';
    return `
        <form onsubmit="saveJurisdiction(event, ${existing ? 'true' : 'false'}, '${n}')">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-on-surface-muted mb-1">Name</label>
                    <input id="pf-jur-name" value="${n}" ${existing ? 'disabled' : ''} required
                        class="w-full input-field disabled:opacity-50" />
                </div>
                <div>
                    <label class="block text-sm text-on-surface-muted mb-1">Description</label>
                    <textarea id="pf-jur-desc" rows="3"
                        class="w-full input-field">${d}</textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 btn-primary transition-colors">
                    ${existing ? 'Update' : 'Create'}
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm btn-secondary transition-colors">Cancel</button>
            </div>
            <span id="pf-jur-msg" class="hidden"></span>
        </form>`;
}

async function saveJurisdiction(e, isEdit, origName) {
    e.preventDefault();
    const name = document.getElementById('pf-jur-name').value.trim();
    const desc = document.getElementById('pf-jur-desc').value.trim();
    const msg = document.getElementById('pf-jur-msg');
    try {
        if (isEdit) {
            await api(`/jurisdictions/${encodeURIComponent(origName)}`, { method: 'PUT', body: JSON.stringify({ description: desc }) });
        } else {
            await api('/jurisdictions', { method: 'POST', body: JSON.stringify({ jurisdictionName: name, description: desc }) });
        }
        closePanel();
        await reloadJurisdictions();
    } catch (err) { showMsg(msg, err.message, true); }
}

function deleteJurisdiction(name) {
    confirmDelete(`Delete jurisdiction "${name}"? This cannot be undone.`, async () => {
        await api(`/jurisdictions/${encodeURIComponent(name)}`, { method: 'DELETE' });
        await reloadJurisdictions();
    });
}

// ── Regulations ────────────────────────────────────────────────
async function loadRegulations() {
    try {
        regulationsCache = await api('/regulations');
        renderRegulations();
    } catch (e) {
        document.getElementById('regulations-table').innerHTML =
            `<tr><td colspan="4" class="px-6 py-8 text-center text-error">${esc(e.message)}</td></tr>`;
    }
}

function renderRegulations() {
    const tb = document.getElementById('regulations-table');
    if (!regulationsCache.length) {
        tb.innerHTML = '<tr><td colspan="4" class="px-6 py-8 text-center text-on-surface-muted">No regulations defined yet.</td></tr>';
        return;
    }
    tb.innerHTML = regulationsCache.map(r => `
        <tr class="hover:bg-surface-container transition-colors">
            <td class="px-6 py-4 font-medium text-on-surface">${esc(r.regulationName)}</td>
            <td class="px-6 py-4 text-on-surface-muted">${esc(r.description || '—')}</td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(r.activeJurisdictions || []).map(j => badge(j.jurisdictionName, 'teal')).join('')}
                    <button onclick='openPanel("regulation-jurisdictions", ${JSON.stringify(r).replace(/'/g,"&#39;")})'
                        class="inline-flex items-center px-2 py-0.5 rounded-md text-xs text-on-surface-muted hover:text-primary border border-dashed border-on-surface-muted/20 hover:border-primary/40 transition-colors">+ manage</button>
                </div>
            </td>
            <td class="px-6 py-4 text-right">
                <button onclick='deleteRegulation("${esc(r.regulationName)}")' class="text-on-surface-muted hover:text-error transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join('');
}

function regulationForm() {
    return `
        <form onsubmit="saveRegulation(event)">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-on-surface-muted mb-1">Name</label>
                    <input id="pf-reg-name" required
                        class="w-full input-field" />
                </div>
                <div>
                    <label class="block text-sm text-on-surface-muted mb-1">Description</label>
                    <textarea id="pf-reg-desc" rows="3"
                        class="w-full input-field"></textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 btn-primary transition-colors">
                    Create
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm btn-secondary transition-colors">Cancel</button>
            </div>
            <span id="pf-reg-msg" class="hidden"></span>
        </form>`;
}

async function saveRegulation(e) {
    e.preventDefault();
    const name = document.getElementById('pf-reg-name').value.trim();
    const desc = document.getElementById('pf-reg-desc').value.trim();
    const msg = document.getElementById('pf-reg-msg');
    try {
        await api('/regulations', { method: 'POST', body: JSON.stringify({ regulationName: name, description: desc }) });
        closePanel();
        await reloadRegulations();
    } catch (err) { showMsg(msg, err.message, true); }
}

function deleteRegulation(name) {
    confirmDelete(`Delete regulation "${name}"? This cannot be undone.`, async () => {
        await api(`/regulations/${encodeURIComponent(name)}`, { method: 'DELETE' });
        await reloadRegulations();
    });
}

function regulationJurisdictionsForm(reg) {
    const existing = new Set((reg.activeJurisdictions || []).map(j => j.jurisdictionName));
    const availableOptions = jurisdictionsCache
        .filter(j => !existing.has(j.jurisdictionName))
        .map(j => `<option value="${esc(j.jurisdictionName)}">${esc(j.jurisdictionName)}</option>`)
        .join('');

    return `
        <p class="text-sm text-on-surface-muted mb-4">Manage jurisdictions for <strong class="text-on-surface">${esc(reg.regulationName)}</strong></p>
        <div id="rj-list" class="space-y-2 mb-6">
            ${(reg.activeJurisdictions || []).map(j => `
                <div class="flex items-center justify-between bg-surface-base ghost-border rounded-md px-3 py-2">
                    <span class="text-sm text-on-surface">${esc(j.jurisdictionName)}</span>
                    <button onclick="removeJurisdictionFromRegulation('${esc(reg.regulationName)}','${esc(j.jurisdictionName)}')"
                        class="text-xs text-error hover:text-error transition-colors">Remove</button>
                </div>`).join('') || '<p class="text-sm text-on-surface-muted">No jurisdictions linked.</p>'}
        </div>
        <hr class="border-on-surface-muted/10 mb-4">
        <p class="text-sm text-on-surface-muted mb-3">Add a jurisdiction</p>
        <form onsubmit="addJurisdictionToRegulation(event, '${esc(reg.regulationName)}')">
            <div class="flex gap-2">
                <select id="rj-select" class="flex-1 input-field">
                    <option value="">Select…</option>
                    ${availableOptions}
                </select>
                <button type="submit" class="px-4 py-2 btn-primary transition-colors">Add</button>
            </div>
            <span id="rj-msg" class="hidden"></span>
        </form>`;
}

async function addJurisdictionToRegulation(e, regName) {
    e.preventDefault();
    const sel = document.getElementById('rj-select');
    const jurName = sel.value;
    if (!jurName) return;
    try {
        await api('/regulations/jurisdictions', { method: 'POST', body: JSON.stringify({ regulationName: regName, jurisdictionName: jurName }) });
        closePanel();
        await reloadRegulations();
        const updated = regulationsCache.find(r => r.regulationName === regName);
        if (updated) openPanel('regulation-jurisdictions', updated);
    } catch (err) { showMsg(document.getElementById('rj-msg'), err.message, true); }
}

async function removeJurisdictionFromRegulation(regName, jurName) {
    try {
        await api(`/regulations/${encodeURIComponent(regName)}/jurisdictions/${encodeURIComponent(jurName)}`, { method: 'DELETE' });
        closePanel();
        await reloadRegulations();
        const updated = regulationsCache.find(r => r.regulationName === regName);
        if (updated) openPanel('regulation-jurisdictions', updated);
    } catch (e) { showMsg(document.getElementById('rj-msg'), e.message, true); }
}

// ── Purposes ───────────────────────────────────────────────────
async function loadPurposes() {
    try {
        purposesCache = await api('/purposes');
        renderPurposes();
        renderPolicies();
    } catch (e) {
        document.getElementById('purposes-table').innerHTML =
            `<tr><td colspan="5" class="px-6 py-8 text-center text-error">${esc(e.message)}</td></tr>`;
    }
}

function renderPurposes() {
    const tb = document.getElementById('purposes-table');
    if (!purposesCache.length) {
        tb.innerHTML = '<tr><td colspan="5" class="px-6 py-8 text-center text-on-surface-muted">No purposes defined yet.</td></tr>';
        return;
    }
    tb.innerHTML = purposesCache.map(p => `
        <tr class="hover:bg-surface-container transition-colors">
            <td class="px-6 py-4 font-medium text-on-surface">${esc(p.purposeName)}</td>
            <td class="px-6 py-4 text-on-surface-muted">${esc(p.description || '—')}</td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(p.regulations || []).map(r => badge(r.regulationName, 'blue')).join('')}
                    <button onclick='openPanel("purpose-regulations", ${JSON.stringify(p).replace(/'/g,"&#39;")})'
                        class="inline-flex items-center px-2 py-0.5 rounded-md text-xs text-on-surface-muted hover:text-primary border border-dashed border-on-surface-muted/20 hover:border-primary/40 transition-colors">+ manage</button>
                </div>
            </td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(p.policies || []).map(pol => badge(pol.policyType === 'FIELD' ? (pol.field ? pol.field.entityName + '/' + pol.field.fieldName : 'field') : (pol.category ? pol.category.categoryName : 'category'), 'purple')).join('')}
                    <span class="text-xs text-on-surface-muted">${(p.policies || []).length}</span>
                </div>
            </td>
            <td class="px-6 py-4 text-right">
                <button onclick='deletePurpose("${esc(p.purposeName)}")' class="text-on-surface-muted hover:text-error transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join('');
}

function purposeForm() {
    return `
        <form onsubmit="savePurpose(event)">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-on-surface-muted mb-1">Name</label>
                    <input id="pf-pur-name" required
                        class="w-full input-field" />
                </div>
                <div>
                    <label class="block text-sm text-on-surface-muted mb-1">Description</label>
                    <textarea id="pf-pur-desc" rows="3"
                        class="w-full input-field"></textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 btn-primary transition-colors">
                    Create
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm btn-secondary transition-colors">Cancel</button>
            </div>
            <span id="pf-pur-msg" class="hidden"></span>
        </form>`;
}

async function savePurpose(e) {
    e.preventDefault();
    const name = document.getElementById('pf-pur-name').value.trim();
    const desc = document.getElementById('pf-pur-desc').value.trim();
    const msg = document.getElementById('pf-pur-msg');
    try {
        await api('/purposes', { method: 'POST', body: JSON.stringify({ purposeName: name, description: desc }) });
        closePanel();
        await reloadPurposes();
    } catch (err) { showMsg(msg, err.message, true); }
}

function deletePurpose(name) {
    confirmDelete(`Delete purpose "${name}"? This will also remove all linked policies and regulations.`, async () => {
        await api(`/purposes/${encodeURIComponent(name)}`, { method: 'DELETE' });
        await reloadPurposes();
    });
}

function purposeRegulationsForm(purpose) {
    const existing = new Set((purpose.regulations || []).map(r => r.regulationName));
    const availableOptions = regulationsCache
        .filter(r => !existing.has(r.regulationName))
        .map(r => `<option value="${esc(r.regulationName)}">${esc(r.regulationName)}</option>`)
        .join('');

    return `
        <p class="text-sm text-on-surface-muted mb-4">Manage regulation links for <strong class="text-on-surface">${esc(purpose.purposeName)}</strong></p>
        <div id="pr-list" class="space-y-2 mb-6">
            ${(purpose.regulations || []).map(r => `
                <div class="flex items-center justify-between bg-surface-base ghost-border rounded-md px-3 py-2">
                    <div>
                        <span class="text-sm text-on-surface">${esc(r.regulationName)}</span>
                        ${r.lawfulBasis ? `<span class="ml-2 text-xs text-on-surface-muted">basis: ${esc(r.lawfulBasis)}</span>` : ''}
                    </div>
                    <button onclick="unlinkPurposeRegulation('${esc(purpose.purposeName)}','${esc(r.regulationName)}')"
                        class="text-xs text-error hover:text-error transition-colors">Unlink</button>
                </div>`).join('') || '<p class="text-sm text-on-surface-muted">No regulations linked.</p>'}
        </div>
        <hr class="border-on-surface-muted/10 mb-4">
        <p class="text-sm text-on-surface-muted mb-3">Link a regulation</p>
        <form onsubmit="linkPurposeRegulation(event, '${esc(purpose.purposeName)}')">
            <div class="space-y-3">
                <select id="pr-select" class="w-full input-field">
                    <option value="">Select regulation…</option>
                    ${availableOptions}
                </select>
                <input id="pr-basis" placeholder="Lawful basis (e.g. consent, legitimate_interest)"
                    class="w-full input-field" />
                <div class="grid grid-cols-2 gap-3">
                    <input id="pr-granted" placeholder="On Granted action"
                        class="w-full input-field" />
                    <input id="pr-denied" placeholder="On Denied action"
                        class="w-full input-field" />
                </div>
                <button type="submit" class="w-full px-4 py-2 btn-primary transition-colors">Link</button>
            </div>
            <span id="pr-msg" class="hidden"></span>
        </form>`;
}

async function linkPurposeRegulation(e, purposeName) {
    e.preventDefault();
    const regName = document.getElementById('pr-select').value;
    if (!regName) return;
    const basis = document.getElementById('pr-basis').value.trim() || null;
    const onGranted = document.getElementById('pr-granted').value.trim() || null;
    const onDenied = document.getElementById('pr-denied').value.trim() || null;
    try {
        await api('/purposes/regulations', { method: 'POST', body: JSON.stringify({ purposeName, regulationName: regName, lawfulBasis: basis, onGranted, onDenied }) });
        closePanel();
        await reloadPurposes();
        const updated = purposesCache.find(p => p.purposeName === purposeName);
        if (updated) openPanel('purpose-regulations', updated);
    } catch (err) { showMsg(document.getElementById('pr-msg'), err.message, true); }
}

async function unlinkPurposeRegulation(purposeName, regName) {
    try {
        await api(`/purposes/${encodeURIComponent(purposeName)}/regulations/${encodeURIComponent(regName)}`, { method: 'DELETE' });
        closePanel();
        await reloadPurposes();
        const updated = purposesCache.find(p => p.purposeName === purposeName);
        if (updated) openPanel('purpose-regulations', updated);
    } catch (e) { showMsg(document.getElementById('pr-msg'), e.message, true); }
}

// ── Policies (derived from purposes) ───────────────────────────
function renderPolicies() {
    const tb = document.getElementById('policies-table');
    const allPolicies = [];
    purposesCache.forEach(p => {
        (p.policies || []).forEach(pol => {
            allPolicies.push({ ...pol, purposeName: p.purposeName });
        });
    });

    if (!allPolicies.length) {
        tb.innerHTML = '<tr><td colspan="5" class="px-6 py-8 text-center text-on-surface-muted">No policies defined yet. Add policies from the Purposes tab.</td></tr>';
        return;
    }
    tb.innerHTML = allPolicies.map(pol => `
        <tr class="hover:bg-surface-container transition-colors">
            <td class="px-6 py-4 font-medium text-on-surface">${esc(pol.purposeName)}</td>
            <td class="px-6 py-4">${badge(pol.policyType, pol.policyType === 'FIELD' ? 'teal' : 'purple')}</td>
            <td class="px-6 py-4 text-on-surface-muted">${pol.policyType === 'FIELD' ? esc((pol.field ? pol.field.fieldName : '—')) : esc((pol.category ? pol.category.categoryName : '—'))}</td>
            <td class="px-6 py-4 text-on-surface-muted">${pol.policyType === 'FIELD' && pol.field ? esc(pol.field.entityName) : '—'}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='removePolicy("${esc(pol.purposeName)}", ${JSON.stringify(pol).replace(/'/g,"&#39;")})' class="text-on-surface-muted hover:text-error transition-colors text-xs font-medium">Remove</button>
            </td>
        </tr>`).join('');
}

function policyForm() {
    const fieldOptions = fieldsCache.map(f => {
        const label = (f.entityName ? f.entityName + '/' : '') + f.fieldName;
        const val = JSON.stringify({ fieldName: f.fieldName, entityName: f.entityName || '' }).replace(/"/g, '&quot;');
        return `<option value="${val}">${esc(label)}</option>`;
    }).join('');
    const categoryOptions = categoriesCache.map(c =>
        `<option value="${esc(c.categoryName)}">${esc(c.categoryName)}</option>`
    ).join('');

    return `
        <form onsubmit="addPolicy(event)">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-on-surface-muted mb-1">Purpose</label>
                    <select id="pf-pol-purpose" required
                        class="w-full input-field">
                        <option value="">Select purpose…</option>
                        ${purposesCache.map(p => `<option value="${esc(p.purposeName)}">${esc(p.purposeName)}</option>`).join('')}
                    </select>
                </div>
                <div>
                    <label class="block text-sm text-on-surface-muted mb-1">Policy Type</label>
                    <select id="pf-pol-type" onchange="togglePolicyFields()" required
                        class="w-full input-field">
                        <option value="FIELD">Field</option>
                        <option value="CATEGORY">Category</option>
                    </select>
                </div>
                <div id="pf-pol-field-group">
                    <label class="block text-sm text-on-surface-muted mb-1">Field</label>
                    <select id="pf-pol-field-select" required
                        class="w-full input-field">
                        <option value="">Select field…</option>
                        ${fieldOptions}
                    </select>
                    ${!fieldsCache.length ? '<p class="text-xs text-warning mt-1">No fields registered. Add fields in the Catalogue first.</p>' : ''}
                </div>
                <div id="pf-pol-cat-group" class="hidden">
                    <label class="block text-sm text-on-surface-muted mb-1">Category</label>
                    <select id="pf-pol-cat-select"
                        class="w-full input-field">
                        <option value="">Select category…</option>
                        ${categoryOptions}
                    </select>
                    ${!categoriesCache.length ? '<p class="text-xs text-warning mt-1">No categories registered. Add categories in the Catalogue first.</p>' : ''}
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 btn-primary transition-colors">Add Policy</button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm btn-secondary transition-colors">Cancel</button>
            </div>
            <span id="pf-pol-msg" class="hidden"></span>
        </form>`;
}

function togglePolicyFields() {
    const type = document.getElementById('pf-pol-type').value;
    document.getElementById('pf-pol-field-group').classList.toggle('hidden', type !== 'FIELD');
    document.getElementById('pf-pol-cat-group').classList.toggle('hidden', type !== 'CATEGORY');
    document.getElementById('pf-pol-field-select').required = (type === 'FIELD');
    document.getElementById('pf-pol-cat-select').required = (type === 'CATEGORY');
}

async function addPolicy(e) {
    e.preventDefault();
    const purposeName = document.getElementById('pf-pol-purpose').value;
    const policyType = document.getElementById('pf-pol-type').value;
    const msg = document.getElementById('pf-pol-msg');
    const body = { purposeName, policyType };
    if (policyType === 'FIELD') {
        const fieldVal = document.getElementById('pf-pol-field-select').value;
        if (!fieldVal) { showMsg(msg, 'Please select a field.', true); return; }
        const parsed = JSON.parse(fieldVal);
        body.fieldName = parsed.fieldName;
        body.entityName = parsed.entityName;
        body.categoryName = null;
    } else {
        body.categoryName = document.getElementById('pf-pol-cat-select').value;
        if (!body.categoryName) { showMsg(msg, 'Please select a category.', true); return; }
        body.fieldName = null;
        body.entityName = null;
    }
    try {
        await api('/purposes/policies', { method: 'POST', body: JSON.stringify(body) });
        closePanel();
        await reloadPurposes();
    } catch (err) { showMsg(msg, err.message, true); }
}

async function removePolicy(purposeName, pol) {
    confirmDelete(`Remove this ${pol.policyType.toLowerCase()} policy from "${purposeName}"?`, async () => {
        const body = {
            purposeName,
            policyType: pol.policyType,
            fieldName: pol.field ? pol.field.fieldName : null,
            entityName: pol.field ? pol.field.entityName : null,
            categoryName: pol.category ? pol.category.categoryName : null,
        };
        await api('/purposes/policies', { method: 'DELETE', body: JSON.stringify(body) });
        await reloadPurposes();
    });
}

// ── Init ───────────────────────────────────────────────────────
export function init() {
    Object.assign(window, {
        showTab, openPanel, closePanel, closeDeleteModal,
        saveJurisdiction, deleteJurisdiction,
        saveRegulation, deleteRegulation,
        addJurisdictionToRegulation, removeJurisdictionFromRegulation,
        savePurpose, deletePurpose,
        linkPurposeRegulation, unlinkPurposeRegulation,
        togglePolicyFields, addPolicy, removePolicy,
    });

    initDeleteModal();

    ready(() => {
        loadJurisdictions();
        loadRegulations();
        loadPurposes();
        loadCatalogue();

        const hash = window.location.hash.replace('#', '');
        if (['jurisdictions', 'regulations', 'purposes', 'policies'].includes(hash)) {
            showTab(hash);
        }
    });
}
