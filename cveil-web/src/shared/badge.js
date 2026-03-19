import { esc } from './escape.js';

export function badge(text, color = 'gray') {
    const colors = {
        gray: 'chip-neutral',
        teal: 'chip-safe',
        blue: 'chip-info',
        purple: 'bg-[#2a1f3d] text-[#d8b4fe] font-technical text-[0.6875rem] uppercase tracking-wider rounded-full',
        red: 'chip-critical',
        amber: 'chip-warning',
    };
    return `<span class="inline-flex items-center px-2.5 py-0.5 ${colors[color] || colors.gray}">${esc(text)}</span>`;
}
