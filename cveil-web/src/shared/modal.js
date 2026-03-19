let pendingDeleteFn = null;

export function confirmDelete(msg, fn) {
    document.getElementById('delete-modal-msg').textContent = msg;
    document.getElementById('delete-modal').classList.remove('hidden');
    const errEl = document.getElementById('delete-modal-error');
    if (errEl) { errEl.textContent = ''; errEl.classList.add('hidden'); }
    pendingDeleteFn = fn;
}

export function closeDeleteModal() {
    document.getElementById('delete-modal').classList.add('hidden');
    pendingDeleteFn = null;
}

export function initDeleteModal() {
    const btn = document.getElementById('delete-modal-confirm');
    const originalLabel = btn.textContent;
    btn.addEventListener('click', async () => {
        if (!pendingDeleteFn) return;
        btn.disabled = true;
        btn.textContent = 'Deleting\u2026';
        try {
            await pendingDeleteFn();
            closeDeleteModal();
        } catch (e) {
            let errEl = document.getElementById('delete-modal-error');
            if (!errEl) {
                errEl = document.createElement('p');
                errEl.id = 'delete-modal-error';
                errEl.className = 'text-sm text-error mt-3';
                btn.parentElement.appendChild(errEl);
            }
            errEl.textContent = e.message;
            errEl.classList.remove('hidden');
        } finally {
            btn.disabled = false;
            btn.textContent = originalLabel;
        }
    });
}
