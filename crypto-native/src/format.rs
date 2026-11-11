//! format.rs — Versioned Encrypted Data Format
//! Standardized, tamper-proof, bounds-checked serialization for encrypted objects.

use crate::aes_gcm::{KEY_LEN, NONCE_LEN, TAG_LEN};
use crate::error::{CryptoError, Result};
use crate::kdf::{self, MIN_SALT_LEN};
use base64ct::{Base64, Encoding};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

pub const CURRENT_FORMAT_VERSION: u32 = 1;
pub const ALGORITHM_NAME: &str = "AES-256-GCM";
pub const KDF_NAME: &str = "argon2id";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EncryptedPacket {
    pub version: u32,
    pub algorithm: String,
    pub kdf: String,
    pub kdf_profile: String,
    pub kdf_memory_kib: u32,
    pub kdf_iterations: u32,
    pub kdf_parallelism: u32,
    pub salt: String,        // Base64
    pub wrapped_dek: String, // Base64 (32B DEK + 16B Tag = 48B)
    pub dek_nonce: String,   // Base64 (12B)
    pub data_nonce: String,  // Base64 (12B)
    pub ciphertext: String,  // Base64 (ciphertext || 16B Tag)
    pub metadata: HashMap<String, String>,
}

impl EncryptedPacket {
    /// Strictly validates all byte lengths and parameter bounds.
    pub fn validate(&self) -> Result<()> {
        if self.version != CURRENT_FORMAT_VERSION {
            return Err(CryptoError::InvalidFormat(format!(
                "Unsupported format version {}; expected {}",
                self.version, CURRENT_FORMAT_VERSION
            )));
        }

        if self.algorithm != ALGORITHM_NAME {
            return Err(CryptoError::InvalidFormat(format!(
                "Unsupported algorithm '{}'",
                self.algorithm
            )));
        }

        if self.kdf != KDF_NAME {
            return Err(CryptoError::InvalidFormat(format!(
                "Unsupported KDF '{}'",
                self.kdf
            )));
        }

        // Validate KDF parameter bounds (DoS defense)
        kdf::validate_kdf_params(
            self.kdf_memory_kib,
            self.kdf_iterations,
            self.kdf_parallelism,
        )?;

        // Validate Base64 lengths
        let salt = Base64::decode_vec(&self.salt)
            .map_err(|e| CryptoError::InvalidFormat(format!("Invalid salt base64: {}", e)))?;
        if salt.len() < MIN_SALT_LEN {
            return Err(CryptoError::InvalidFormat(format!(
                "Salt length {} is below minimum 16 bytes",
                salt.len()
            )));
        }

        let dek_nonce = Base64::decode_vec(&self.dek_nonce)
            .map_err(|e| CryptoError::InvalidFormat(format!("Invalid dek_nonce base64: {}", e)))?;
        if dek_nonce.len() != NONCE_LEN {
            return Err(CryptoError::InvalidFormat(format!(
                "DEK nonce must be 12 bytes, got {}",
                dek_nonce.len()
            )));
        }

        let data_nonce = Base64::decode_vec(&self.data_nonce)
            .map_err(|e| CryptoError::InvalidFormat(format!("Invalid data_nonce base64: {}", e)))?;
        if data_nonce.len() != NONCE_LEN {
            return Err(CryptoError::InvalidFormat(format!(
                "Data nonce must be 12 bytes, got {}",
                data_nonce.len()
            )));
        }

        let wrapped_dek = Base64::decode_vec(&self.wrapped_dek)
            .map_err(|e| CryptoError::InvalidFormat(format!("Invalid wrapped_dek base64: {}", e)))?;
        if wrapped_dek.len() != (KEY_LEN + TAG_LEN) {
            return Err(CryptoError::InvalidFormat(format!(
                "Wrapped DEK length must be 48 bytes (32B key + 16B tag), got {}",
                wrapped_dek.len()
            )));
        }

        let ct = Base64::decode_vec(&self.ciphertext)
            .map_err(|e| CryptoError::InvalidFormat(format!("Invalid ciphertext base64: {}", e)))?;
        if ct.len() < TAG_LEN {
            return Err(CryptoError::InvalidFormat(
                "Ciphertext payload shorter than 16-byte tag".into(),
            ));
        }

        Ok(())
    }

    pub fn to_json(&self) -> Result<String> {
        self.validate()?;
        serde_json::to_string(self)
            .map_err(|e| CryptoError::InvalidFormat(format!("Serialization error: {}", e)))
    }

    pub fn from_json(json_str: &str) -> Result<Self> {
        let packet: Self = serde_json::from_str(json_str)
            .map_err(|e| CryptoError::InvalidFormat(format!("Deserialization error: {}", e)))?;
        packet.validate()?;
        Ok(packet)
    }
}
