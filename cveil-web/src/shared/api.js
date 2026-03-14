export function createApi(basePath) {
    return async function api(path, opts = {}) {
        const headers = { ...opts.headers };
        if (opts.body) headers['Content-Type'] = 'application/json';
        const res = await fetch(basePath + path, { ...opts, headers });
        if (!res.ok) {
            const err = await res.json().catch(() => ({ message: res.statusText }));
            throw new Error(err.message || res.statusText);
        }
        if (res.status === 204) return null;
        return res.json();
    };
}
