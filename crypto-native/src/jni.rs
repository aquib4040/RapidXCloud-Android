//! jni.rs — Android JNI Native Interface Bindings
//! Exposes NativeCrypto methods to Java/Kotlin in in.rapidxcloud.drive.crypto.NativeCrypto

use crate::aes_gcm;
use crate::kdf;
use crate::mtproto::{self, CANCELLATION_FLAGS, MTPROTO_UPLOAD_CHUNK_BYTES};
use crate::split;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::JNIEnv;
use std::sync::atomic::Ordering;
use zeroize::Zeroize;

#[no_mangle]
pub extern "system" fn Java_in_rapidxcloud_drive_crypto_NativeCrypto_isNativeAvailable(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    1
}

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

    let derived = match kdf::derive_key(
        &password_str,
        &salt_bytes,
        memory_kb as u32,
        iterations as u32,
        parallelism as u32,
        out_len_usize,
    ) {
        Ok(d) => d,
        Err(_) => return std::ptr::null_mut(),
    };

    let byte_array = match env.byte_array_from_slice(&derived) {
        Ok(arr) => arr,
        Err(_) => return std::ptr::null_mut(),
    };

    byte_array.into_raw()
}

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

    let len = if out_length > 0 {
        out_length as usize
    } else {
        32
    };

    let okm = match kdf::hkdf_expand(&ikm_bytes, &info_bytes, len) {
        Ok(k) => k,
        Err(_) => return std::ptr::null_mut(),
    };

    match env.byte_array_from_slice(&okm) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

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

    let mut out = match split::xor_shares(&a, &b) {
        Ok(x) => x,
        Err(_) => return std::ptr::null_mut(),
    };

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

#[no_mangle]
pub extern "system" fn Java_in_rapidxcloud_drive_crypto_NativeCrypto_aesGcmEncrypt(
    env: JNIEnv,
    _class: JClass,
    key: JByteArray,
    nonce: JByteArray,
    plaintext: JByteArray,
    aad: JByteArray,
) -> jbyteArray {
    let key_bytes = match env.convert_byte_array(&key) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let nonce_bytes = match env.convert_byte_array(&nonce) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let pt_bytes = match env.convert_byte_array(&plaintext) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let aad_bytes = env.convert_byte_array(&aad).ok();

    let ct = match aes_gcm::encrypt(
        &key_bytes,
        &nonce_bytes,
        &pt_bytes,
        aad_bytes.as_deref(),
    ) {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };

    match env.byte_array_from_slice(&ct) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_in_rapidxcloud_drive_crypto_NativeCrypto_aesGcmDecrypt(
    env: JNIEnv,
    _class: JClass,
    key: JByteArray,
    nonce: JByteArray,
    ciphertext: JByteArray,
    aad: JByteArray,
) -> jbyteArray {
    let key_bytes = match env.convert_byte_array(&key) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let nonce_bytes = match env.convert_byte_array(&nonce) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let ct_bytes = match env.convert_byte_array(&ciphertext) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let aad_bytes = env.convert_byte_array(&aad).ok();

    let pt = match aes_gcm::decrypt(
        &key_bytes,
        &nonce_bytes,
        &ct_bytes,
        aad_bytes.as_deref(),
    ) {
        Ok(p) => p,
        Err(_) => return std::ptr::null_mut(),
    };

    match env.byte_array_from_slice(&pt) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

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

    let framed = mtproto::frame_mtproto_upload_part(
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

    match mtproto::parse_telethon_session(&session) {
        Some((_, _, _, _, auth_key_id)) => auth_key_id,
        None => 0,
    }
}

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

    if idx >= total || size == 0 || size > MTPROTO_UPLOAD_CHUNK_BYTES {
        0
    } else {
        1
    }
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

    if mtproto::parse_telethon_session(&session).is_some() {
        1
    } else {
        0
    }
}
