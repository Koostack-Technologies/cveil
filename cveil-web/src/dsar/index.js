import { createApi } from '../shared/index.js';

const api = createApi('/api/v1/dsar');
let currentPage = 1;
const pageSize = 25;
let allowedTransitions = {};

export function init() {
    loadTransitions();
    loadSummary();
    loadRequests();

    // Reset pagination when filters change
    document.getElementById('dsar-filter-status')?.addEventListener('change', () => { currentPage = 1; loadRequests(); });
    document.getElementById('dsar-filter-type')?.addEventListener('change', () => { currentPage = 1; loadRequests(); });
}

async function loadTransitions() {
    try {
        allowedTransitions = await api('/transitions');
    } catch (_) { /* use empty map as fallback */ }
}

// -- Summary cards --------------------------------------------------------

export async function loadSummary() {
    try {
        const data = await api('/summary');
        const counts = {};
        (data.statuses || []).forEach(s => { counts[s.status] = s.count; });

        setText('dsar-count-pending', (counts['PENDING'] || 0) + (counts['IN_REVIEW'] || 0));
        setText('dsar-count-in-progress', (counts['IN_PROGRESS'] || 0) + (counts['APPROVED'] || 0));
        setText('dsar-count-completed', counts['COMPLETED'] || 0);

        // Overdue count loaded from request list filtering (client-side)
        setText('dsar-count-overdue', '—');
    } catch (_) { /* silent */ }
}

// -- Request table --------------------------------------------------------

export async function loadRequests() {
    const body = document.getElementById('dsar-table-body');
    if (!body) return;

    const status = document.getElementById('dsar-filter-status')?.value || '';
    const type = document.getElementById('dsar-filter-type')?.value || '';
    const params = `?page=${currentPage}&pageSize=${pageSize}`
        + (status ? `&status=${status}` : '')
        + (type ? `&type=${type}` : '');

    try {
        const data = await api('/requests' + params);
        const requests = data.requests || [];
        const total = data.total || 0;

        if (!requests.length) {
            body.innerHTML = '<tr><td colspan="7" class="py-12 text-center text-on-surface-muted">No DSAR requests found.</td></tr>';
        } else {
            body.innerHTML = requests.map(r => {
                const isOverdue = r.dueDate && new Date(r.dueDate) < new Date() && !['COMPLETED', 'REJECTED', 'CANCELLED'].includes(r.status);
                return `<tr class="text-sm ${isOverdue ? 'bg-error-container' : ''}">
                    <td class="py-3 px-4">
                        <div class="font-medium text-on-surface">${esc(r.requesterEmail)}</div>
                        ${r.requesterName ? `<div class="text-xs text-on-surface-muted">${esc(r.requesterName)}</div>` : ''}
                    </td>
                    <td class="py-3 px-4"><span class="px-2 py-0.5 text-xs font-medium rounded-md bg-surface-container text-on-surface">${esc(r.requestType)}</span></td>
                    <td class="py-3 px-4">${statusBadge(r.status)}</td>
                    <td class="py-3 px-4 text-on-surface-muted font-technical text-xs">${esc(r.subjectId || '—')}</td>
                    <td class="py-3 px-4 text-xs text-on-surface-muted ${isOverdue ? 'text-error font-medium' : ''}">${r.dueDate ? new Date(r.dueDate).toLocaleDateString() : '—'}</td>
                    <td class="py-3 px-4 text-xs text-on-surface-muted">${new Date(r.createdAt).toLocaleDateString()}</td>
                    <td class="py-3 px-4">
                        <button onclick="CveilWeb.dsar.showDetail('${r.id}')" class="text-primary hover:text-primary text-xs font-medium">View</button>
                    </td>
                </tr>`;
            }).join('');
        }

        // Pagination info
        const start = (currentPage - 1) * pageSize + 1;
        const end = Math.min(start + requests.length - 1, total);
        setText('dsar-page-info', `${start}–${end} of ${total}`);
        document.getElementById('dsar-prev').disabled = currentPage <= 1;
        document.getElementById('dsar-next').disabled = end >= total;
    } catch (e) {
        body.innerHTML = `<tr><td colspan="7" class="py-12 text-center text-error">${esc(e.message)}</td></tr>`;
    }
}

export function prevPage() { currentPage = Math.max(1, currentPage - 1); loadRequests(); }
export function nextPage() { currentPage++; loadRequests(); }

// -- Create request -------------------------------------------------------

export function showCreateModal() {
    document.getElementById('dsar-create-modal')?.classList.remove('hidden');
}

export function hideCreateModal() {
    document.getElementById('dsar-create-modal')?.classList.add('hidden');
    document.getElementById('dsar-create-form')?.reset();
}

export async function createRequest(event) {
    event.preventDefault();
    const form = event.target;
    const data = Object.fromEntries(new FormData(form));

    try {
        await api('/requests', {
            method: 'POST',
            body: JSON.stringify(data)
        });
        hideCreateModal();
        loadSummary();
        loadRequests();
    } catch (e) {
        alert('Failed to create request: ' + e.message);
    }
}

// -- Detail view ----------------------------------------------------------

export async function showDetail(requestId) {
    const content = document.getElementById('dsar-detail-content');
    document.getElementById('dsar-detail-modal')?.classList.remove('hidden');
    content.innerHTML = '<p class="text-on-surface-muted">Loading...</p>';

    try {
        const [request, activity] = await Promise.all([
            api('/requests/' + requestId),
            api('/requests/' + requestId + '/activity')
        ]);

        content.innerHTML = `
            <div class="space-y-4">
                <div class="grid grid-cols-2 gap-3 text-sm">
                    <div><span class="text-on-surface-muted">Type:</span> <span class="font-medium">${esc(request.requestType)}</span></div>
                    <div><span class="text-on-surface-muted">Status:</span> ${statusBadge(request.status)}</div>
                    <div><span class="text-on-surface-muted">Email:</span> ${esc(request.requesterEmail)}</div>
                    <div><span class="text-on-surface-muted">Name:</span> ${esc(request.requesterName || '—')}</div>
                    <div><span class="text-on-surface-muted">Subject:</span> <code class="text-xs">${esc(request.subjectId || '—')}</code></div>
                    <div><span class="text-on-surface-muted">Due:</span> ${request.dueDate ? new Date(request.dueDate).toLocaleDateString() : '—'}</div>
                </div>
                ${request.notes ? `<div class="text-sm"><span class="text-on-surface-muted">Notes:</span><p class="mt-1 text-on-surface bg-surface-container rounded-md p-2 text-xs">${esc(request.notes)}</p></div>` : ''}
                ${request.adminNotes ? `<div class="text-sm"><span class="text-on-surface-muted">Admin Notes:</span><p class="mt-1 text-on-surface bg-[#1e2a4a] rounded-md p-2 text-xs">${esc(request.adminNotes)}</p></div>` : ''}

                <div class="border-t border-ghost-border pt-4">
                    <div class="flex items-center gap-2 mb-3">
                        <label class="text-sm text-on-surface-muted">Update Status:</label>
                        <select id="dsar-status-select" class="text-sm input-field">
                            ${(() => {
                                const allowed = allowedTransitions[request.status] || [];
                                if (!allowed.length) return `<option value="${esc(request.status)}" selected>${esc(request.status)} (final)</option>`;
                                return allowed.map(s =>
                                    `<option value="${esc(s)}">${esc(s)}</option>`
                                ).join('');
                            })()}
                        </select>
                        <button onclick="CveilWeb.dsar.updateStatus('${request.id}')" class="btn-primary text-xs"${(allowedTransitions[request.status] || []).length ? '' : ' disabled'}>Update</button>
                    </div>
                </div>

                <div class="border-t border-ghost-border pt-4">
                    <h3 class="text-sm font-medium text-on-surface mb-2">Activity Log</h3>
                    ${(activity || []).length ? activity.map(a => `
                        <div class="flex gap-2 text-xs py-1.5">
                            <span class="text-on-surface-muted whitespace-nowrap">${new Date(a.createdAt).toLocaleString()}</span>
                            <span class="px-1.5 py-0.5 bg-surface-container rounded-md font-technical">${esc(a.action)}</span>
                            <span class="text-on-surface-muted">${esc(a.details || '')}</span>
                        </div>
                    `).join('') : '<p class="text-xs text-on-surface-muted">No activity yet.</p>'}
                </div>
            </div>
        `;
    } catch (e) {
        content.innerHTML = `<p class="text-error">${esc(e.message)}</p>`;
    }
}

export function hideDetailModal() {
    document.getElementById('dsar-detail-modal')?.classList.add('hidden');
}

export async function updateStatus(requestId) {
    const status = document.getElementById('dsar-status-select')?.value;
    if (!status) return;

    try {
        await api('/requests/' + requestId + '/status', {
            method: 'PUT',
            body: JSON.stringify({ status })
        });
        hideDetailModal();
        loadSummary();
        loadRequests();
    } catch (e) {
        alert('Failed to update status: ' + e.message);
    }
}

// -- Helpers --------------------------------------------------------------

function statusBadge(status) {
    const colors = {
        PENDING: 'bg-warning-container text-on-warning-container',
        IN_REVIEW: 'chip-info',
        APPROVED: 'bg-[#1e1a4a] text-[#c7d2fe]',
        IN_PROGRESS: 'chip-info',
        COMPLETED: 'chip-safe',
        REJECTED: 'chip-critical',
        CANCELLED: 'chip-neutral'
    };
    const cls = colors[status] || 'chip-neutral';
    return `<span class="px-2 py-0.5 text-xs font-medium rounded-full font-technical uppercase ${cls}">${status}</span>`;
}

function setText(id, text) {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
}

function esc(s) {
    if (!s) return '';
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
