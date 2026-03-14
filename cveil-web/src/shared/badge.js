import { esc } from './escape.js';

export function badge(text, color = 'gray') {
    const colors = {
        gray: 'bg-gray-100 text-gray-700',
        teal: 'bg-cv-50 text-cv-700 border border-cv-200',
        blue: 'bg-blue-50 text-blue-700 border border-blue-200',
        purple: 'bg-purple-50 text-purple-700 border border-purple-200',
    };
    return `<span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${colors[color] || colors.gray}">${esc(text)}</span>`;
}
