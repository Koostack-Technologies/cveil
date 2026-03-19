export function showTab(name) {
    document.querySelectorAll('[id^="section-"]').forEach(s => s.classList.add('hidden'));
    document.querySelectorAll('.tab-btn').forEach(b => {
        b.classList.remove('border-primary', 'text-primary');
        b.classList.add('border-transparent', 'text-on-surface-muted');
    });
    document.getElementById('section-' + name).classList.remove('hidden');
    const btn = document.getElementById('tab-' + name);
    btn.classList.add('border-primary', 'text-primary');
    btn.classList.remove('border-transparent', 'text-on-surface-muted');
    window.location.hash = name;
}
