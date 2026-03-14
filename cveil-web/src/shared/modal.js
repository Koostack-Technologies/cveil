let pendingDeleteFn = null;

export function confirmDelete(msg, fn) {
    document.getElementById('delete-modal-msg').textContent = msg;
    document.getElementById('delete-modal').classList.remove('hidden');
    pendingDeleteFn = fn;
}

export function closeDeleteModal() {
    document.getElementById('delete-modal').classList.add('hidden');
    pendingDeleteFn = null;
}

export function initDeleteModal() {
    document.getElementById('delete-modal-confirm').addEventListener('click', async () => {
        if (pendingDeleteFn) { await pendingDeleteFn(); closeDeleteModal(); }
    });
}
