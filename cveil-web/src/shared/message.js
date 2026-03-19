export function showMsg(el, text, isError = false) {
    el.textContent = text;
    el.className = 'text-sm mt-3 ' + (isError ? 'text-error' : 'text-primary');
    el.classList.remove('hidden');
    setTimeout(() => el.classList.add('hidden'), 4000);
}
