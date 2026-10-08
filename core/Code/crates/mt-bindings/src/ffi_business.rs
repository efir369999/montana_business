//! C ABI of Montana Business: the eleven doors of mt-business, byte for byte the Rust API the
//! Android JNI calls, so both platforms answer one input with one output.
//!
//! Outputs go into the caller's buffer. On MT_ERR_BUFFER_TOO_SMALL `out_len` holds the length
//! needed (a caller may ask with cap 0 first; every door is deterministic, the second call gives
//! the same bytes). JSON outputs are UTF-8 without a terminating NUL.

// Safety contracts (buffer sizes, non-null pointers) are documented in mt_business.h.
#![allow(clippy::missing_safety_doc)]

use core::slice;
use std::ffi::CStr;
use std::os::raw::{c_char, c_int};

use mt_business::BizError;

use super::*;

pub const MT_ERR_BIZ_DENIED: c_int = -40;
pub const MT_ERR_BIZ_ATTEST: c_int = -41;
pub const MT_ERR_BIZ_FRAME: c_int = -42;
pub const MT_ERR_BIZ_COMMAND: c_int = -43;

pub const MT_BIZ_ID_LEN: usize = 32;
// One frame of a chain: u32 length plus the largest record (header, 65536-byte body, signature).
pub const MT_BIZ_FRAME_MAX: usize = 4 + mt_business::frame::FRAME_MAX;

fn code(e: &BizError) -> c_int {
    match e {
        BizError::Denied(_) => MT_ERR_BIZ_DENIED,
        BizError::Attest(_) => MT_ERR_BIZ_ATTEST,
        BizError::Frame(_) => MT_ERR_BIZ_FRAME,
        BizError::Command(_) => MT_ERR_BIZ_COMMAND,
        BizError::Key(_) => MT_ERR_SIGN_FAILED,
    }
}

// An empty input may come as a null pointer; a non-empty one may not.
unsafe fn input<'a>(p: *const u8, len: usize) -> Option<&'a [u8]> {
    if len == 0 {
        Some(&[])
    } else if p.is_null() {
        None
    } else {
        Some(slice::from_raw_parts(p, len))
    }
}

unsafe fn emit(bytes: &[u8], out: *mut u8, cap: usize, out_len: *mut usize) -> c_int {
    *out_len = bytes.len();
    if bytes.len() > cap {
        return MT_ERR_BUFFER_TOO_SMALL;
    }
    if bytes.is_empty() {
        return MT_OK;
    }
    if out.is_null() {
        return MT_ERR_NULL_PTR;
    }
    slice::from_raw_parts_mut(out, bytes.len()).copy_from_slice(bytes);
    MT_OK
}

#[no_mangle]
pub unsafe extern "C" fn mt_biz_author(
    seckey: *const u8,
    pubkey: *const u8,
    roster: *const u8,
    roster_len: usize,
    hr: *const u8,
    hr_len: usize,
    command_utf8: *const c_char,
    at_ms: u64,
    out: *mut u8,
    cap: usize,
    out_len: *mut usize,
) -> c_int {
    guard(|| {
        if seckey.is_null() || pubkey.is_null() || command_utf8.is_null() || out_len.is_null() {
            return MT_ERR_NULL_PTR;
        }
        let (Some(roster), Some(hr)) = (input(roster, roster_len), input(hr, hr_len)) else {
            return MT_ERR_NULL_PTR;
        };
        let command = match CStr::from_ptr(command_utf8).to_str() {
            Ok(s) => s,
            Err(_) => return MT_ERR_INVALID_UTF8,
        };
        let sk = slice::from_raw_parts(seckey, MT_MLDSA_SECKEY_SIZE);
        let pk = slice::from_raw_parts(pubkey, MT_MLDSA_PUBKEY_SIZE);
        match mt_business::author(sk, pk, roster, hr, command, at_ms) {
            Ok(frame) => emit(&frame, out, cap, out_len),
            Err(e) => code(&e),
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mt_biz_merge(
    have: *const u8,
    have_len: usize,
    incoming: *const u8,
    incoming_len: usize,
    out: *mut u8,
    cap: usize,
    out_len: *mut usize,
    out_added: *mut usize,
) -> c_int {
    guard(|| {
        if out_len.is_null() || out_added.is_null() {
            return MT_ERR_NULL_PTR;
        }
        let (Some(have), Some(incoming)) = (input(have, have_len), input(incoming, incoming_len))
        else {
            return MT_ERR_NULL_PTR;
        };
        match mt_business::merge(have, incoming) {
            Ok((chain, added)) => {
                *out_added = added;
                emit(&chain, out, cap, out_len)
            },
            Err(e) => code(&e),
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mt_biz_view(
    roster: *const u8,
    roster_len: usize,
    hr: *const u8,
    hr_len: usize,
    viewer_pubkey: *const u8,
    now_ms: u64,
    out_json: *mut u8,
    cap: usize,
    out_len: *mut usize,
) -> c_int {
    guard(|| {
        if viewer_pubkey.is_null() || out_len.is_null() {
            return MT_ERR_NULL_PTR;
        }
        let (Some(roster), Some(hr)) = (input(roster, roster_len), input(hr, hr_len)) else {
            return MT_ERR_NULL_PTR;
        };
        let pk = slice::from_raw_parts(viewer_pubkey, MT_MLDSA_PUBKEY_SIZE);
        match mt_business::view(roster, hr, pk, now_ms) {
            Ok(json) => emit(json.as_bytes(), out_json, cap, out_len),
            Err(e) => code(&e),
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mt_biz_slice(
    hr: *const u8,
    hr_len: usize,
    lane: *const u8,
    out: *mut u8,
    cap: usize,
    out_len: *mut usize,
) -> c_int {
    guard(|| {
        if lane.is_null() || out_len.is_null() {
            return MT_ERR_NULL_PTR;
        }
        let Some(hr) = input(hr, hr_len) else {
            return MT_ERR_NULL_PTR;
        };
        let lane = slice::from_raw_parts(lane, MT_BIZ_ID_LEN);
        match mt_business::slice(hr, lane) {
            Ok(chain) => emit(&chain, out, cap, out_len),
            Err(e) => code(&e),
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mt_biz_attest_open(
    attest: *const u8,
    attest_len: usize,
    out_json: *mut u8,
    cap: usize,
    out_len: *mut usize,
) -> c_int {
    guard(|| {
        if out_len.is_null() {
            return MT_ERR_NULL_PTR;
        }
        let Some(attest) = input(attest, attest_len) else {
            return MT_ERR_NULL_PTR;
        };
        match mt_business::attest_open(attest) {
            Ok(json) => emit(json.as_bytes(), out_json, cap, out_len),
            Err(e) => code(&e),
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mt_biz_email_open(
    attest: *const u8,
    attest_len: usize,
    out_json: *mut u8,
    cap: usize,
    out_len: *mut usize,
) -> c_int {
    guard(|| {
        if out_len.is_null() {
            return MT_ERR_NULL_PTR;
        }
        let Some(attest) = input(attest, attest_len) else {
            return MT_ERR_NULL_PTR;
        };
        match mt_business::email_open(attest) {
            Ok(json) => emit(json.as_bytes(), out_json, cap, out_len),
            Err(e) => code(&e),
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mt_biz_service_key(out: *mut u8) -> c_int {
    guard(|| {
        if out.is_null() {
            return MT_ERR_NULL_PTR;
        }
        match mt_business::service_key() {
            Ok(key) => {
                slice::from_raw_parts_mut(out, MT_MLDSA_PUBKEY_SIZE).copy_from_slice(&key);
                MT_OK
            },
            Err(e) => code(&e),
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mt_biz_member_id(pubkey: *const u8, out: *mut u8) -> c_int {
    guard(|| {
        if pubkey.is_null() || out.is_null() {
            return MT_ERR_NULL_PTR;
        }
        let pk = slice::from_raw_parts(pubkey, MT_MLDSA_PUBKEY_SIZE);
        match mt_business::member_id(pk) {
            Ok(id) => {
                slice::from_raw_parts_mut(out, MT_BIZ_ID_LEN).copy_from_slice(&id);
                MT_OK
            },
            Err(e) => code(&e),
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mt_biz_invite_id(secret: *const u8, out: *mut u8) -> c_int {
    guard(|| {
        if secret.is_null() || out.is_null() {
            return MT_ERR_NULL_PTR;
        }
        let secret = slice::from_raw_parts(secret, MT_BIZ_ID_LEN);
        match mt_business::invite_id(secret) {
            Ok(id) => {
                slice::from_raw_parts_mut(out, MT_BIZ_ID_LEN).copy_from_slice(&id);
                MT_OK
            },
            Err(e) => code(&e),
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mt_biz_heads(
    stream: *const u8,
    stream_len: usize,
    out: *mut u8,
    cap: usize,
    out_len: *mut usize,
) -> c_int {
    guard(|| {
        if out_len.is_null() {
            return MT_ERR_NULL_PTR;
        }
        let Some(stream) = input(stream, stream_len) else {
            return MT_ERR_NULL_PTR;
        };
        match mt_business::heads(stream) {
            Ok(bytes) => emit(&bytes, out, cap, out_len),
            Err(e) => code(&e),
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mt_biz_after(
    stream: *const u8,
    stream_len: usize,
    heads: *const u8,
    heads_len: usize,
    out: *mut u8,
    cap: usize,
    out_len: *mut usize,
) -> c_int {
    guard(|| {
        if out_len.is_null() {
            return MT_ERR_NULL_PTR;
        }
        let (Some(stream), Some(heads)) = (input(stream, stream_len), input(heads, heads_len))
        else {
            return MT_ERR_NULL_PTR;
        };
        match mt_business::after(stream, heads) {
            Ok(chain) => emit(&chain, out, cap, out_len),
            Err(e) => code(&e),
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn mt_biz_keep(
    org: *const u8,
    stream: *const u8,
    stream_len: usize,
    out: *mut u8,
    cap: usize,
    out_len: *mut usize,
) -> c_int {
    guard(|| {
        if org.is_null() || out_len.is_null() {
            return MT_ERR_NULL_PTR;
        }
        let Some(stream) = input(stream, stream_len) else {
            return MT_ERR_NULL_PTR;
        };
        let org = slice::from_raw_parts(org, MT_BIZ_ID_LEN);
        match mt_business::keep(org, stream) {
            Ok(chain) => emit(&chain, out, cap, out_len),
            Err(e) => code(&e),
        }
    })
}
