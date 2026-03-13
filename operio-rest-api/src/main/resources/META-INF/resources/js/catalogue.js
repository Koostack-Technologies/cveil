const API = '/api/v1/catalogue';

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
        'entity-add': () => { title.textContent = 'Add Entity'; body.innerHTML = entityForm(); },
        'entity-edit': () => { title.textContent = 'Edit Entity'; body.innerHTML = entityForm(data); },
        'field-add': () => { title.textContent = 'Add Field'; body.innerHTML = fieldForm(); },
        'field-categories': () => { title.textContent = 'Manage Categories'; body.innerHTML = fieldCategoriesForm(data); },
        'category-add': () => { title.textContent = 'Add Category'; body.innerHTML = categoryForm(); },
        'category-edit': () => { title.textContent = 'Edit Category'; body.innerHTML = categoryForm(data); },
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

// ── Entities ───────────────────────────────────────────────────
let entitiesCache = [];

async function loadEntities() {
    try {
        entitiesCache = await api('/entities');
        renderEntities();
    } catch (e) {
        document.getElementById('entities-table').innerHTML =
            `<tr><td colspan="3" class="px-6 py-8 text-center text-red-400">${esc(e.message)}</td></tr>`;
    }
}

function renderEntities() {
    const tb = document.getElementById('entities-table');
    if (!entitiesCache.length) {
        tb.innerHTML = '<tr><td colspan="3" class="px-6 py-8 text-center text-gray-500">No entities defined yet.</td></tr>';
        return;
    }
    tb.innerHTML = entitiesCache.map(e => `
        <tr class="hover:bg-gray-800/30 transition-colors">
            <td class="px-6 py-4 font-medium text-white">${esc(e.entityName)}</td>
            <td class="px-6 py-4 text-gray-400">${esc(e.description || '—')}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='openPanel("entity-edit", ${JSON.stringify(e).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-cv-500 mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteEntity("${esc(e.entityName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join('');
}

function entityForm(existing) {
    const n = existing ? esc(existing.entityName) : '';
    const d = existing ? esc(existing.description || '') : '';
    return `
        <form onsubmit="saveEntity(event, ${existing ? 'true' : 'false'}, '${n}')">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Name</label>
                    <input id="pf-ent-name" value="${n}" ${existing ? 'disabled' : ''} required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none disabled:opacity-50" />
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Description</label>
                    <textarea id="pf-ent-desc" rows="3"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">${d}</textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    ${existing ? 'Update' : 'Create'}
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-400 hover:text-white border border-gray-600 rounded-lg hover:bg-gray-800 transition-colors">Cancel</button>
            </div>
            <span id="pf-ent-msg" class="hidden"></span>
        </form>`;
}

async function saveEntity(e, isEdit, origName) {
    e.preventDefault();
    const name = document.getElementById('pf-ent-name').value.trim();
    const desc = document.getElementById('pf-ent-desc').value.trim();
    const msg = document.getElementById('pf-ent-msg');
    try {
        if (isEdit) {
            await api(`/entities/${encodeURIComponent(origName)}`, { method: 'PUT', body: JSON.stringify({ description: desc }) });
        } else {
            await api('/entities', { method: 'POST', body: JSON.stringify({ entityName: name, description: desc }) });
        }
        closePanel();
        loadEntities();
    } catch (err) { showMsg(msg, err.message, true); }
}

function deleteEntity(name) {
    confirmDelete(`Delete entity "${name}"? This cannot be undone. Entities with fields must have their fields removed first.`, async () => {
        try {
            await api(`/entities/${encodeURIComponent(name)}`, { method: 'DELETE' });
            loadEntities();
        } catch (e) { alert(e.message); }
    });
}

// ── Fields ─────────────────────────────────────────────────────
let fieldsCache = [];

async function loadFields() {
    try {
        fieldsCache = await api('/fields');
        renderFields();
    } catch (e) {
        document.getElementById('fields-table').innerHTML =
            `<tr><td colspan="5" class="px-6 py-8 text-center text-red-400">${esc(e.message)}</td></tr>`;
    }
}

function renderFields() {
    const tb = document.getElementById('fields-table');
    if (!fieldsCache.length) {
        tb.innerHTML = '<tr><td colspan="5" class="px-6 py-8 text-center text-gray-500">No fields defined yet.</td></tr>';
        return;
    }
    tb.innerHTML = fieldsCache.map(f => `
        <tr class="hover:bg-gray-800/30 transition-colors">
            <td class="px-6 py-4 font-medium text-white">${esc(f.fieldName)}</td>
            <td class="px-6 py-4 text-gray-400">${f.entity ? esc(f.entity.entityName) : '<span class="text-gray-600">—</span>'}</td>
            <td class="px-6 py-4 text-gray-400">${esc(f.description || '—')}</td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(f.categories || []).map(c => badge(c.categoryName, 'purple')).join('')}
                    <button onclick='openPanel("field-categories", ${JSON.stringify(f).replace(/'/g,"&#39;")})'
                        class="inline-flex items-center px-2 py-0.5 rounded text-xs text-gray-500 hover:text-cv-500 border border-dashed border-gray-600 hover:border-cv-500 transition-colors">+ manage</button>
                </div>
            </td>
            <td class="px-6 py-4 text-right">
                <button onclick='deleteField("${f.entity ? esc(f.entity.entityName) : ''}", "${esc(f.fieldName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join('');
}

function fieldForm() {
    const entityOptions = entitiesCache.map(e =>
        `<option value="${esc(e.entityName)}">${esc(e.entityName)}</option>`
    ).join('');
    return `
        <form onsubmit="saveField(event)">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Field Name</label>
                    <input id="pf-fld-name" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none" />
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Entity</label>
                    <select id="pf-fld-entity" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                        <option value="">Select entity…</option>
                        ${entityOptions}
                    </select>
                    ${!entitiesCache.length ? '<p class="text-xs text-amber-400 mt-1">No entities registered. Add an entity first.</p>' : ''}
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Description</label>
                    <textarea id="pf-fld-desc" rows="3"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none"></textarea>
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Entity Description <span class="text-gray-600">(optional, used if entity is new)</span></label>
                    <textarea id="pf-fld-ent-desc" rows="2"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none"></textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">Create</button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-400 hover:text-white border border-gray-600 rounded-lg hover:bg-gray-800 transition-colors">Cancel</button>
            </div>
            <span id="pf-fld-msg" class="hidden"></span>
        </form>`;
}

async function saveField(e) {
    e.preventDefault();
    const fieldName = document.getElementById('pf-fld-name').value.trim();
    const entityName = document.getElementById('pf-fld-entity').value;
    const description = document.getElementById('pf-fld-desc').value.trim() || null;
    const entityDescription = document.getElementById('pf-fld-ent-desc').value.trim() || null;
    const msg = document.getElementById('pf-fld-msg');
    try {
        await api('/fields', { method: 'POST', body: JSON.stringify({ fieldName, entityName, description, entityDescription }) });
        closePanel();
        loadFields();
        loadEntities();
    } catch (err) { showMsg(msg, err.message, true); }
}

function deleteField(entityName, fieldName) {
    confirmDelete(`Delete field "${fieldName}" from entity "${entityName}"? This cannot be undone.`, async () => {
        try {
            await api(`/fields/${encodeURIComponent(entityName)}/${encodeURIComponent(fieldName)}`, { method: 'DELETE' });
            loadFields();
        } catch (e) { alert(e.message); }
    });
}

// ── Field–Category Management ──────────────────────────────────
let categoriesCache = [];

async function loadCategories() {
    try {
        categoriesCache = await api('/categories');
        renderCategories();
    } catch (e) {
        document.getElementById('categories-table').innerHTML =
            `<tr><td colspan="3" class="px-6 py-8 text-center text-red-400">${esc(e.message)}</td></tr>`;
    }
}

function fieldCategoriesForm(field) {
    const entityName = field.entity ? field.entity.entityName : '';
    return `
        <p class="text-sm text-gray-400 mb-4">Manage categories for field <strong class="text-white">${esc(field.fieldName)}</strong> on entity <strong class="text-white">${esc(entityName)}</strong></p>
        <div id="fc-list" class="space-y-2 mb-6">
            ${(field.categories || []).map(c => `
                <div class="flex items-center justify-between bg-gray-800/60 border border-gray-700 rounded-lg px-3 py-2">
                    <span class="text-sm text-white">${esc(c.categoryName)}</span>
                    <button onclick="unassignFieldCategory('${esc(entityName)}','${esc(field.fieldName)}','${esc(c.categoryName)}')"
                        class="text-xs text-red-400 hover:text-red-300 transition-colors">Remove</button>
                </div>`).join('') || '<p class="text-sm text-gray-500">No categories assigned.</p>'}
        </div>
        <hr class="border-gray-700 mb-4">
        <p class="text-sm text-gray-400 mb-3">Assign a category</p>
        <form onsubmit="assignFieldCategory(event, '${esc(entityName)}', '${esc(field.fieldName)}')">
            <div class="flex gap-2">
                <select id="fc-select" class="flex-1 bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                    <option value="">Select…</option>
                </select>
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm rounded-lg transition-colors">Assign</button>
            </div>
            <span id="fc-msg" class="hidden"></span>
        </form>
        <script>
            (function() {
                const existing = new Set(${JSON.stringify((field.categories || []).map(c => c.categoryName))});
                const sel = document.getElementById('fc-select');
                categoriesCache.filter(c => !existing.has(c.categoryName)).forEach(c => {
                    const o = document.createElement('option');
                    o.value = c.categoryName;
                    o.textContent = c.categoryName;
                    sel.appendChild(o);
                });
            })();
        </script>`;
}

async function assignFieldCategory(e, entityName, fieldName) {
    e.preventDefault();
    const categoryName = document.getElementById('fc-select').value;
    if (!categoryName) return;
    try {
        await api('/fields/categories', { method: 'POST', body: JSON.stringify({ fieldName, entityName, categoryName }) });
        closePanel();
        await loadFields();
        const updated = fieldsCache.find(f => f.fieldName === fieldName && f.entity && f.entity.entityName === entityName);
        if (updated) openPanel('field-categories', updated);
    } catch (err) { showMsg(document.getElementById('fc-msg'), err.message, true); }
}

async function unassignFieldCategory(entityName, fieldName, categoryName) {
    try {
        await api(`/fields/${encodeURIComponent(entityName)}/${encodeURIComponent(fieldName)}/categories/${encodeURIComponent(categoryName)}`, { method: 'DELETE' });
        closePanel();
        await loadFields();
        const updated = fieldsCache.find(f => f.fieldName === fieldName && f.entity && f.entity.entityName === entityName);
        if (updated) openPanel('field-categories', updated);
    } catch (e) { alert(e.message); }
}

// ── Categories ─────────────────────────────────────────────────
function renderCategories() {
    const tb = document.getElementById('categories-table');
    if (!categoriesCache.length) {
        tb.innerHTML = '<tr><td colspan="3" class="px-6 py-8 text-center text-gray-500">No categories defined yet.</td></tr>';
        return;
    }
    tb.innerHTML = categoriesCache.map(c => `
        <tr class="hover:bg-gray-800/30 transition-colors">
            <td class="px-6 py-4 font-medium text-white">${esc(c.categoryName)}</td>
            <td class="px-6 py-4 text-gray-400">${esc(c.description || '—')}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='openPanel("category-edit", ${JSON.stringify(c).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-cv-500 mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteCategory("${esc(c.categoryName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join('');
}

function categoryForm(existing) {
    const n = existing ? esc(existing.categoryName) : '';
    const d = existing ? esc(existing.description || '') : '';
    return `
        <form onsubmit="saveCategory(event, ${existing ? 'true' : 'false'}, '${n}')">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Name</label>
                    <input id="pf-cat-name" value="${n}" ${existing ? 'disabled' : ''} required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none disabled:opacity-50" />
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Description</label>
                    <textarea id="pf-cat-desc" rows="3"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">${d}</textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    ${existing ? 'Update' : 'Create'}
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-400 hover:text-white border border-gray-600 rounded-lg hover:bg-gray-800 transition-colors">Cancel</button>
            </div>
            <span id="pf-cat-msg" class="hidden"></span>
        </form>`;
}

async function saveCategory(e, isEdit, origName) {
    e.preventDefault();
    const name = document.getElementById('pf-cat-name').value.trim();
    const desc = document.getElementById('pf-cat-desc').value.trim();
    const msg = document.getElementById('pf-cat-msg');
    try {
        if (isEdit) {
            await api(`/categories/${encodeURIComponent(origName)}`, { method: 'PUT', body: JSON.stringify({ description: desc }) });
        } else {
            await api('/categories', { method: 'POST', body: JSON.stringify({ categoryName: name, description: desc }) });
        }
        closePanel();
        loadCategories();
    } catch (err) { showMsg(msg, err.message, true); }
}

function deleteCategory(name) {
    confirmDelete(`Delete category "${name}"? This cannot be undone. Categories still assigned to fields must be unassigned first.`, async () => {
        try {
            await api(`/categories/${encodeURIComponent(name)}`, { method: 'DELETE' });
            loadCategories();
        } catch (e) { alert(e.message); }
    });
}

// ── Init ───────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    loadEntities();
    loadFields();
    loadCategories();

    // Hash-based tab selection
    const hash = window.location.hash.replace('#', '');
    if (['entities', 'fields', 'categories'].includes(hash)) {
        showTab(hash);
    }
});
