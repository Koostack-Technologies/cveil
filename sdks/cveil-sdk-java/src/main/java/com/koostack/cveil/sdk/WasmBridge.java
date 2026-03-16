package com.koostack.cveil.sdk;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasi.WasiPreview1_ModuleFactory;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Low-level WASM bridge: loads the cveil_sdk_core.wasm module via Chicory (pure-Java WASM runtime)
 * and wraps the pointer-based C-FFI into ergonomic string-in/string-out calls.
 *
 * <p>Mirrors the JS WasmBridge in wasm-bridge.ts.
 */
public class WasmBridge {

    private final Instance instance;
    private final Memory memory;
    private final ExportFunction cveilAlloc;
    private final ExportFunction cveilDealloc;
    private final ExportFunction cveilResultPtr;
    private final ExportFunction cveilResultLen;
    private final ExportFunction cveilFreeResult;

    // Core WASM exports
    private final ExportFunction cveilInit;
    private final ExportFunction cveilIngestGraphPage;
    private final ExportFunction cveilIngestDecisions;
    private final ExportFunction cveilMask;
    private final ExportFunction cveilMaskWithJurisdiction;
    private final ExportFunction cveilMaskBatch;
    private final ExportFunction cveilEvaluate;
    private final ExportFunction cveilProcessInvalidation;
    private final ExportFunction cveilSseConnected;
    private final ExportFunction cveilSseDisconnected;
    private final ExportFunction cveilSseReconnected;
    private final ExportFunction cveilStatus;
    private final ExportFunction cveilShutdown;

    private WasmBridge(Instance instance) {
        this.instance = instance;
        this.memory = instance.memory();

        this.cveilAlloc = instance.export("cveil_alloc");
        this.cveilDealloc = instance.export("cveil_dealloc");
        this.cveilResultPtr = instance.export("cveil_result_ptr");
        this.cveilResultLen = instance.export("cveil_result_len");
        this.cveilFreeResult = instance.export("cveil_free_result");

        this.cveilInit = instance.export("cveil_init");
        this.cveilIngestGraphPage = instance.export("cveil_ingest_graph_page");
        this.cveilIngestDecisions = instance.export("cveil_ingest_decisions");
        this.cveilMask = instance.export("cveil_mask");
        this.cveilMaskWithJurisdiction = instance.export("cveil_mask_with_jurisdiction");
        this.cveilMaskBatch = instance.export("cveil_mask_batch");
        this.cveilEvaluate = instance.export("cveil_evaluate");
        this.cveilProcessInvalidation = instance.export("cveil_process_invalidation");
        this.cveilSseConnected = instance.export("cveil_sse_connected");
        this.cveilSseDisconnected = instance.export("cveil_sse_disconnected");
        this.cveilSseReconnected = instance.export("cveil_sse_reconnected");
        this.cveilStatus = instance.export("cveil_status");
        this.cveilShutdown = instance.export("cveil_shutdown");
    }

    /** Load the WASM module from the given file path. */
    public static WasmBridge load(Path wasmPath) {
        WasmModule module = Parser.parse(wasmPath.toFile());
        return instantiate(module);
    }

    /** Load the WASM module from a classpath resource (e.g. "wasm/cveil_sdk_core.wasm"). */
    public static WasmBridge loadFromClasspath(String resourcePath) throws IOException {
        try (InputStream is =
                WasmBridge.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException(
                        "WASM resource not found on classpath: " + resourcePath);
            }
            WasmModule module = Parser.parse(is);
            return instantiate(module);
        }
    }

    private static final String CLASSPATH_WASM = "wasm/cveil_sdk_core.wasm";

    /** Load the WASM module from the default classpath location. */
    public static WasmBridge loadFromClasspath() throws IOException {
        return loadFromClasspath(CLASSPATH_WASM);
    }

    private static WasmBridge instantiate(WasmModule module) {
        var wasi = WasiPreview1.builder().withOptions(WasiOptions.builder().build()).build();
        var hostFunctions = WasiPreview1_ModuleFactory.toHostFunctions(wasi);
        var importValues = ImportValues.builder().addFunction(hostFunctions).build();

        var instance =
                Instance.builder(module)
                        .withImportValues(importValues)
                        .withInitialize(true)
                        .withStart(false)
                        .build();

        return new WasmBridge(instance);
    }

    // ─── Memory helpers ────────────────────────────────────────

    /** Write a string into WASM linear memory. Returns [ptr, len]. */
    private long[] writeString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        long ptr = cveilAlloc.apply((long) bytes.length)[0];
        memory.write((int) ptr, bytes);
        return new long[] {ptr, bytes.length};
    }

    /** Read the last result string from WASM memory. */
    private String readResult() {
        long ptr = cveilResultPtr.apply()[0];
        long len = cveilResultLen.apply()[0];
        if (ptr == 0 || len == 0) return "";
        byte[] bytes = memory.readBytes((int) ptr, (int) len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Free a string previously written to WASM memory. */
    private void freeString(long ptr, long len) {
        cveilDealloc.apply(ptr, len);
    }

    // ─── Public WASM call wrappers ─────────────────────────────

    /** Initialize the SDK with a JSON config string. */
    public String init(String configJson) {
        return call1(cveilInit, configJson);
    }

    /** Ingest a consent graph page (JSON). */
    public String ingestGraphPage(String pageJson) {
        return call1(cveilIngestGraphPage, pageJson);
    }

    /** Ingest consent decisions for a subject. */
    public String ingestDecisions(String subject, String decisionsJson) {
        return call2(cveilIngestDecisions, subject, decisionsJson);
    }

    /** Mask a JSON payload. */
    public String mask(String subject, String entity, String dataJson, String purpose) {
        return call4(cveilMask, subject, entity, dataJson, purpose);
    }

    /** Mask with explicit jurisdiction override. */
    public String maskWithJurisdiction(
            String subject, String entity, String dataJson, String purpose, String jurisdiction) {
        return call5(cveilMaskWithJurisdiction, subject, entity, dataJson, purpose, jurisdiction);
    }

    /** Mask a batch of JSON payloads. */
    public String maskBatch(String subject, String entity, String arrayJson, String purpose) {
        return call4(cveilMaskBatch, subject, entity, arrayJson, purpose);
    }

    /** Evaluate consent for specific fields. */
    public String evaluate(String subject, String entity, String fieldsJson, String purpose) {
        return call4(cveilEvaluate, subject, entity, fieldsJson, purpose);
    }

    /** Process an invalidation event. */
    public String processInvalidation(String eventJson) {
        return call1(cveilProcessInvalidation, eventJson);
    }

    /** Signal SSE connected. */
    public String sseConnected() {
        return call0(cveilSseConnected);
    }

    /** Signal SSE disconnected. */
    public String sseDisconnected() {
        return call0(cveilSseDisconnected);
    }

    /** Signal SSE reconnected. */
    public String sseReconnected() {
        return call0(cveilSseReconnected);
    }

    /** Get SDK status. */
    public String status() {
        return call0(cveilStatus);
    }

    /** Shutdown the SDK. */
    public String shutdown() {
        return call0(cveilShutdown);
    }

    // ─── Generic callN helpers (mirror JS bridge) ──────────────

    private String call0(ExportFunction fn) {
        long rc = fn.apply()[0];
        String result = readResult();
        cveilFreeResult.apply();
        if (rc != 0) throw new CveilWasmError(result);
        return result;
    }

    private String call1(ExportFunction fn, String arg) {
        long[] a = writeString(arg);
        long rc = fn.apply(a[0], a[1])[0];
        String result = readResult();
        freeString(a[0], a[1]);
        cveilFreeResult.apply();
        if (rc != 0) throw new CveilWasmError(result);
        return result;
    }

    private String call2(ExportFunction fn, String arg1, String arg2) {
        long[] a1 = writeString(arg1);
        long[] a2 = writeString(arg2);
        long rc = fn.apply(a1[0], a1[1], a2[0], a2[1])[0];
        String result = readResult();
        freeString(a1[0], a1[1]);
        freeString(a2[0], a2[1]);
        cveilFreeResult.apply();
        if (rc != 0) throw new CveilWasmError(result);
        return result;
    }

    private String call4(ExportFunction fn, String a1, String a2, String a3, String a4) {
        long[] s1 = writeString(a1);
        long[] s2 = writeString(a2);
        long[] s3 = writeString(a3);
        long[] s4 = writeString(a4);
        long rc = fn.apply(s1[0], s1[1], s2[0], s2[1], s3[0], s3[1], s4[0], s4[1])[0];
        String result = readResult();
        freeString(s1[0], s1[1]);
        freeString(s2[0], s2[1]);
        freeString(s3[0], s3[1]);
        freeString(s4[0], s4[1]);
        cveilFreeResult.apply();
        if (rc != 0) throw new CveilWasmError(result);
        return result;
    }

    private String call5(ExportFunction fn, String a1, String a2, String a3, String a4, String a5) {
        long[] s1 = writeString(a1);
        long[] s2 = writeString(a2);
        long[] s3 = writeString(a3);
        long[] s4 = writeString(a4);
        long[] s5 = writeString(a5);
        long rc = fn.apply(s1[0], s1[1], s2[0], s2[1], s3[0], s3[1], s4[0], s4[1], s5[0], s5[1])[0];
        String result = readResult();
        freeString(s1[0], s1[1]);
        freeString(s2[0], s2[1]);
        freeString(s3[0], s3[1]);
        freeString(s4[0], s4[1]);
        freeString(s5[0], s5[1]);
        cveilFreeResult.apply();
        if (rc != 0) throw new CveilWasmError(result);
        return result;
    }
}
