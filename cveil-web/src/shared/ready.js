/** Calls fn immediately if DOM is already parsed, otherwise waits for DOMContentLoaded. */
export function ready(fn) {
    if (document.readyState !== 'loading') fn();
    else document.addEventListener('DOMContentLoaded', fn);
}
