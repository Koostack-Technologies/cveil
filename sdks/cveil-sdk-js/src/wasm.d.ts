// WebAssembly type declarations for Node.js (not included in @types/node).
// Subset of the WebAssembly API used by this package.

declare namespace WebAssembly {
  class Memory {
    readonly buffer: ArrayBuffer;
  }

  class Module {
    static exports(module: Module): Array<{ name: string; kind: string }>;
  }

  class Instance {
    readonly exports: Record<string, unknown>;
  }

  function compile(bytes: BufferSource): Promise<Module>;
  function instantiate(
    module: Module,
    imports?: Record<string, Record<string, unknown>>
  ): Promise<Instance>;
}
