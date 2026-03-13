//! Low-level WASM ABI layer: `#[unsafe(no_mangle)] extern "C"` wrappers around the
//! high-level exports. The host (JS/Python/Java) calls these via WASM function
//! imports, passing strings as (ptr, len) pairs and reading results back via
//! `cveil_result_ptr()` / `cveil_result_len()`.
//!
//! Protocol:
//!   1. Host writes string args into WASM memory via `cveil_alloc`
//!   2. Host calls the `cveil_*` function with (ptr, len) for each arg
//!   3. Function returns 0 (ok) or 1 (error)
//!   4. Host reads result string from `cveil_result_ptr()` / `cveil_result_len()`
//!   5. Host calls `cveil_free_result()` to release the result memory

use std::cell::RefCell;

use super::exports;

// ─── Result Storage ────────────────────────────────────────────────

thread_local! {
    static LAST_RESULT: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

fn set_result(s: String) {
    LAST_RESULT.with(|r| {
        *r.borrow_mut() = s.into_bytes();
    });
}

/// Pointer to the last result string in WASM linear memory.
#[unsafe(no_mangle)]
pub extern "C" fn cveil_result_ptr() -> *const u8 {
    LAST_RESULT.with(|r| {
        let borrow = r.borrow();
        if borrow.is_empty() {
            std::ptr::null()
        } else {
            borrow.as_ptr()
        }
    })
}

/// Length in bytes of the last result string.
#[unsafe(no_mangle)]
pub extern "C" fn cveil_result_len() -> usize {
    LAST_RESULT.with(|r| r.borrow().len())
}

/// Free the last result (optional — overwritten on next call anyway).
#[unsafe(no_mangle)]
pub extern "C" fn cveil_free_result() {
    LAST_RESULT.with(|r| r.borrow_mut().clear());
}

// ─── Memory Allocation ────────────────────────────────────────────

/// Allocate `size` bytes in WASM linear memory. Returns pointer.
/// The host writes string data here before calling SDK functions.
#[unsafe(no_mangle)]
pub extern "C" fn cveil_alloc(size: usize) -> *mut u8 {
    let mut buf = Vec::with_capacity(size);
    let ptr = buf.as_mut_ptr();
    std::mem::forget(buf);
    ptr
}

/// Free a previously allocated buffer.
#[unsafe(no_mangle)]
pub extern "C" fn cveil_dealloc(ptr: *mut u8, size: usize) {
    if !ptr.is_null() && size > 0 {
        unsafe {
            drop(Vec::from_raw_parts(ptr, 0, size));
        }
    }
}

// ─── Helpers ───────────────────────────────────────────────────────

unsafe fn str_from_wasm(ptr: *const u8, len: usize) -> &'static str {
    let slice = unsafe { std::slice::from_raw_parts(ptr, len) };
    unsafe { std::str::from_utf8_unchecked(slice) }
}

fn call_and_store(result: Result<String, String>) -> i32 {
    match result {
        Ok(s) => {
            set_result(s);
            0
        }
        Err(e) => {
            set_result(e);
            1
        }
    }
}

// ─── Exported SDK Functions ────────────────────────────────────────

#[unsafe(no_mangle)]
pub extern "C" fn cveil_init(config_ptr: *const u8, config_len: usize) -> i32 {
    let config_json = unsafe { str_from_wasm(config_ptr, config_len) };
    call_and_store(exports::cveil_init(config_json))
}

#[unsafe(no_mangle)]
pub extern "C" fn cveil_ingest_graph_page(page_ptr: *const u8, page_len: usize) -> i32 {
    let page_json = unsafe { str_from_wasm(page_ptr, page_len) };
    call_and_store(exports::cveil_ingest_graph_page(page_json))
}

#[unsafe(no_mangle)]
pub extern "C" fn cveil_ingest_decisions(
    subject_ptr: *const u8,
    subject_len: usize,
    decisions_ptr: *const u8,
    decisions_len: usize,
) -> i32 {
    let subject = unsafe { str_from_wasm(subject_ptr, subject_len) };
    let decisions_json = unsafe { str_from_wasm(decisions_ptr, decisions_len) };
    call_and_store(exports::cveil_ingest_decisions(subject, decisions_json))
}

#[unsafe(no_mangle)]
pub extern "C" fn cveil_mask(
    subject_ptr: *const u8,
    subject_len: usize,
    entity_ptr: *const u8,
    entity_len: usize,
    payload_ptr: *const u8,
    payload_len: usize,
    purpose_ptr: *const u8,
    purpose_len: usize,
) -> i32 {
    let subject = unsafe { str_from_wasm(subject_ptr, subject_len) };
    let entity = unsafe { str_from_wasm(entity_ptr, entity_len) };
    let payload = unsafe { str_from_wasm(payload_ptr, payload_len) };
    let purpose = unsafe { str_from_wasm(purpose_ptr, purpose_len) };
    call_and_store(exports::cveil_mask(subject, entity, payload, purpose))
}

#[unsafe(no_mangle)]
pub extern "C" fn cveil_mask_with_jurisdiction(
    subject_ptr: *const u8,
    subject_len: usize,
    entity_ptr: *const u8,
    entity_len: usize,
    payload_ptr: *const u8,
    payload_len: usize,
    purpose_ptr: *const u8,
    purpose_len: usize,
    jurisdiction_ptr: *const u8,
    jurisdiction_len: usize,
) -> i32 {
    let subject = unsafe { str_from_wasm(subject_ptr, subject_len) };
    let entity = unsafe { str_from_wasm(entity_ptr, entity_len) };
    let payload = unsafe { str_from_wasm(payload_ptr, payload_len) };
    let purpose = unsafe { str_from_wasm(purpose_ptr, purpose_len) };
    let jurisdiction = unsafe { str_from_wasm(jurisdiction_ptr, jurisdiction_len) };
    call_and_store(exports::cveil_mask_with_jurisdiction(
        subject, entity, payload, purpose, jurisdiction,
    ))
}

#[unsafe(no_mangle)]
pub extern "C" fn cveil_mask_batch(
    subject_ptr: *const u8,
    subject_len: usize,
    entity_ptr: *const u8,
    entity_len: usize,
    array_ptr: *const u8,
    array_len: usize,
    purpose_ptr: *const u8,
    purpose_len: usize,
) -> i32 {
    let subject = unsafe { str_from_wasm(subject_ptr, subject_len) };
    let entity = unsafe { str_from_wasm(entity_ptr, entity_len) };
    let array = unsafe { str_from_wasm(array_ptr, array_len) };
    let purpose = unsafe { str_from_wasm(purpose_ptr, purpose_len) };
    call_and_store(exports::cveil_mask_batch(subject, entity, array, purpose))
}

#[unsafe(no_mangle)]
pub extern "C" fn cveil_evaluate(
    subject_ptr: *const u8,
    subject_len: usize,
    entity_ptr: *const u8,
    entity_len: usize,
    fields_ptr: *const u8,
    fields_len: usize,
    purpose_ptr: *const u8,
    purpose_len: usize,
) -> i32 {
    let subject = unsafe { str_from_wasm(subject_ptr, subject_len) };
    let entity = unsafe { str_from_wasm(entity_ptr, entity_len) };
    let fields = unsafe { str_from_wasm(fields_ptr, fields_len) };
    let purpose = unsafe { str_from_wasm(purpose_ptr, purpose_len) };
    call_and_store(exports::cveil_evaluate(subject, entity, fields, purpose))
}

#[unsafe(no_mangle)]
pub extern "C" fn cveil_process_invalidation(event_ptr: *const u8, event_len: usize) -> i32 {
    let event_json = unsafe { str_from_wasm(event_ptr, event_len) };
    call_and_store(exports::cveil_process_invalidation(event_json))
}

#[unsafe(no_mangle)]
pub extern "C" fn cveil_sse_connected() -> i32 {
    call_and_store(exports::cveil_sse_connected())
}

#[unsafe(no_mangle)]
pub extern "C" fn cveil_sse_disconnected() -> i32 {
    call_and_store(exports::cveil_sse_disconnected())
}

#[unsafe(no_mangle)]
pub extern "C" fn cveil_sse_reconnected() -> i32 {
    call_and_store(exports::cveil_sse_reconnected())
}

#[unsafe(no_mangle)]
pub extern "C" fn cveil_status() -> i32 {
    call_and_store(exports::cveil_status())
}

#[unsafe(no_mangle)]
pub extern "C" fn cveil_shutdown() -> i32 {
    call_and_store(exports::cveil_shutdown())
}
