//! kdf.rs — Password-Based Key Derivation Engine (Argon2id & HKDF)
//! RFC 9106 (Argon2id) and RFC 5869 (HKDF-SHA256) implementation with strict DoS protection.

use crate::error::{CryptoError, Result};
use argon2::{Algorithm, Argon2, Params, Version};
use hkdf::Hkdf;
use sha2::Sha256;
use unicode_normalization::UnicodeNormalization;
use zeroize::Zeroize;

pub const KEY_LEN: usize = 32;
pub const MIN_SALT_LEN: usize = 16;
pub const DEFAULT_SALT_LEN: usize = 32;

// Strict DoS upper limits on KDF parameters
pub const MAX_KDF_MEMORY_KIB: u32 = 1_048_576; // 1 GiB
pub const MAX_KDF_ITERATIONS: u32 = 20;
pub const MAX_KDF_PARALLELISM: u32 = 16;
pub const MIN_KDF_MEMORY_KIB: u32 = 1_024; // 1 MiB minimum
pub const MIN_KDF_ITERATIONS: u32 = 1;

/// Predefined versioned KDF Profiles
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KDFProfile {
    /// 256 MiB memory, t=3, p=4 (Target per Master Prompt)
    Production256MB,
    /// 64 MiB memory, t=3, p=1 (Default for RapidxCloud Web & Mobile)
    Interactive64MB,
    /// 512 MiB memory, t=4, p=4 (Cold storage / high security)
    HighSecurity512MB,
    /// Fast profile for automated test suites
    TestFast,
}

impl KDFProfile {
    pub fn memory_kib(&self) -> u32 {
        match self {
            KDFProfile::Production256MB => 262_144,   // 256 MiB
            KDFProfile::Interactive64MB => 65_536,    // 64 MiB
            KDFProfile::HighSecurity512MB => 524_288, // 512 MiB
            KDFProfile::TestFast => 1_024,           // 1 MiB
        }
    }

    pub fn iterations(&self) -> u32 {
        match self {
            KDFProfile::Production256MB => 3,
            KDFProfile::Interactive64MB => 3,
            KDFProfile::HighSecurity512MB => 4,
            KDFProfile::TestFast => 1,
        }
    }

    pub fn parallelism(&self) -> u32 {
        match self {
            KDFProfile::Production256MB => 4,
            KDFProfile::Interactive64MB => 1,
            KDFProfile::HighSecurity512MB => 4,
            KDFProfile::TestFast => 1,
        }
    }

    pub fn profile_name(&self) -> &'static str {
        match self {
            KDFProfile::Production256MB => "production-256mb",
            KDFProfile::Interactive64MB => "interactive-64mb",
            KDFProfile::HighSecurity512MB => "high-security-512mb",
            KDFProfile::TestFast => "test-fast",
        }
    }
}

/// Generates fresh cryptographically secure salt using OS CSPRNG.
pub fn generate_salt(len: usize) -> Result<Vec<u8>> {
    if len < MIN_SALT_LEN {
        return Err(CryptoError::InvalidParameter("Salt length below 16 bytes"));
    }
    let mut salt = vec![0u8; len];
    getrandom::getrandom(&mut salt).map_err(|_| CryptoError::EntropyError)?;
    Ok(salt)
}

/// Validates KDF parameters against strict denial-of-service bounds.
pub fn validate_kdf_params(memory_kib: u32, iterations: u32, parallelism: u32) -> Result<()> {
    if memory_kib < MIN_KDF_MEMORY_KIB || memory_kib > MAX_KDF_MEMORY_KIB {
        return Err(CryptoError::KdfError(format!(
            "Memory cost {} KiB outside safe bounds [{}, {}]",
            memory_kib, MIN_KDF_MEMORY_KIB, MAX_KDF_MEMORY_KIB
        )));
    }
    if iterations < MIN_KDF_ITERATIONS || iterations > MAX_KDF_ITERATIONS {
        return Err(CryptoError::KdfError(format!(
            "Iterations {} outside safe bounds [{}, {}]",
            iterations, MIN_KDF_ITERATIONS, MAX_KDF_ITERATIONS
        )));
    }
    if parallelism < 1 || parallelism > MAX_KDF_PARALLELISM {
        return Err(CryptoError::KdfError(format!(
            "Parallelism {} outside safe bounds [1, {}]",
            parallelism, MAX_KDF_PARALLELISM
        )));
    }
    Ok(())
}

/// Derives a 32-byte key from a password and salt using Argon2id.
/// Normalizes password to Unicode NFC for consistent cross-platform behavior.
pub fn derive_key(
    password: &str,
    salt: &[u8],
    memory_kib: u32,
    iterations: u32,
    parallelism: u32,
    out_len: usize,
) -> Result<Vec<u8>> {
    if salt.len() < MIN_SALT_LEN {
        return Err(CryptoError::InvalidParameter("Salt length must be at least 16 bytes"));
    }
    validate_kdf_params(memory_kib, iterations, parallelism)?;

    // Unicode NFC normalization
    let mut normalized_pw: String = password.nfc().collect();

    let params = Params::new(memory_kib, iterations, parallelism, Some(out_len))
        .map_err(|e| CryptoError::KdfError(e.to_string()))?;

    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut out = vec![0u8; out_len];

    let res = argon2.hash_password_into(normalized_pw.as_bytes(), salt, &mut out);
    normalized_pw.zeroize();

    res.map_err(|e| {
        out.zeroize();
        CryptoError::KdfError(e.to_string())
    })?;

    Ok(out)
}

/// Derives a key using a predefined KDFProfile.
pub fn derive_key_profile(password: &str, salt: &[u8], profile: KDFProfile) -> Result<Vec<u8>> {
    derive_key(
        password,
        salt,
        profile.memory_kib(),
        profile.iterations(),
        profile.parallelism(),
        KEY_LEN,
    )
}

/// HKDF-SHA256 Expand (RFC 5869)
pub fn hkdf_expand(ikm: &[u8], info: &[u8], out_len: usize) -> Result<Vec<u8>> {
    let hk = Hkdf::<Sha256>::new(None, ikm);
    let mut okm = vec![0u8; out_len];
    hk.expand(info, &mut okm)
        .map_err(|_| CryptoError::InvalidParameter("HKDF expand output length too long"))?;
    Ok(okm)
}
