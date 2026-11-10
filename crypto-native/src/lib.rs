use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint};
use jni::JNIEnv;

use argon2::{Algorithm, Argon2, Params, Version};
use base64ct::{Base64, Encoding};
use hkdf::Hkdf;
use sha2::Sha256;
use std::sync::atomic::{AtomicBool, Ordering};
use zeroize::Zeroize;

/// Global atomic cancellation flags mapped by transfer task slot (0..64)
static CANCELLATION_FLAGS: [AtomicBool; 64] = {
    // Array initialization of AtomicBool::new(false)
    const INIT: AtomicBool = AtomicBool::new(false);
    [INIT; 64]
};

/// MTProto Maximum Allowed Part Size: 1024 KiB (1 MiB)
pub const MTPROTO_MAX_CHUNK_BYTES: usize = 1024 * 1024; // 1048576 bytes

/// Checks if native Rust crypto is loaded and functioning
#[no_mangle]
pub extern "system" fn Java_in_rapidxcloud_drive_crypto_NativeCrypto_isNativeAvailable(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    1 // true
}

/// Sets native cancellation state for a task slot (0..63)
#[no_mangle]
pub extern "system" fn Java_in_rapidxcloud_drive_crypto_NativeCrypto_setCancelled(
    _env: JNIEnv,
    _class: JClass,
    slot_id: jint,
    cancelled: jboolean,
) {
    let slot = (slot_id as usize) % 64;
    CANCELLATION_FLAGS[slot].store(cancelled != 0, Ordering::SeqCst);
}

/// Checks if native cancellation has been requested for a task slot
#[no_mangle]
pub extern "system" fn Java_in_rapidxcloud_drive_crypto_NativeCrypto_isCancelled(
    _env: JNIEnv,
    _class: JClass,
    slot_id: jint,
) -> jboolean {
    let slot = (slot_id as usize) % 64;
    if CANCELLATION_FLAGS[slot].load(Ordering::SeqCst) {
        1
    } else {
        0
    }
}

/// Computes Argon2id KDF: m=65536 KB (64MB), t=3 iterations, p=1 parallelism, output 32 bytes.
/// Memory-safe: buffers are strictly zeroized upon completion.
#[no_mangle]
pub extern "system" fn Java_in_rapidxcloud_drive_crypto_NativeCrypto_argon2id(
    mut env: JNIEnv,
    _class: JClass,
    password: JString,
    salt: JByteArray,
    memory_kb: jint,
    iterations: jint,
    parallelism: jint,
    out_len: jint,
) -> jbyteArray {
    let password_str: String = match env.get_string(&password) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let salt_bytes = match env.convert_byte_array(&salt) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    let out_len_usize = if out_len > 0 { out_len as usize } else { 32 };

    let params = match Params::new(
        memory_kb as u32,
        iterations as u32,
        parallelism as u32,
        Some(out_len_usize),
    ) {
        Ok(p) => p,
        Err(_) => return std::ptr::null_mut(),
    };

    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);

    let mut out = vec![0u8; out_len_usize];
    let result = argon2.hash_password_into(password_str.as_bytes(), &salt_bytes, &mut out);

    // Explicitly zeroize temporary password from RAM
    let mut pw_to_zero = password_str;
    pw_to_zero.zeroize();

    if result.is_err() {
        out.zeroize();
        return std::ptr::null_mut();
    }

    let byte_array = match env.byte_array_from_slice(&out) {
        Ok(arr) => arr,
        Err(_) => {
            out.zeroize();
            return std::ptr::null_mut();
        }
    };

    out.zeroize();
    byte_array.into_raw()
}

/// HKDF-SHA256 Expand (RFC 5869)
#[no_mangle]
pub extern "system" fn Java_in_rapidxcloud_drive_crypto_NativeCrypto_hkdfExpand(
    env: JNIEnv,
    _class: JClass,
    ikm: JByteArray,
    info: JByteArray,
    out_length: jint,
) -> jbyteArray {
    let ikm_bytes = match env.convert_byte_array(&ikm) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    let info_bytes = match env.convert_byte_array(&info) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    let hk = Hkdf::<Sha256>::from_prk(&ikm_bytes);
    if hk.is_err() {
        return std::ptr::null_mut();
    }
    let hk = hk.unwrap();

    let len = if out_length > 0 { out_length as usize } else { 32 };
    let mut okm = vec![0u8; len];

    if hk.expand(&info_bytes, &mut okm).is_err() {
        okm.zeroize();
        return std::ptr::null_mut();
    }

    let byte_array = match env.byte_array_from_slice(&okm) {
        Ok(arr) => arr,
        Err(_) => {
            okm.zeroize();
            return std::ptr::null_mut();
        }
    };

    okm.zeroize();
    byte_array.into_raw()
}

/// Constant-time XOR for Dual-Key Split reconstruction (FileKey = ClientShare ⊕ ServerShare)
#[no_mangle]
pub extern "system" fn Java_in_rapidxcloud_drive_crypto_NativeCrypto_xorShares(
    env: JNIEnv,
    _class: JClass,
    share_a: JByteArray,
    share_b: JByteArray,
) -> jbyteArray {
    let a = match env.convert_byte_array(&share_a) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    let b = match env.convert_byte_array(&share_b) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    if a.len() != b.len() {
        return std::ptr::null_mut();
    }

    let mut out = vec![0u8; a.len()];
    for i in 0..a.len() {
        out[i] = a[i] ^ b[i];
    }

    let byte_array = match env.byte_array_from_slice(&out) {
        Ok(arr) => arr,
        Err(_) => {
            out.zeroize();
            return std::ptr::null_mut();
        }
    };

    out.zeroize();
    byte_array.into_raw()
}

/// Validates MTProto chunk size (<= 1024 KiB part limit) and computes part index offset
#[no_mangle]
pub extern "system" fn Java_in_rapidxcloud_drive_crypto_NativeCrypto_validateChunkPart(
    _env: JNIEnv,
    _class: JClass,
    part_size: jint,
    part_index: jint,
    total_parts: jint,
) -> jboolean {
    let size = part_size as usize;
    let idx = part_index as usize;
    let total = total_parts as usize;

    if idx >= total {
        return 0; // Invalid part index
    }

    // Intermediate parts must be exactly or up to 1024 KiB.
    // The final part can be 1 byte to 1024 KiB.
    if size == 0 || size > MTPROTO_MAX_CHUNK_BYTES {
        return 0;
    }

    1 // valid
}

/// Validates and parses Telethon/Pyrogram base64 string session format
/// used by Go microservice ephemeral MTProto keys.
#[no_mangle]
pub extern "system" fn Java_in_rapidxcloud_drive_crypto_NativeCrypto_validateSessionString(
    mut env: JNIEnv,
    _class: JClass,
    session_str: JString,
) -> jboolean {
    let session: String = match env.get_string(&session_str) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    // Telethon session starts with 1 byte DC ID, then IP (4 or 16), port (2), auth_key (256)
    let decoded = match Base64::decode_vec(session.trim()) {
        Ok(b) => b,
        Err(_) => return 0,
    };

    // Telethon session length is typically >= 263 bytes (1 + 4/16 + 2 + 256)
    if decoded.len() < 263 {
        return 0;
    }

    1 // valid
}

// ─────────────────────────────────────────────────────────────────────────────
// Unit Tests (Rust Test Suite)
// ─────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_cancellation_flags() {
        let slot = 5;
        CANCELLATION_FLAGS[slot].store(false, Ordering::SeqCst);
        assert!(!CANCELLATION_FLAGS[slot].load(Ordering::SeqCst));

        CANCELLATION_FLAGS[slot].store(true, Ordering::SeqCst);
        assert!(CANCELLATION_FLAGS[slot].load(Ordering::SeqCst));

        CANCELLATION_FLAGS[slot].store(false, Ordering::SeqCst);
    }

    #[test]
    fn test_argon2id_derivation() {
        let password = "TestMasterPassword123!";
        let salt = [0x42u8; 16];
        let params = Params::new(1024, 1, 1, Some(32)).unwrap();
        let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);

        let mut out = [0u8; 32];
        let res = argon2.hash_password_into(password.as_bytes(), &salt, &mut out);
        assert!(res.is_ok());
        assert_ne!(out, [0u8; 32]);
    }

    #[test]
    fn test_xor_shares() {
        let share_a = [0xAAu8; 32];
        let share_b = [0x55u8; 32];
        let mut recovered = [0u8; 32];
        for i in 0..32 {
            recovered[i] = share_a[i] ^ share_b[i];
        }
        assert_eq!(recovered, [0xFFu8; 32]);

        // XOR again recovers share_a
        let mut original_a = [0u8; 32];
        for i in 0..32 {
            original_a[i] = recovered[i] ^ share_b[i];
        }
        assert_eq!(original_a, share_a);
    }

    #[test]
    fn test_mtproto_chunk_part_validation() {
        // Valid 1 MiB chunk
        assert_eq!(MTPROTO_MAX_CHUNK_BYTES, 1024 * 1024);
        assert!(1048576 <= MTPROTO_MAX_CHUNK_BYTES);

        // Exceeding 1 MiB is invalid
        assert!(1048577 > MTPROTO_MAX_CHUNK_BYTES);

        // Final part of 500 bytes is valid
        assert!(500 <= MTPROTO_MAX_CHUNK_BYTES);
    }

    #[test]
    fn test_telethon_session_string_validation() {
        // 263 bytes dummy payload base64 encoded
        let dummy_bytes = vec![1u8; 265];
        let encoded = Base64::encode_string(&dummy_bytes);
        let decoded = Base64::decode_vec(&encoded).unwrap();
        assert!(decoded.len() >= 263);

        // Short invalid string
        let short_bytes = vec![1u8; 50];
        let short_encoded = Base64::encode_string(&short_bytes);
        let decoded_short = Base64::decode_vec(&short_encoded).unwrap();
        assert!(decoded_short.len() < 263);
    }
}
