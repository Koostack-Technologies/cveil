export function showMsg(el, text, isError = false) {
    el.textContent = text;
    el.className = 'text-sm mt-3 ' + (isError ? 'text-red-400' : 'text-green-400');
    el.classList.remove('hidden');
    setTimeout(() => el.classList.add('hidden'), 4000);
}
