const API = '/api/v1/governance';
const CATALOGUE_API = '/api/v1/catalogue';

// Catalogue caches for policy form dropdowns
let fieldsCache = [];
let categoriesCache = [];

async function loadCatalogue() {
    try {
        const [fields, categories] = await Promise.all([
            fetch(CATALOGUE_API + '/fields').then(r => r.ok ? r.json() : []),
            fetch(CATALOGUE_API + '/categories').then(r => r.ok ? r.json() : []),
        ]);
        fieldsCache = fields;
        categoriesCache = categories;
    } catch (e) {
        console.warn('Failed to load catalogue data:', e);
    }
}

// ── Tab switching ──────────────────────────────────────────────
function showTab(name) {
    document.querySelectorAll('[id^="section-"]').forEach(s => s.classList.add('hidden'));
    document.querySelectorAll('.tab-btn').forEach(b => {
        b.classList.remove('border-cv-500', 'text-cv-500');
        b.classList.add('border-transparent', 'text-gray-400');
    });
    document.getElementById('section-' + name).classList.remove('hidden');
    const btn = document.getElementById('tab-' + name);
    btn.classList.add('border-cv-500', 'text-cv-500');
    btn.classList.remove('border-transparent', 'text-gray-400');
    window.location.hash = name;
}

// ── Helpers ────────────────────────────────────────────────────
function esc(s) { const d = document.createElement('div'); d.textContent = s; return d.innerHTML; }

async function api(path, opts = {}) {
    const headers = { ...opts.headers };
    if (opts.body) headers['Content-Type'] = 'application/json';
    const res = await fetch(API + path, { ...opts, headers });
    if (!res.ok) {
        const err = await res.json().catch(() => ({ message: res.statusText }));
        throw new Error(err.message || res.statusText);
    }
    if (res.status === 204) return null;
    return res.json();
}

function badge(text, color = 'gray') {
    const colors = {
        gray: 'bg-gray-700 text-gray-300',
        teal: 'bg-cv-900/50 text-cv-500 border border-cv-500/30',
        blue: 'bg-blue-900/50 text-blue-400 border border-blue-500/30',
        purple: 'bg-purple-900/50 text-purple-400 border border-purple-500/30',
    };
    return `<span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${colors[color] || colors.gray}">${esc(text)}</span>`;
}

function showMsg(el, text, isError = false) {
    el.textContent = text;
    el.className = 'text-sm mt-3 ' + (isError ? 'text-red-400' : 'text-green-400');
    el.classList.remove('hidden');
    setTimeout(() => el.classList.add('hidden'), 4000);
}

// ── Slide-out Panel ────────────────────────────────────────────
function openPanel(type, data) {
    const overlay = document.getElementById('panel-overlay');
    const panel = document.getElementById('panel');
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
    overlay.classList.remove('hidden');
    requestAnimationFrame(() => panel.classList.remove('translate-x-full'));
}

function closePanel() {
    document.getElementById('panel').classList.add('translate-x-full');
    setTimeout(() => document.getElementById('panel-overlay').classList.add('hidden'), 300);
}

// ── Delete Modal ───────────────────────────────────────────────
let pendingDeleteFn = null;
function confirmDelete(msg, fn) {
    document.getElementById('delete-modal-msg').textContent = msg;
    document.getElementById('delete-modal').classList.remove('hidden');
    pendingDeleteFn = fn;
}
function closeDeleteModal() {
    document.getElementById('delete-modal').classList.add('hidden');
    pendingDeleteFn = null;
}
document.getElementById('delete-modal-confirm').addEventListener('click', async () => {
    if (pendingDeleteFn) { await pendingDeleteFn(); closeDeleteModal(); }
});

// ── Jurisdictions ──────────────────────────────────────────────
let jurisdictionsCache = [];

async function loadJurisdictions() {
    try {
        jurisdictionsCache = await api('/jurisdictions');
        renderJurisdictions();
    } catch (e) {
        document.getElementById('jurisdictions-table').innerHTML =
            `<tr><td colspan="3" class="px-6 py-8 text-center text-red-400">${esc(e.message)}</td></tr>`;
    }
}

function renderJurisdictions() {
    const tb = document.getElementById('jurisdictions-table');
    if (!jurisdictionsCache.length) {
        tb.innerHTML = '<tr><td colspan="3" class="px-6 py-8 text-center text-gray-500">No jurisdictions defined yet.</td></tr>';
        return;
    }
    tb.innerHTML = jurisdictionsCache.map(j => `
        <tr class="hover:bg-gray-800/30 transition-colors">
            <td class="px-6 py-4 font-medium text-white">${esc(j.jurisdictionName)}</td>
            <td class="px-6 py-4 text-gray-400">${esc(j.description || '—')}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='openPanel("jurisdiction-edit", ${JSON.stringify(j).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-cv-500 mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteJurisdiction("${esc(j.jurisdictionName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
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
                    <label class="block text-sm text-gray-400 mb-1">Name</label>
                    <input id="pf-jur-name" value="${n}" ${existing ? 'disabled' : ''} required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none disabled:opacity-50" />
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Description</label>
                    <textarea id="pf-jur-desc" rows="3"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">${d}</textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    ${existing ? 'Update' : 'Create'}
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-400 hover:text-white border border-gray-600 rounded-lg hover:bg-gray-800 transition-colors">Cancel</button>
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
        loadJurisdictions();
    } catch (err) { showMsg(msg, err.message, true); }
}

function deleteJurisdiction(name) {
    confirmDelete(`Delete jurisdiction "${name}"? This cannot be undone.`, async () => {
        try {
            await api(`/jurisdictions/${encodeURIComponent(name)}`, { method: 'DELETE' });
            loadJurisdictions();
        } catch (e) { alert(e.message); }
    });
}

// ── Regulations ────────────────────────────────────────────────
let regulationsCache = [];

async function loadRegulations() {
    try {
        regulationsCache = await api('/regulations');
        renderRegulations();
    } catch (e) {
        document.getElementById('regulations-table').innerHTML =
            `<tr><td colspan="4" class="px-6 py-8 text-center text-red-400">${esc(e.message)}</td></tr>`;
    }
}

function renderRegulations() {
    const tb = document.getElementById('regulations-table');
    if (!regulationsCache.length) {
        tb.innerHTML = '<tr><td colspan="4" class="px-6 py-8 text-center text-gray-500">No regulations defined yet.</td></tr>';
        return;
    }
    tb.innerHTML = regulationsCache.map(r => `
        <tr class="hover:bg-gray-800/30 transition-colors">
            <td class="px-6 py-4 font-medium text-white">${esc(r.regulationName)}</td>
            <td class="px-6 py-4 text-gray-400">${esc(r.description || '—')}</td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(r.activeJurisdictions || []).map(j => badge(j.jurisdictionName, 'teal')).join('')}
                    <button onclick='openPanel("regulation-jurisdictions", ${JSON.stringify(r).replace(/'/g,"&#39;")})'
                        class="inline-flex items-center px-2 py-0.5 rounded text-xs text-gray-500 hover:text-cv-500 border border-dashed border-gray-600 hover:border-cv-500 transition-colors">+ manage</button>
                </div>
            </td>
            <td class="px-6 py-4 text-right">
                <button onclick='deleteRegulation("${esc(r.regulationName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join('');
}

function regulationForm() {
    return `
        <form onsubmit="saveRegulation(event)">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Name</label>
                    <input id="pf-reg-name" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none" />
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Description</label>
                    <textarea id="pf-reg-desc" rows="3"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none"></textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    Create
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-400 hover:text-white border border-gray-600 rounded-lg hover:bg-gray-800 transition-colors">Cancel</button>
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
        loadRegulations();
    } catch (err) { showMsg(msg, err.message, true); }
}

function deleteRegulation(name) {
    confirmDelete(`Delete regulation "${name}"? This cannot be undone.`, async () => {
        try {
            await api(`/regulations/${encodeURIComponent(name)}`, { method: 'DELETE' });
            loadRegulations();
        } catch (e) { alert(e.message); }
    });
}

function regulationJurisdictionsForm(reg) {
    return `
        <p class="text-sm text-gray-400 mb-4">Manage jurisdictions for <strong class="text-white">${esc(reg.regulationName)}</strong></p>
        <div id="rj-list" class="space-y-2 mb-6">
            ${(reg.activeJurisdictions || []).map(j => `
                <div class="flex items-center justify-between bg-gray-800/60 border border-gray-700 rounded-lg px-3 py-2">
                    <span class="text-sm text-white">${esc(j.jurisdictionName)}</span>
                    <button onclick="removeJurisdictionFromRegulation('${esc(reg.regulationName)}','${esc(j.jurisdictionName)}')"
                        class="text-xs text-red-400 hover:text-red-300 transition-colors">Remove</button>
                </div>`).join('') || '<p class="text-sm text-gray-500">No jurisdictions linked.</p>'}
        </div>
        <hr class="border-gray-700 mb-4">
        <p class="text-sm text-gray-400 mb-3">Add a jurisdiction</p>
        <form onsubmit="addJurisdictionToRegulation(event, '${esc(reg.regulationName)}')">
            <div class="flex gap-2">
                <select id="rj-select" class="flex-1 bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                    <option value="">Select…</option>
                </select>
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm rounded-lg transition-colors">Add</button>
            </div>
            <span id="rj-msg" class="hidden"></span>
        </form>
        <script>
            (function() {
                const existing = new Set(${JSON.stringify((reg.activeJurisdictions || []).map(j => j.jurisdictionName))});
                const sel = document.getElementById('rj-select');
                jurisdictionsCache.filter(j => !existing.has(j.jurisdictionName)).forEach(j => {
                    const o = document.createElement('option');
                    o.value = j.jurisdictionName;
                    o.textContent = j.jurisdictionName;
                    sel.appendChild(o);
                });
            })();
        </script>`;
}

async function addJurisdictionToRegulation(e, regName) {
    e.preventDefault();
    const sel = document.getElementById('rj-select');
    const jurName = sel.value;
    if (!jurName) return;
    try {
        await api('/regulations/jurisdictions', { method: 'POST', body: JSON.stringify({ regulationName: regName, jurisdictionName: jurName }) });
        closePanel();
        await loadRegulations();
        const updated = regulationsCache.find(r => r.regulationName === regName);
        if (updated) openPanel('regulation-jurisdictions', updated);
    } catch (err) { showMsg(document.getElementById('rj-msg'), err.message, true); }
}

async function removeJurisdictionFromRegulation(regName, jurName) {
    try {
        await api(`/regulations/${encodeURIComponent(regName)}/jurisdictions/${encodeURIComponent(jurName)}`, { method: 'DELETE' });
        closePanel();
        await loadRegulations();
        const updated = regulationsCache.find(r => r.regulationName === regName);
        if (updated) openPanel('regulation-jurisdictions', updated);
    } catch (e) { alert(e.message); }
}

// ── Purposes ───────────────────────────────────────────────────
let purposesCache = [];

async function loadPurposes() {
    try {
        purposesCache = await api('/purposes');
        renderPurposes();
        renderPolicies();
    } catch (e) {
        document.getElementById('purposes-table').innerHTML =
            `<tr><td colspan="5" class="px-6 py-8 text-center text-red-400">${esc(e.message)}</td></tr>`;
    }
}

function renderPurposes() {
    const tb = document.getElementById('purposes-table');
    if (!purposesCache.length) {
        tb.innerHTML = '<tr><td colspan="5" class="px-6 py-8 text-center text-gray-500">No purposes defined yet.</td></tr>';
        return;
    }
    tb.innerHTML = purposesCache.map(p => `
        <tr class="hover:bg-gray-800/30 transition-colors">
            <td class="px-6 py-4 font-medium text-white">${esc(p.purposeName)}</td>
            <td class="px-6 py-4 text-gray-400">${esc(p.description || '—')}</td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(p.regulations || []).map(r => badge(r.regulationName, 'blue')).join('')}
                    <button onclick='openPanel("purpose-regulations", ${JSON.stringify(p).replace(/'/g,"&#39;")})'
                        class="inline-flex items-center px-2 py-0.5 rounded text-xs text-gray-500 hover:text-cv-500 border border-dashed border-gray-600 hover:border-cv-500 transition-colors">+ manage</button>
                </div>
            </td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(p.policies || []).map(pol => badge(pol.policyType === 'FIELD' ? (pol.field ? pol.field.entityName + '/' + pol.field.fieldName : 'field') : (pol.category ? pol.category.categoryName : 'category'), 'purple')).join('')}
                    <span class="text-xs text-gray-500">${(p.policies || []).length}</span>
                </div>
            </td>
            <td class="px-6 py-4 text-right">
                <button onclick='deletePurpose("${esc(p.purposeName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join('');
}

function purposeForm() {
    return `
        <form onsubmit="savePurpose(event)">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Name</label>
                    <input id="pf-pur-name" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none" />
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Description</label>
                    <textarea id="pf-pur-desc" rows="3"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none"></textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    Create
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-400 hover:text-white border border-gray-600 rounded-lg hover:bg-gray-800 transition-colors">Cancel</button>
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
        loadPurposes();
    } catch (err) { showMsg(msg, err.message, true); }
}

function deletePurpose(name) {
    confirmDelete(`Delete purpose "${name}"? This will also remove all linked policies and regulations.`, async () => {
        try {
            await api(`/purposes/${encodeURIComponent(name)}`, { method: 'DELETE' });
            loadPurposes();
        } catch (e) { alert(e.message); }
    });
}

function purposeRegulationsForm(purpose) {
    return `
        <p class="text-sm text-gray-400 mb-4">Manage regulation links for <strong class="text-white">${esc(purpose.purposeName)}</strong></p>
        <div id="pr-list" class="space-y-2 mb-6">
            ${(purpose.regulations || []).map(r => `
                <div class="flex items-center justify-between bg-gray-800/60 border border-gray-700 rounded-lg px-3 py-2">
                    <div>
                        <span class="text-sm text-white">${esc(r.regulationName)}</span>
                        ${r.lawfulBasis ? `<span class="ml-2 text-xs text-gray-500">basis: ${esc(r.lawfulBasis)}</span>` : ''}
                    </div>
                    <button onclick="unlinkPurposeRegulation('${esc(purpose.purposeName)}','${esc(r.regulationName)}')"
                        class="text-xs text-red-400 hover:text-red-300 transition-colors">Unlink</button>
                </div>`).join('') || '<p class="text-sm text-gray-500">No regulations linked.</p>'}
        </div>
        <hr class="border-gray-700 mb-4">
        <p class="text-sm text-gray-400 mb-3">Link a regulation</p>
        <form onsubmit="linkPurposeRegulation(event, '${esc(purpose.purposeName)}')">
            <div class="space-y-3">
                <select id="pr-select" class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                    <option value="">Select regulation…</option>
                </select>
                <input id="pr-basis" placeholder="Lawful basis (e.g. consent, legitimate_interest)"
                    class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none" />
                <div class="grid grid-cols-2 gap-3">
                    <input id="pr-granted" placeholder="On Granted action"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none" />
                    <input id="pr-denied" placeholder="On Denied action"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none" />
                </div>
                <button type="submit" class="w-full px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm rounded-lg transition-colors">Link</button>
            </div>
            <span id="pr-msg" class="hidden"></span>
        </form>
        <script>
            (function() {
                const existing = new Set(${JSON.stringify((purpose.regulations || []).map(r => r.regulationName))});
                const sel = document.getElementById('pr-select');
                regulationsCache.filter(r => !existing.has(r.regulationName)).forEach(r => {
                    const o = document.createElement('option');
                    o.value = r.regulationName;
                    o.textContent = r.regulationName;
                    sel.appendChild(o);
                });
            })();
        </script>`;
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
        await loadPurposes();
        const updated = purposesCache.find(p => p.purposeName === purposeName);
        if (updated) openPanel('purpose-regulations', updated);
    } catch (err) { showMsg(document.getElementById('pr-msg'), err.message, true); }
}

async function unlinkPurposeRegulation(purposeName, regName) {
    try {
        await api(`/purposes/${encodeURIComponent(purposeName)}/regulations/${encodeURIComponent(regName)}`, { method: 'DELETE' });
        closePanel();
        await loadPurposes();
        const updated = purposesCache.find(p => p.purposeName === purposeName);
        if (updated) openPanel('purpose-regulations', updated);
    } catch (e) { alert(e.message); }
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
        tb.innerHTML = '<tr><td colspan="5" class="px-6 py-8 text-center text-gray-500">No policies defined yet. Add policies from the Purposes tab.</td></tr>';
        return;
    }
    tb.innerHTML = allPolicies.map(pol => `
        <tr class="hover:bg-gray-800/30 transition-colors">
            <td class="px-6 py-4 font-medium text-white">${esc(pol.purposeName)}</td>
            <td class="px-6 py-4">${badge(pol.policyType, pol.policyType === 'FIELD' ? 'teal' : 'purple')}</td>
            <td class="px-6 py-4 text-gray-400">${pol.policyType === 'FIELD' ? esc((pol.field ? pol.field.fieldName : '—')) : esc((pol.category ? pol.category.categoryName : '—'))}</td>
            <td class="px-6 py-4 text-gray-400">${pol.policyType === 'FIELD' && pol.field ? esc(pol.field.entityName) : '—'}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='removePolicy("${esc(pol.purposeName)}", ${JSON.stringify(pol).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Remove</button>
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
                    <label class="block text-sm text-gray-400 mb-1">Purpose</label>
                    <select id="pf-pol-purpose" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                        <option value="">Select purpose…</option>
                        ${purposesCache.map(p => `<option value="${esc(p.purposeName)}">${esc(p.purposeName)}</option>`).join('')}
                    </select>
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Policy Type</label>
                    <select id="pf-pol-type" onchange="togglePolicyFields()" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                        <option value="FIELD">Field</option>
                        <option value="CATEGORY">Category</option>
                    </select>
                </div>
                <div id="pf-pol-field-group">
                    <label class="block text-sm text-gray-400 mb-1">Field</label>
                    <select id="pf-pol-field-select" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                        <option value="">Select field…</option>
                        ${fieldOptions}
                    </select>
                    ${!fieldsCache.length ? '<p class="text-xs text-amber-400 mt-1">No fields registered. Add fields in the Catalogue first.</p>' : ''}
                </div>
                <div id="pf-pol-cat-group" class="hidden">
                    <label class="block text-sm text-gray-400 mb-1">Category</label>
                    <select id="pf-pol-cat-select" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                        <option value="">Select category…</option>
                        ${categoryOptions}
                    </select>
                    ${!categoriesCache.length ? '<p class="text-xs text-amber-400 mt-1">No categories registered. Add categories in the Catalogue first.</p>' : ''}
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">Add Policy</button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-400 hover:text-white border border-gray-600 rounded-lg hover:bg-gray-800 transition-colors">Cancel</button>
            </div>
            <span id="pf-pol-msg" class="hidden"></span>
        </form>`;
}

function togglePolicyFields() {
    const type = document.getElementById('pf-pol-type').value;
    document.getElementById('pf-pol-field-group').classList.toggle('hidden', type !== 'FIELD');
    document.getElementById('pf-pol-cat-group').classList.toggle('hidden', type !== 'CATEGORY');
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
        // Wait for async projection to process the event before refreshing
        await new Promise(resolve => setTimeout(resolve, 800));
        await loadPurposes();
    } catch (err) { showMsg(msg, err.message, true); }
}

async function removePolicy(purposeName, pol) {
    confirmDelete(`Remove this ${pol.policyType.toLowerCase()} policy from "${purposeName}"?`, async () => {
        try {
            const body = {
                purposeName,
                policyType: pol.policyType,
                fieldName: pol.field ? pol.field.fieldName : null,
                entityName: pol.field ? pol.field.entityName : null,
                categoryName: pol.category ? pol.category.categoryName : null,
            };
            await api('/purposes/policies', { method: 'DELETE', body: JSON.stringify(body) });
            loadPurposes();
        } catch (e) { alert(e.message); }
    });
}

// ── Init ───────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    loadJurisdictions();
    loadRegulations();
    loadPurposes();
    loadCatalogue();

    // Hash-based tab selection
    const hash = window.location.hash.replace('#', '');
    if (['jurisdictions', 'regulations', 'purposes', 'policies'].includes(hash)) {
        showTab(hash);
    }
});
