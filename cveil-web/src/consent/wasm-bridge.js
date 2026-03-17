/**
 * Browser-compatible WASM bridge for cveil-sdk-core.
 *
 * Mirrors the Node.js WasmBridge in sdks/cveil-sdk-js/src/wasm-bridge.ts
 * but replaces node:fs/node:wasi with fetch() and provides host imports
 * (cveil_host_time_ms) that the browser WASM build expects.
 *
 * Protocol (same as Node bridge):
 *   1. Write string args into WASM memory via cveil_alloc
 *   2. Call the cveil_* function with (ptr, len) pairs
 *   3. Read result from cveil_result_ptr() / cveil_result_len()
 *   4. Free result via cveil_free_result()
 */

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export class CveilWasmError extends Error {
    constructor(message) {
        super(message);
        this.name = 'CveilWasmError';
    }
}

export class WasmBridge {
    /** @type {WebAssembly.Instance['exports']} */
    #wasm = null;

    /**
     * Load and instantiate the WASM module from a URL.
     * @param {string} wasmUrl - URL to the .wasm file (e.g. "/wasm/cveil_sdk_core.wasm")
     * @returns {Promise<WasmBridge>}
     */
    static async load(wasmUrl) {
        const bridge = new WasmBridge();

        const hostImports = {
            env: {
                cveil_host_time_ms: () => BigInt(Date.now()),
            },
        };

        let wasm;
        if (typeof WebAssembly.instantiateStreaming === 'function') {
            const result = await WebAssembly.instantiateStreaming(
                fetch(wasmUrl),
                hostImports,
            );
            wasm = result.instance.exports;
        } else {
            const response = await fetch(wasmUrl);
            const bytes = await response.arrayBuffer();
            const module = await WebAssembly.compile(bytes);
            const instance = await WebAssembly.instantiate(module, hostImports);
            wasm = instance.exports;
        }

        bridge.#wasm = wasm;
        return bridge;
    }

    /** Raw WASM exports (for advanced use). */
    get exports() {
        return this.#wasm;
    }

    // ── String I/O ─────────────────────────────────────────────────

    /** Write a JS string into WASM linear memory. Returns [ptr, len]. */
    writeString(s) {
        const bytes = encoder.encode(s);
        const ptr = this.#wasm.cveil_alloc(bytes.length);
        new Uint8Array(this.#wasm.memory.buffer, ptr, bytes.length).set(bytes);
        return [ptr, bytes.length];
    }

    /** Read the last result string from WASM. */
    readResult() {
        const ptr = this.#wasm.cveil_result_ptr();
        const len = this.#wasm.cveil_result_len();
        if (!ptr || !len) return '';
        const bytes = new Uint8Array(this.#wasm.memory.buffer, ptr, len);
        return decoder.decode(bytes);
    }

    /** Free a previously allocated string. */
    freeString(ptr, len) {
        this.#wasm.cveil_dealloc(ptr, len);
    }

    // ── Call wrappers (mirror Node bridge) ─────────────────────────

    /** Zero-arg call. */
    call0(fn) {
        const rc = fn();
        const result = this.readResult();
        this.#wasm.cveil_free_result();
        if (rc !== 0) throw new CveilWasmError(result || 'WASM call failed');
        return result;
    }

    /** Single string arg call. */
    call1(fn, arg1) {
        const [p1, l1] = this.writeString(arg1);
        try {
            const rc = fn(p1, l1);
            const result = this.readResult();
            this.#wasm.cveil_free_result();
            if (rc !== 0) throw new CveilWasmError(result || 'WASM call failed');
            return result;
        } finally {
            this.freeString(p1, l1);
        }
    }

    /** Two string args call. */
    call2(fn, arg1, arg2) {
        const [p1, l1] = this.writeString(arg1);
        const [p2, l2] = this.writeString(arg2);
        try {
            const rc = fn(p1, l1, p2, l2);
            const result = this.readResult();
            this.#wasm.cveil_free_result();
            if (rc !== 0) throw new CveilWasmError(result || 'WASM call failed');
            return result;
        } finally {
            this.freeString(p1, l1);
            this.freeString(p2, l2);
        }
    }

    /** Four string args call. */
    call4(fn, arg1, arg2, arg3, arg4) {
        const [p1, l1] = this.writeString(arg1);
        const [p2, l2] = this.writeString(arg2);
        const [p3, l3] = this.writeString(arg3);
        const [p4, l4] = this.writeString(arg4);
        try {
            const rc = fn(p1, l1, p2, l2, p3, l3, p4, l4);
            const result = this.readResult();
            this.#wasm.cveil_free_result();
            if (rc !== 0) throw new CveilWasmError(result || 'WASM call failed');
            return result;
        } finally {
            this.freeString(p1, l1);
            this.freeString(p2, l2);
            this.freeString(p3, l3);
            this.freeString(p4, l4);
        }
    }

    /** Five string args call. */
    call5(fn, arg1, arg2, arg3, arg4, arg5) {
        const [p1, l1] = this.writeString(arg1);
        const [p2, l2] = this.writeString(arg2);
        const [p3, l3] = this.writeString(arg3);
        const [p4, l4] = this.writeString(arg4);
        const [p5, l5] = this.writeString(arg5);
        try {
            const rc = fn(p1, l1, p2, l2, p3, l3, p4, l4, p5, l5);
            const result = this.readResult();
            this.#wasm.cveil_free_result();
            if (rc !== 0) throw new CveilWasmError(result || 'WASM call failed');
            return result;
        } finally {
            this.freeString(p1, l1);
            this.freeString(p2, l2);
            this.freeString(p3, l3);
            this.freeString(p4, l4);
            this.freeString(p5, l5);
        }
    }
}
