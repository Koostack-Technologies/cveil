// Client-side masking engine mirroring crates/cveil-sdk-core/src/masking/engine.rs
// 5 masking types: Plaintext, RedactAll, RedactUpToLast4, Hash256, RedactLocal

export const MaskingType = Object.freeze({
    Plaintext: 'Plaintext',
    RedactAll: 'RedactAll',
    RedactUpToLast4: 'RedactUpToLast4',
    Hash256: 'Hash256',
    RedactLocal: 'RedactLocal',
});

export const MASKING_TYPES = Object.values(MaskingType);

function redactAll(value) {
    if (typeof value === 'string') return '***';
    if (typeof value === 'number') return Number.isInteger(value) ? 0 : 0.0;
    if (typeof value === 'boolean') return false;
    return null;
}

function redactUpToLast4(value) {
    if (typeof value === 'string') {
        const chars = [...value]; // proper unicode handling
        if (chars.length <= 4) return '****';
        const visibleStart = chars.length - 4;
        return '*'.repeat(visibleStart) + chars.slice(visibleStart).join('');
    }
    return redactAll(value);
}

async function hash256(value) {
    if (typeof value === 'string' || typeof value === 'number') {
        const text = String(value);
        const data = new TextEncoder().encode(text);
        const hashBuf = await crypto.subtle.digest('SHA-256', data);
        return Array.from(new Uint8Array(hashBuf))
            .map(b => b.toString(16).padStart(2, '0'))
            .join('');
    }
    return redactAll(value);
}

function redactLocal(value) {
    if (typeof value === 'string') {
        const atPos = value.indexOf('@');
        if (atPos >= 0) {
            const domain = value.slice(atPos);
            const maskedLen = Math.max(atPos, 4);
            return '*'.repeat(maskedLen) + domain;
        }
    }
    return redactAll(value);
}

/** Apply a single masking type to a value. Returns a Promise (Hash256 is async). */
export async function applyMask(value, maskingType) {
    switch (maskingType) {
        case MaskingType.Plaintext: return value;
        case MaskingType.RedactAll: return redactAll(value);
        case MaskingType.RedactUpToLast4: return redactUpToLast4(value);
        case MaskingType.Hash256: return hash256(value);
        case MaskingType.RedactLocal: return redactLocal(value);
        default: return value;
    }
}

/**
 * Walk a JSON object tree and mask leaf fields that have resolutions.
 * @param {*} data - parsed JSON value (will be cloned, original untouched)
 * @param {Object} resolutions - map of field path → MaskingType string
 * @returns {Promise<*>} masked clone
 */
export async function maskJsonTree(data, resolutions) {
    return walkAndMask(structuredClone(data), '', resolutions);
}

async function walkAndMask(value, currentPath, resolutions) {
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
        for (const key of Object.keys(value)) {
            const childPath = currentPath ? `${currentPath}.${key}` : key;
            const mt = resolutions[childPath];
            if (mt && mt !== MaskingType.Plaintext) {
                value[key] = await applyMask(value[key], mt);
                continue; // don't recurse into masked subtrees
            }
            value[key] = await walkAndMask(value[key], childPath, resolutions);
        }
    } else if (Array.isArray(value)) {
        for (let i = 0; i < value.length; i++) {
            value[i] = await walkAndMask(value[i], currentPath, resolutions);
        }
    } else if (currentPath) {
        const mt = resolutions[currentPath];
        if (mt && mt !== MaskingType.Plaintext) {
            return applyMask(value, mt);
        }
    }
    return value;
}

/** Extract all leaf field paths from a JSON object. */
export function extractFieldPaths(obj, prefix = '') {
    const paths = [];
    if (obj !== null && typeof obj === 'object' && !Array.isArray(obj)) {
        for (const [key, val] of Object.entries(obj)) {
            const path = prefix ? `${prefix}.${key}` : key;
            if (val !== null && typeof val === 'object' && !Array.isArray(val)) {
                paths.push(...extractFieldPaths(val, path));
            } else {
                paths.push(path);
            }
        }
    }
    return paths;
}
