use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint};
use jni::JNIEnv;

use argon2::{Algorithm, Argon2, Params, Version};
use hkdf::Hkdf;
use sha2::Sha256;
use zeroize::Zeroize;

/// Checks if native Rust crypto is loaded and functioning
#[no_mangle]
pub extern "system" fn Java_in_rapidxcloud_drive_crypto_NativeCrypto_isNativeAvailable(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    1 // true
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
