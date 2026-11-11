//! error.rs — Cryptographic Error Types
//! Safe generic errors that prevent information leakage and padding/timing oracles.

use std::fmt;

#[derive(Debug, PartialEq, Eq)]
pub enum CryptoError {
    /// Authentication tag verification failed, or data has been manipulated.
    /// Never discloses whether the failure was due to key, nonce, tag, or AAD mismatch.
    AuthenticationFailed,

    /// One or more cryptographic parameters (key length, nonce length, salt length) are invalid.
    InvalidParameter(&'static str),

    /// Serialized packet, header, or framing format is invalid or corrupted.
    InvalidFormat(String),

    /// Argon2id key derivation error or parameters exceed DoS safety bounds.
    KdfError(String),

    /// Chunk stream violation: reordering, duplication, dropped chunk, or truncation detected.
    TamperingDetected(String),

    /// System entropy generator failed.
    EntropyError,
}

impl fmt::Display for CryptoError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CryptoError::AuthenticationFailed => {
                write!(f, "Cryptographic authentication failed: invalid tag or tampered data")
            }
            CryptoError::InvalidParameter(msg) => write!(f, "Invalid cryptographic parameter: {}", msg),
            CryptoError::InvalidFormat(msg) => write!(f, "Invalid encrypted data format: {}", msg),
            CryptoError::KdfError(msg) => write!(f, "Key derivation error: {}", msg),
            CryptoError::TamperingDetected(msg) => write!(f, "Cryptographic tampering detected: {}", msg),
            CryptoError::EntropyError => write!(f, "Operating system CSPRNG entropy failure"),
        }
    }
}

impl std::error::Error for CryptoError {}

pub type Result<T> = std::result::Result<T, CryptoError>;
