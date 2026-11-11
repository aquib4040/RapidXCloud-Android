//! envelope.rs — Master Secret Hierarchy & Recovery Key Envelope Encryption
//! Proton/MEGA Model: Stable Master Secret M wrapped under Password and Recovery Key.

use crate::aes_gcm;
use crate::error::{CryptoError, Result};
use crate::kdf::{self, KDFProfile, KEY_LEN};
use base64ct::{Base64, Encoding};
use serde::{Deserialize, Serialize};
use zeroize::Zeroize;

pub const WRAP_INFO_PW: &str = "master-wrap-password";
pub const WRAP_INFO_RECOVERY: &str = "master-wrap-recovery";
pub const FILE_ENCRYPTION_INFO: &str = "file-encryption";
pub const LOGIN_AUTH_INFO: &str = "login-auth";

const RECOVERY_ALPHABET: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WrappedSecret {
    pub data: String, // Base64 [12B IV][Ciphertext][16B Tag]
    pub salt: String, // Base64
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KeyBag {
    pub wrapped_pw: WrappedSecret,
    pub wrapped_recovery: WrappedSecret,
}

/// Generates a fresh random 32-byte Master Secret M.
pub fn generate_master_secret() -> Result<[u8; KEY_LEN]> {
    aes_gcm::generate_key()
}

/// Generates a human-friendly 160-bit alphanumeric Emergency Recovery Code
/// formatted as XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX
pub fn generate_recovery_code() -> Result<String> {
    let mut random_bytes = [0u8; 32];
    getrandom::getrandom(&mut random_bytes).map_err(|_| CryptoError::EntropyError)?;

    let mut out = String::with_capacity(39);
    for i in 0..32 {
        let idx = (random_bytes[i] as usize) & 31;
        out.push(RECOVERY_ALPHABET[idx] as char);
        if i % 5 == 4 && i != 31 {
            out.push('-');
        }
    }
    Ok(out)
}

/// Normalizes recovery code: uppercase, removes dashes and spaces.
pub fn normalize_recovery_code(code: &str) -> String {
    code.chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .map(|c| c.to_ascii_uppercase())
        .collect()
}

/// Derives wrap key from secret, salt, and HKDF info tag.
fn derive_wrap_key(secret: &str, salt: &[u8], info: &str) -> Result<Vec<u8>> {
    let mk = kdf::derive_key_profile(secret, salt, KDFProfile::Interactive64MB)?;
    kdf::hkdf_expand(&mk, info.as_bytes(), KEY_LEN)
}

/// Wraps Master Secret M under a secret string using AES-256-GCM.
pub fn wrap_master(master_secret: &[u8], secret: &str, info: &str) -> Result<WrappedSecret> {
    let salt = kdf::generate_salt(16)?;
    let mut wrap_key = derive_wrap_key(secret, &salt, info)?;
    let nonce = aes_gcm::generate_nonce()?;

    let ciphertext = aes_gcm::encrypt(&wrap_key, &nonce, master_secret, None)?;
    wrap_key.zeroize();

    let mut sealed = Vec::with_capacity(nonce.len() + ciphertext.len());
    sealed.extend_from_slice(&nonce);
    sealed.extend_from_slice(&ciphertext);

    Ok(WrappedSecret {
        data: Base64::encode_string(&sealed),
        salt: Base64::encode_string(&salt),
    })
}

/// Unwraps Master Secret M using secret string and HKDF info tag.
pub fn unwrap_master(wrapped: &WrappedSecret, secret: &str, info: &str) -> Result<Vec<u8>> {
    let salt = Base64::decode_vec(&wrapped.salt)
        .map_err(|e| CryptoError::InvalidFormat(format!("Salt base64 invalid: {}", e)))?;
    let sealed = Base64::decode_vec(&wrapped.data)
        .map_err(|e| CryptoError::InvalidFormat(format!("Data base64 invalid: {}", e)))?;

    if sealed.len() < aes_gcm::NONCE_LEN + aes_gcm::TAG_LEN {
        return Err(CryptoError::AuthenticationFailed);
    }

    let nonce = &sealed[..aes_gcm::NONCE_LEN];
    let ciphertext = &sealed[aes_gcm::NONCE_LEN..];

    let mut wrap_key = derive_wrap_key(secret, &salt, info)?;
    let plaintext = aes_gcm::decrypt(&wrap_key, nonce, ciphertext, None);
    wrap_key.zeroize();

    plaintext
}

/// Derives the long-lived file encryption key encKey from Master Secret M.
pub fn derive_enc_key(master_secret: &[u8]) -> Result<Vec<u8>> {
    kdf::hkdf_expand(master_secret, FILE_ENCRYPTION_INFO.as_bytes(), KEY_LEN)
}

/// Complete account enrollment: generates M, wraps under password and recovery code, returns KeyBag.
pub fn enroll_master(password: &str) -> Result<(Vec<u8>, KeyBag, String)> {
    let mut m = generate_master_secret()?;
    let recovery_code = generate_recovery_code()?;
    let norm_code = normalize_recovery_code(&recovery_code);

    let wrapped_pw = wrap_master(&m, password, WRAP_INFO_PW)?;
    let wrapped_recovery = wrap_master(&m, &norm_code, WRAP_INFO_RECOVERY)?;

    let enc_key = derive_enc_key(&m)?;
    m.zeroize();

    let key_bag = KeyBag {
        wrapped_pw,
        wrapped_recovery,
    };

    Ok((enc_key, key_bag, recovery_code))
}

/// Unlocks KeyBag using account password.
pub fn unlock_with_password(key_bag: &KeyBag, password: &str) -> Result<(Vec<u8>, Vec<u8>)> {
    let m = unwrap_master(&key_bag.wrapped_pw, password, WRAP_INFO_PW)?;
    let enc_key = derive_enc_key(&m)?;
    Ok((enc_key, m))
}

/// Recovers Master Secret M using Recovery Key, and re-wraps M under new password.
pub fn recover_with_code(
    key_bag: &KeyBag,
    recovery_code: &str,
    new_password: &str,
) -> Result<(Vec<u8>, WrappedSecret, Vec<u8>)> {
    let norm_code = normalize_recovery_code(recovery_code);
    let m = unwrap_master(&key_bag.wrapped_recovery, &norm_code, WRAP_INFO_RECOVERY)?;
    let new_wrapped_pw = wrap_master(&m, new_password, WRAP_INFO_PW)?;
    let enc_key = derive_enc_key(&m)?;
    Ok((enc_key, new_wrapped_pw, m))
}

/// Derives deterministic salt for zero-knowledge auth credential.
pub fn salt_for_email(email: &str) -> [u8; 16] {
    use sha2::{Digest, Sha256};
    let norm = email.trim().to_lowercase();
    let mut hasher = Sha256::new();
    hasher.update(format!("rxc-e2e-salt-v1|{}", norm).as_bytes());
    let digest = hasher.finalize();
    let mut salt = [0u8; 16];
    salt.copy_from_slice(&digest[..16]);
    salt
}

/// Derives 1:1 WebCrypto-compatible auth credential (authKeyHex + "_Aa1!") for login.
pub fn derive_auth_key_hex(email: &str, password: &str) -> Result<String> {
    let salt = salt_for_email(email);
    let master = kdf::derive_key_profile(password, &salt, KDFProfile::Interactive64MB)?;
    let auth_key = kdf::hkdf_expand(&master, LOGIN_AUTH_INFO.as_bytes(), KEY_LEN)?;
    let hex = auth_key.iter().map(|b| format!("{:02x}", b)).collect::<String>();
    Ok(format!("{}_Aa1!", hex))
}
