import { createApi } from '../shared/api.js';

const api = createApi('/api/v1/webhooks/endpoints');

const EVENT_TYPES = [
    '*',
    'test.ping',
    'consent.subject.granted',
    'consent.subject.revoked',
    'consent.subject.all-revoked',
    'consent.subject-entity.registered',
    'consent.subject-entity.removed',
    'consent.dsar.data-deleted',
    'consent.dsar.shredded',
    'consent.regulation.registered',
    'consent.regulation.updated',
    'consent.regulation.removed',
    'consent.purpose.registered',
    'consent.purpose.updated',
    'consent.purpose.removed',
    'consent.jurisdiction.registered',
    'consent.jurisdiction.updated',
    'consent.jurisdiction.removed',
];

let endpoints = [];

export async function init() {
    await loadEndpoints();
}

async function loadEndpoints() {
    const loading = document.getElementById('webhooks-loading');
    const empty = document.getElementById('webhooks-empty');
    const wrap = document.getElementById('webhooks-table-wrap');
    try {
        endpoints = await api('');
        if (loading) loading.classList.add('hidden');

        if (!endpoints || endpoints.length === 0) {
            empty.classList.remove('hidden');
            wrap.classList.add('hidden');
        } else {
            empty.classList.add('hidden');
            wrap.classList.remove('hidden');
            renderTable();
        }
    } catch (e) {
        if (loading) loading.textContent = 'Failed to load endpoints.';
        console.error('Failed to load webhook endpoints', e);
    }
}

function esc(str) {
    const d = document.createElement('div');
    d.textContent = str;
    return d.innerHTML;
}

function renderTable() {
    const tbody = document.getElementById('webhooks-tbody');
    tbody.innerHTML = endpoints.map(ep => `
        <tr class="border-b border-surface-high last:border-0">
            <td class="px-4 py-3">
                <div class="font-mono text-xs truncate max-w-xs" title="${esc(ep.url)}">${esc(ep.url)}</div>
                ${ep.description ? `<div class="text-xs text-on-surface-muted mt-0.5">${esc(ep.description)}</div>` : ''}
            </td>
            <td class="px-4 py-3">
                <span class="text-xs text-on-surface-muted">${ep.events && ep.events.includes('*') ? 'All events' : (ep.events || []).length + ' event(s)'}</span>
            </td>
            <td class="px-4 py-3">
                ${ep.enabled
                    ? '<span class="inline-flex items-center gap-1 text-xs font-medium text-green-400"><span class="w-1.5 h-1.5 rounded-full bg-green-400"></span>Active</span>'
                    : '<span class="inline-flex items-center gap-1 text-xs font-medium text-on-surface-muted"><span class="w-1.5 h-1.5 rounded-full bg-on-surface-muted"></span>Disabled</span>'}
            </td>
            <td class="px-4 py-3 text-right">
                <div class="flex items-center justify-end gap-2">
                    <button onclick="CveilWeb.webhooks.testEndpoint('${ep.id}')" class="text-xs text-on-surface-variant hover:text-on-surface transition-colors" title="Send test ping">Test</button>
                    <button onclick="CveilWeb.webhooks.showEditModal('${ep.id}')" class="text-xs text-on-surface-variant hover:text-on-surface transition-colors" title="Edit">Edit</button>
                    <button onclick="CveilWeb.webhooks.rotateSecret('${ep.id}')" class="text-xs text-on-surface-variant hover:text-on-surface transition-colors" title="Rotate secret">Rotate</button>
                    <button onclick="CveilWeb.webhooks.deleteEndpoint('${ep.id}')" class="text-xs text-red-400 hover:text-red-300 transition-colors" title="Delete">Delete</button>
                </div>
            </td>
        </tr>
    `).join('');
}

function populateEventCheckboxes(selected) {
    const container = document.getElementById('webhook-events');
    const sel = new Set(selected || []);
    container.innerHTML = EVENT_TYPES.map(e => `
        <label class="flex items-center gap-2 text-xs cursor-pointer">
            <input type="checkbox" value="${e}" class="rounded" ${sel.has(e) ? 'checked' : ''}>
            <span class="font-mono">${e === '*' ? '* (all)' : e}</span>
        </label>
    `).join('');
}

function getSelectedEvents() {
    const boxes = document.querySelectorAll('#webhook-events input[type="checkbox"]:checked');
    return Array.from(boxes).map(cb => cb.value);
}

export function showCreateModal() {
    document.getElementById('webhook-edit-id').value = '';
    document.getElementById('webhook-url').value = '';
    document.getElementById('webhook-desc').value = '';
    document.getElementById('webhook-modal-title').textContent = 'Add webhook endpoint';
    document.getElementById('webhook-submit-btn').textContent = 'Create';
    document.getElementById('webhook-form-error').classList.add('hidden');
    populateEventCheckboxes(['*']);
    document.getElementById('webhook-modal').classList.remove('hidden');
}

export function showEditModal(id) {
    const ep = endpoints.find(e => e.id === id);
    if (!ep) return;
    document.getElementById('webhook-edit-id').value = id;
    document.getElementById('webhook-url').value = ep.url || '';
    document.getElementById('webhook-desc').value = ep.description || '';
    document.getElementById('webhook-modal-title').textContent = 'Edit webhook endpoint';
    document.getElementById('webhook-submit-btn').textContent = 'Save';
    document.getElementById('webhook-form-error').classList.add('hidden');
    populateEventCheckboxes(ep.events || []);
    document.getElementById('webhook-modal').classList.remove('hidden');
}

export function closeModal() {
    document.getElementById('webhook-modal').classList.add('hidden');
}

export async function submitForm(e) {
    e.preventDefault();
    const errEl = document.getElementById('webhook-form-error');
    errEl.classList.add('hidden');

    const editId = document.getElementById('webhook-edit-id').value;
    const url = document.getElementById('webhook-url').value.trim();
    const desc = document.getElementById('webhook-desc').value.trim();
    const events = getSelectedEvents();

    if (events.length === 0) {
        errEl.textContent = 'Select at least one event type.';
        errEl.classList.remove('hidden');
        return false;
    }

    try {
        if (editId) {
            await api('/' + editId, {
                method: 'PUT',
                body: JSON.stringify({ url, events, description: desc || null, enabled: true }),
            });
        } else {
            const result = await api('', {
                method: 'POST',
                body: JSON.stringify({ url, events, description: desc || null }),
            });
            if (result && result.secret) {
                closeModal();
                showSecret(result.secret);
                await loadEndpoints();
                return false;
            }
        }
        closeModal();
        await loadEndpoints();
    } catch (err) {
        errEl.textContent = err.message || 'Something went wrong.';
        errEl.classList.remove('hidden');
    }
    return false;
}

function showSecret(secret) {
    document.getElementById('secret-value').textContent = secret;
    document.getElementById('secret-modal').classList.remove('hidden');
}

export async function testEndpoint(id) {
    try {
        const result = await api('/' + id + '/test', { method: 'POST' });
        alert(result && result.delivered ? 'Test ping delivered successfully.' : 'Test delivery failed. Check that the endpoint is reachable.');
    } catch (err) {
        alert('Test failed: ' + (err.message || 'Unknown error'));
    }
}

export async function rotateSecret(id) {
    if (!confirm('Rotate the signing secret? The old secret will remain valid for a short grace period.')) return;
    try {
        const result = await api('/' + id + '/rotate-secret', { method: 'POST' });
        if (result && result.secret) {
            showSecret(result.secret);
        }
    } catch (err) {
        alert('Rotation failed: ' + (err.message || 'Unknown error'));
    }
}

export async function deleteEndpoint(id) {
    if (!confirm('Delete this webhook endpoint? This cannot be undone.')) return;
    try {
        await api('/' + id, { method: 'DELETE' });
        await loadEndpoints();
    } catch (err) {
        alert('Delete failed: ' + (err.message || 'Unknown error'));
    }
}
