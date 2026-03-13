/**
 * Low-level WASM bridge: loads the cveil_sdk_core.wasm module via Node.js WASI
 * and wraps the pointer-based C-FFI into ergonomic string-in/string-out calls.
 */

import { readFile } from "node:fs/promises";
import { WASI } from "node:wasi";

/** Raw WASM exports from the cveil-sdk-core module. */
interface WasmExports {
  memory: WebAssembly.Memory;
  cveil_alloc(size: number): number;
  cveil_dealloc(ptr: number, size: number): void;
  cveil_result_ptr(): number;
  cveil_result_len(): number;
  cveil_free_result(): void;
  cveil_init(ptr: number, len: number): number;
  cveil_ingest_graph_page(ptr: number, len: number): number;
  cveil_ingest_decisions(
    sPtr: number,
    sLen: number,
    dPtr: number,
    dLen: number
  ): number;
  cveil_mask(
    sPtr: number,
    sLen: number,
    ePtr: number,
    eLen: number,
    pPtr: number,
    pLen: number,
    puPtr: number,
    puLen: number
  ): number;
  cveil_mask_with_jurisdiction(
    sPtr: number,
    sLen: number,
    ePtr: number,
    eLen: number,
    pPtr: number,
    pLen: number,
    puPtr: number,
    puLen: number,
    jPtr: number,
    jLen: number
  ): number;
  cveil_mask_batch(
    sPtr: number,
    sLen: number,
    ePtr: number,
    eLen: number,
    aPtr: number,
    aLen: number,
    puPtr: number,
    puLen: number
  ): number;
  cveil_evaluate(
    sPtr: number,
    sLen: number,
    ePtr: number,
    eLen: number,
    fPtr: number,
    fLen: number,
    puPtr: number,
    puLen: number
  ): number;
  cveil_process_invalidation(ptr: number, len: number): number;
  cveil_sse_connected(): number;
  cveil_sse_disconnected(): number;
  cveil_sse_reconnected(): number;
  cveil_status(): number;
  cveil_shutdown(): number;
  // WASI lifecycle
  _initialize?(): void;
}

export class WasmBridge {
  private wasm!: WasmExports;
  private encoder = new TextEncoder();
  private decoder = new TextDecoder();

  private constructor() {}

  /** Load the WASM module from the given file path. */
  static async load(wasmPath: string): Promise<WasmBridge> {
    const bridge = new WasmBridge();

    const wasi = new WASI({
      version: "preview1",
      args: [],
      env: {},
    });

    const wasmBytes = await readFile(wasmPath);
    const module = await WebAssembly.compile(wasmBytes);
    const instance = await WebAssembly.instantiate(module, {
      wasi_snapshot_preview1: wasi.wasiImport,
    });

    wasi.initialize(instance);
    bridge.wasm = instance.exports as unknown as WasmExports;

    return bridge;
  }

  /** Write a string into WASM linear memory. Returns [ptr, len]. */
  private writeString(s: string): [number, number] {
    const bytes = this.encoder.encode(s);
    const ptr = this.wasm.cveil_alloc(bytes.length);
    const mem = new Uint8Array(this.wasm.memory.buffer, ptr, bytes.length);
    mem.set(bytes);
    return [ptr, bytes.length];
  }

  /** Read the last result string from WASM memory. */
  private readResult(): string {
    const ptr = this.wasm.cveil_result_ptr();
    const len = this.wasm.cveil_result_len();
    if (ptr === 0 || len === 0) return "";
    const bytes = new Uint8Array(this.wasm.memory.buffer, ptr, len);
    return this.decoder.decode(bytes);
  }

  /** Free a string previously written to WASM memory. */
  private freeString(ptr: number, len: number): void {
    this.wasm.cveil_dealloc(ptr, len);
  }

  /** Call a WASM function with one string argument. */
  call1(fn: (ptr: number, len: number) => number, arg: string): string {
    const [ptr, len] = this.writeString(arg);
    const rc = fn.call(this.wasm, ptr, len);
    const result = this.readResult();
    this.freeString(ptr, len);
    this.wasm.cveil_free_result();
    if (rc !== 0) throw new CveilWasmError(result);
    return result;
  }

  /** Call a WASM function with two string arguments. */
  call2(
    fn: (p1: number, l1: number, p2: number, l2: number) => number,
    arg1: string,
    arg2: string
  ): string {
    const [p1, l1] = this.writeString(arg1);
    const [p2, l2] = this.writeString(arg2);
    const rc = fn.call(this.wasm, p1, l1, p2, l2);
    const result = this.readResult();
    this.freeString(p1, l1);
    this.freeString(p2, l2);
    this.wasm.cveil_free_result();
    if (rc !== 0) throw new CveilWasmError(result);
    return result;
  }

  /** Call a WASM function with four string arguments. */
  call4(
    fn: (
      p1: number,
      l1: number,
      p2: number,
      l2: number,
      p3: number,
      l3: number,
      p4: number,
      l4: number
    ) => number,
    a1: string,
    a2: string,
    a3: string,
    a4: string
  ): string {
    const [p1, l1] = this.writeString(a1);
    const [p2, l2] = this.writeString(a2);
    const [p3, l3] = this.writeString(a3);
    const [p4, l4] = this.writeString(a4);
    const rc = fn.call(this.wasm, p1, l1, p2, l2, p3, l3, p4, l4);
    const result = this.readResult();
    this.freeString(p1, l1);
    this.freeString(p2, l2);
    this.freeString(p3, l3);
    this.freeString(p4, l4);
    this.wasm.cveil_free_result();
    if (rc !== 0) throw new CveilWasmError(result);
    return result;
  }

  /** Call a WASM function with five string arguments. */
  call5(
    fn: (
      p1: number,
      l1: number,
      p2: number,
      l2: number,
      p3: number,
      l3: number,
      p4: number,
      l4: number,
      p5: number,
      l5: number
    ) => number,
    a1: string,
    a2: string,
    a3: string,
    a4: string,
    a5: string
  ): string {
    const [p1, l1] = this.writeString(a1);
    const [p2, l2] = this.writeString(a2);
    const [p3, l3] = this.writeString(a3);
    const [p4, l4] = this.writeString(a4);
    const [p5, l5] = this.writeString(a5);
    const rc = fn.call(this.wasm, p1, l1, p2, l2, p3, l3, p4, l4, p5, l5);
    const result = this.readResult();
    this.freeString(p1, l1);
    this.freeString(p2, l2);
    this.freeString(p3, l3);
    this.freeString(p4, l4);
    this.freeString(p5, l5);
    this.wasm.cveil_free_result();
    if (rc !== 0) throw new CveilWasmError(result);
    return result;
  }

  /** Call a zero-argument WASM function. */
  call0(fn: () => number): string {
    const rc = fn.call(this.wasm);
    const result = this.readResult();
    this.wasm.cveil_free_result();
    if (rc !== 0) throw new CveilWasmError(result);
    return result;
  }

  get exports(): WasmExports {
    return this.wasm;
  }
}

export class CveilWasmError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "CveilWasmError";
  }
}
