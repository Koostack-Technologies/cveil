export function showTab(name) {
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
