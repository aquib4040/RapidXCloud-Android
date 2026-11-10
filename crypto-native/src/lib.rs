use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::JNIEnv;

use argon2::{Algorithm, Argon2, Params, Version};
use base64ct::{Base64, Encoding};
use hkdf::Hkdf;
use sha1::{Digest as Sha1Digest, Sha1};
use sha2::Sha256;
use std::sync::atomic::{AtomicBool, Ordering};
use zeroize::Zeroize;

use grammers_tl_types as tl;
use tl::{Identifiable, Serializable};

/// Global atomic cancellation flags mapped by transfer task slot (0..64)
static CANCELLATION_FLAGS: [AtomicBool; 64] = {
    #[allow(clippy::declare_interior_mutable_const)]
    const INIT: AtomicBool = AtomicBool::new(false);
    [INIT; 64]
};

/// MTProto Maximum Allowed Part Size: 512 KiB (524,288 bytes)
pub const MTPROTO_UPLOAD_CHUNK_BYTES: usize = 512 * 1024; // 524,288 bytes

/// TL Constructor for upload.saveFilePart from grammers TL types (< 10 MB)
pub const TL_SAVE_FILE_PART: u32 = tl::functions::upload::SaveFilePart::CONSTRUCTOR_ID;

/// TL Constructor for upload.saveBigFilePart from grammers TL types (>= 10 MB)
pub const TL_SAVE_BIG_FILE_PART: u32 = tl::functions::upload::SaveBigFilePart::CONSTRUCTOR_ID;

/// 10 MB threshold in bytes
pub const TEN_MEGABYTES: i64 = 10 * 1024 * 1024;

/// Checks if native Rust crypto & MTProto engine is loaded
#[no_mangle]
pub extern "system" fn Java_in_rapidxcloud_drive_crypto_NativeCrypto_isNativeAvailable(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    1
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

    let hk = Hkdf::<Sha256>::new(None, &ikm_bytes);

    let len = if out_length > 0 {
        out_length as usize
    } else {
        32
    };
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

/// Builds MTProto upload payload using the pure-Rust Telegram MTProto framework (grammers):
/// - If total_file_size < 10 MB: upload.saveFilePart#b304a624 (file_id:long, file_part:int, bytes:bytes)
/// - If total_file_size >= 10 MB: upload.saveBigFilePart#de7b673d (file_id:long, file_part:int, file_total_parts:int, bytes:bytes)
pub fn frame_mtproto_upload_part(
    file_id: i64,
    part_index: i32,
    total_parts: i32,
    total_file_size: i64,
    chunk: &[u8],
) -> Vec<u8> {
    if total_file_size < TEN_MEGABYTES {
        let req = tl::functions::upload::SaveFilePart {
            file_id,
            file_part: part_index,
            bytes: chunk.to_vec(),
        };
        req.to_bytes()
    } else {
        let req = tl::functions::upload::SaveBigFilePart {
            file_id,
            file_part: part_index,
            file_total_parts: total_parts,
            bytes: chunk.to_vec(),
        };
        req.to_bytes()
    }
}

/// JNI bridge to serialize MTProto upload part based on file size (< 10 MB vs >= 10 MB)
#[no_mangle]
pub extern "system" fn Java_in_rapidxcloud_drive_crypto_NativeCrypto_frameUploadPart(
    env: JNIEnv,
    _class: JClass,
    file_id: jlong,
    part_index: jint,
    total_parts: jint,
    total_file_size: jlong,
    chunk: JByteArray,
) -> jbyteArray {
    let chunk_bytes = match env.convert_byte_array(&chunk) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    let framed = frame_mtproto_upload_part(
        file_id,
        part_index,
        total_parts,
        total_file_size,
        &chunk_bytes,
    );

    match env.byte_array_from_slice(&framed) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Extracts AuthKeyId (lower 64 bits of SHA1(auth_key))
pub fn compute_auth_key_id(auth_key: &[u8]) -> i64 {
    let mut hasher = Sha1::new();
    hasher.update(auth_key);
    let result = hasher.finalize();
    let mut bytes = [0u8; 8];
    bytes.copy_from_slice(&result[12..20]);
    i64::from_le_bytes(bytes)
}

/// Decodes Telethon v1 StringSession ('1...') to (dc_id, ip, port, auth_key_id)
pub fn parse_telethon_session(session: &str) -> Option<(i32, String, i32, Vec<u8>, i64)> {
    let trimmed = session.trim();
    if !trimmed.starts_with('1') {
        return None;
    }

    let payload = &trimmed[1..];
    let decoded = Base64::decode_vec(payload).ok()?;
    if decoded.len() < 263 {
        return None;
    }

    let dc_id = decoded[0] as i32;
    let is_ipv6 = decoded.len() >= 275;
    let mut pos = 1;

    let ip_address = if !is_ipv6 {
        let ip = format!(
            "{}.{}.{}.{}",
            decoded[1], decoded[2], decoded[3], decoded[4]
        );
        pos = 5;
        ip
    } else {
        let mut groups = Vec::new();
        for i in (0..16).step_by(2) {
            let val = ((decoded[pos + i] as u16) << 8) | (decoded[pos + i + 1] as u16);
            groups.push(format!("{:x}", val));
        }
        pos += 16;
        groups.join(":")
    };

    let port = (((decoded[pos] as u16) << 8) | (decoded[pos + 1] as u16)) as i32;
    pos += 2;

    if pos + 256 > decoded.len() {
        return None;
    }

    let auth_key = decoded[pos..pos + 256].to_vec();
    let auth_key_id = compute_auth_key_id(&auth_key);

    Some((dc_id, ip_address, port, auth_key, auth_key_id))
}

/// JNI bridge to validate and parse Telethon session string
#[no_mangle]
pub extern "system" fn Java_in_rapidxcloud_drive_crypto_NativeCrypto_parseTelethonSession(
    mut env: JNIEnv,
    _class: JClass,
    session_str: JString,
) -> jlong {
    let session: String = match env.get_string(&session_str) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    match parse_telethon_session(&session) {
        Some((_, _, _, _, auth_key_id)) => auth_key_id,
        None => 0,
    }
}

/// Validates MTProto chunk size (<= 512 KiB part limit) and computes part index offset
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
        return 0;
    }

    // Telegram MTProto strictly enforces 512 KiB ceiling for upload parts
    if size == 0 || size > MTPROTO_UPLOAD_CHUNK_BYTES {
        return 0;
    }

    1
}

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

    if parse_telethon_session(&session).is_some() {
        1
    } else {
        0
    }
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
    fn test_webcrypto_hkdf_login_auth_parity() {
        let master = [7u8; 32];
        let hk = Hkdf::<Sha256>::new(None, &master);
        let mut okm = [0u8; 32];
        hk.expand(b"login-auth", &mut okm).unwrap();
        let expected = "9369d8f6b85f54b739302c11d675f693f433878a7c534b5d25b8d9714e96bdee";
        let hex = okm.iter().map(|b| format!("{:02x}", b)).collect::<String>();
        assert_eq!(hex, expected);
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

        let mut original_a = [0u8; 32];
        for i in 0..32 {
            original_a[i] = recovered[i] ^ share_b[i];
        }
        assert_eq!(original_a, share_a);
    }

    #[test]
    fn test_mtproto_upload_chunk_limit() {
        // Upload chunk size ceiling is strictly 512 KiB (524288 bytes)
        assert_eq!(MTPROTO_UPLOAD_CHUNK_BYTES, 512 * 1024);
        assert!(524288 <= MTPROTO_UPLOAD_CHUNK_BYTES);
        assert!(524289 > MTPROTO_UPLOAD_CHUNK_BYTES);
    }

    #[test]
    fn test_tl_serialization_small_file() {
        // Under 10 MB uses upload.saveFilePart#b304a624
        let dummy_chunk = vec![0x42u8; 1024];
        let framed = frame_mtproto_upload_part(123456789, 0, 1, 5 * 1024 * 1024, &dummy_chunk);

        // First 4 bytes must be TL_SAVE_FILE_PART constructor (little-endian: 0xb304a624)
        assert_eq!(&framed[0..4], &TL_SAVE_FILE_PART.to_le_bytes());
    }

    #[test]
    fn test_tl_serialization_big_file() {
        // 10 MB or more uses upload.saveBigFilePart#de7b673d
        let dummy_chunk = vec![0x42u8; 512 * 1024];
        let framed = frame_mtproto_upload_part(987654321, 0, 100, 50 * 1024 * 1024, &dummy_chunk);

        // First 4 bytes must be TL_SAVE_BIG_FILE_PART constructor (little-endian: 0xde7b673d)
        assert_eq!(&framed[0..4], &TL_SAVE_BIG_FILE_PART.to_le_bytes());
    }

    #[test]
    fn test_telethon_session_string_parsing() {
        let mut dummy_bytes = Vec::new();
        dummy_bytes.push(2u8); // DC 2
        dummy_bytes.extend_from_slice(&[149, 154, 167, 51]); // IP: 149.154.167.51
        dummy_bytes.extend_from_slice(&443u16.to_be_bytes()); // Port 443
        dummy_bytes.extend_from_slice(&[0x7Au8; 256]); // 256-byte AuthKey

        let b64 = Base64::encode_string(&dummy_bytes);
        let session_str = format!("1{}", b64);

        let parsed = parse_telethon_session(&session_str);
        assert!(parsed.is_some());
        let (dc_id, ip, port, auth_key, auth_key_id) = parsed.unwrap();
        assert_eq!(dc_id, 2);
        assert_eq!(ip, "149.154.167.51");
        assert_eq!(port, 443);
        assert_eq!(auth_key.len(), 256);
        assert_ne!(auth_key_id, 0);
    }
}
