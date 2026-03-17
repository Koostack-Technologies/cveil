export function activatePanel() {
    const overlay = document.getElementById('panel-overlay');
    const panel = document.getElementById('panel');
    overlay.classList.remove('hidden');
    requestAnimationFrame(() => panel.classList.remove('translate-x-full'));
}

export function closePanel() {
    document.getElementById('panel').classList.add('translate-x-full');
    setTimeout(() => document.getElementById('panel-overlay').classList.add('hidden'), 300);
}
