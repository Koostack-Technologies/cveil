import { esc } from './escape.js';

export function badge(text, color = 'gray') {
    const colors = {
        gray: 'bg-gray-700 text-gray-300',
        teal: 'bg-cv-900/50 text-cv-500 border border-cv-500/30',
        blue: 'bg-blue-900/50 text-blue-400 border border-blue-500/30',
        purple: 'bg-purple-900/50 text-purple-400 border border-purple-500/30',
    };
    return `<span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${colors[color] || colors.gray}">${esc(text)}</span>`;
}
