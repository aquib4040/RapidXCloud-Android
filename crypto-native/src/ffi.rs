//! ffi.rs — Cross-Platform C-ABI Foreign Function Interface (FFI)
//! Standard C exports for linking in iOS (Swift/ObjC), Linux, macOS, Windows, Go, Python.

use crate::aes_gcm;
use crate::error::CryptoError;
use crate::kdf;
use crate::split;
use std::slice;

pub const RXC_OK: i32 = 0;
pub const RXC_ERR_AUTH_FAILED: i32 = -1;
pub const RXC_ERR_INVALID_PARAM: i32 = -2;
pub const RXC_ERR_KDF_FAILED: i32 = -3;
pub const RXC_ERR_BUFFER_TOO_SMALL: i32 = -4;

/// Computes Argon2id KDF: writes out_len bytes into out_buf.
#[no_mangle]
pub unsafe extern "C" fn rxc_argon2id(
    password_ptr: *const u8,
    password_len: usize,
    salt_ptr: *const u8,
    salt_len: usize,
    memory_kib: u32,
    iterations: u32,
    parallelism: u32,
    out_buf: *mut u8,
    out_len: usize,
) -> i32 {
    if password_ptr.is_null() || salt_ptr.is_null() || out_buf.is_null() {
        return RXC_ERR_INVALID_PARAM;
    }

    let password_bytes = slice::from_raw_parts(password_ptr, password_len);
    let salt_bytes = slice::from_raw_parts(salt_ptr, salt_len);
    let out_slice = slice::from_raw_parts_mut(out_buf, out_len);

    let password_str = match std::str::from_utf8(password_bytes) {
        Ok(s) => s,
        Err(_) => return RXC_ERR_INVALID_PARAM,
    };

    match kdf::derive_key(
        password_str,
        salt_bytes,
        memory_kib,
        iterations,
        parallelism,
        out_len,
    ) {
        Ok(derived) => {
            out_slice.copy_from_slice(&derived);
            RXC_OK
        }
        Err(CryptoError::KdfError(_)) => RXC_ERR_KDF_FAILED,
        Err(_) => RXC_ERR_INVALID_PARAM,
    }
}

/// AES-256-GCM Encrypt: out_buf must be at least plaintext_len + 16 (tag)
#[no_mangle]
pub unsafe extern "C" fn rxc_aes_gcm_encrypt(
    key_ptr: *const u8,
    key_len: usize,
    nonce_ptr: *const u8,
    nonce_len: usize,
    plaintext_ptr: *const u8,
    plaintext_len: usize,
    aad_ptr: *const u8,
    aad_len: usize,
    out_buf: *mut u8,
    out_buf_len: usize,
    out_written: *mut usize,
) -> i32 {
    if key_ptr.is_null() || nonce_ptr.is_null() || out_buf.is_null() || out_written.is_null() {
        return RXC_ERR_INVALID_PARAM;
    }
    if key_len != 32 || nonce_len != 12 {
        return RXC_ERR_INVALID_PARAM;
    }

    let key = slice::from_raw_parts(key_ptr, key_len);
    let nonce = slice::from_raw_parts(nonce_ptr, nonce_len);
    let plaintext = if plaintext_len > 0 && !plaintext_ptr.is_null() {
        slice::from_raw_parts(plaintext_ptr, plaintext_len)
    } else {
        &[]
    };
    let aad = if aad_len > 0 && !aad_ptr.is_null() {
        Some(slice::from_raw_parts(aad_ptr, aad_len))
    } else {
        None
    };

    let required_len = plaintext.len() + 16;
    if out_buf_len < required_len {
        return RXC_ERR_BUFFER_TOO_SMALL;
    }

    match aes_gcm::encrypt(key, nonce, plaintext, aad) {
        Ok(ct) => {
            let out_slice = slice::from_raw_parts_mut(out_buf, ct.len());
            out_slice.copy_from_slice(&ct);
            *out_written = ct.len();
            RXC_OK
        }
        Err(_) => RXC_ERR_AUTH_FAILED,
    }
}

/// AES-256-GCM Decrypt: verifies tag and writes plaintext into out_buf
#[no_mangle]
pub unsafe extern "C" fn rxc_aes_gcm_decrypt(
    key_ptr: *const u8,
    key_len: usize,
    nonce_ptr: *const u8,
    nonce_len: usize,
    ciphertext_ptr: *const u8,
    ciphertext_len: usize,
    aad_ptr: *const u8,
    aad_len: usize,
    out_buf: *mut u8,
    out_buf_len: usize,
    out_written: *mut usize,
) -> i32 {
    if key_ptr.is_null() || nonce_ptr.is_null() || ciphertext_ptr.is_null() || out_buf.is_null() || out_written.is_null() {
        return RXC_ERR_INVALID_PARAM;
    }
    if key_len != 32 || nonce_len != 12 || ciphertext_len < 16 {
        return RXC_ERR_INVALID_PARAM;
    }

    let key = slice::from_raw_parts(key_ptr, key_len);
    let nonce = slice::from_raw_parts(nonce_ptr, nonce_len);
    let ciphertext = slice::from_raw_parts(ciphertext_ptr, ciphertext_len);
    let aad = if aad_len > 0 && !aad_ptr.is_null() {
        Some(slice::from_raw_parts(aad_ptr, aad_len))
    } else {
        None
    };

    let expected_pt_len = ciphertext_len - 16;
    if out_buf_len < expected_pt_len {
        return RXC_ERR_BUFFER_TOO_SMALL;
    }

    match aes_gcm::decrypt(key, nonce, ciphertext, aad) {
        Ok(pt) => {
            let out_slice = slice::from_raw_parts_mut(out_buf, pt.len());
            out_slice.copy_from_slice(&pt);
            *out_written = pt.len();
            RXC_OK
        }
        Err(CryptoError::AuthenticationFailed) => RXC_ERR_AUTH_FAILED,
        Err(_) => RXC_ERR_INVALID_PARAM,
    }
}

/// XOR Dual-Key Split combination (32 bytes)
#[no_mangle]
pub unsafe extern "C" fn rxc_xor_shares(
    share_a_ptr: *const u8,
    share_b_ptr: *const u8,
    len: usize,
    out_buf: *mut u8,
) -> i32 {
    if share_a_ptr.is_null() || share_b_ptr.is_null() || out_buf.is_null() {
        return RXC_ERR_INVALID_PARAM;
    }

    let a = slice::from_raw_parts(share_a_ptr, len);
    let b = slice::from_raw_parts(share_b_ptr, len);
    let out = slice::from_raw_parts_mut(out_buf, len);

    match split::xor_shares(a, b) {
        Ok(x) => {
            out.copy_from_slice(&x);
            RXC_OK
        }
        Err(_) => RXC_ERR_INVALID_PARAM,
    }
}
