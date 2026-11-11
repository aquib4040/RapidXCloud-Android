//! canondrive-crypto — High-Security, Military-Strength Cryptographic Engine
//!
//! Production-grade cryptographic core engineered in memory-safe Rust.
//! Designed for cross-platform deployment across Android (JNI), iOS (C-FFI),
//! Linux, macOS, Windows, and pure Rust applications.
//!
//! Architecture:
//! - Argon2id Password-Based Key Derivation (RFC 9106) with Unicode NFC & strict DoS limits
//! - AES-256-GCM Authenticated Encryption (NIST SP 800-38D) with fresh nonces & AAD position binding
//! - Master Secret Key Hierarchy (Proton/MEGA model) & Emergency Recovery Key
//! - Information-Theoretic XOR Dual-Key Split (FileKey = ServerShare ⊕ ClientShare)
//! - MTProto-aligned chunked AEAD streaming with anti-splicing & anti-truncation validation
//! - Memory-safe zeroization on drop for sensitive keys via `zeroize`

pub mod aes_gcm;
pub mod envelope;
pub mod error;
pub mod ffi;
pub mod format;
pub mod jni;
pub mod kdf;
pub mod mtproto;
pub mod split;
pub mod streaming;

// Top-level re-exports for pure Rust consumers
pub use aes_gcm::{decrypt, encrypt, generate_key, generate_nonce, KEY_LEN, NONCE_LEN, TAG_LEN};
pub use envelope::{
    derive_auth_key_hex, derive_enc_key, enroll_master, generate_master_secret,
    generate_recovery_code, normalize_recovery_code, recover_with_code, unlock_with_password,
    unwrap_master, wrap_master, KeyBag, WrappedSecret,
};
pub use error::{CryptoError, Result};
pub use format::{EncryptedPacket, CURRENT_FORMAT_VERSION};
pub use kdf::{
    derive_key, derive_key_profile, generate_salt, hkdf_expand, validate_kdf_params, KDFProfile,
    MIN_SALT_LEN,
};
pub use split::{combine_shares, split_key, xor_shares};
pub use streaming::{
    build_chunk_aad, decrypt_stream_chunk, encrypt_stream_chunk, StreamDecryptor,
    STREAM_CHUNK_OVERHEAD, STREAM_CHUNK_PLAINTEXT, STREAM_CHUNK_SEALED,
};

// ─────────────────────────────────────────────────────────────────────────────
// COMPREHENSIVE AUTOMATED SECURITY & UNIT TEST SUITE (Section 10 Compliance)
// ─────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use base64ct::Encoding;

    // ── 1. Argon2id Tests ──
    #[test]
    fn test_argon2id_derived_key_length() {
        let salt = kdf::generate_salt(16).unwrap();
        let key = kdf::derive_key_profile("SecurePassword2026!", &salt, KDFProfile::TestFast).unwrap();
        assert_eq!(key.len(), 32, "Argon2id output must be exactly 32 bytes (256 bits)");
    }

    #[test]
    fn test_argon2id_salt_generation_length() {
        let salt16 = kdf::generate_salt(16).unwrap();
        assert_eq!(salt16.len(), 16);
        let salt32 = kdf::generate_salt(32).unwrap();
        assert_eq!(salt32.len(), 32);
        assert!(kdf::generate_salt(15).is_err(), "Salts under 16 bytes must be rejected");
    }

    #[test]
    fn test_argon2id_unique_salts() {
        let mut salts = std::collections::HashSet::new();
        for _ in 0..100 {
            let s = kdf::generate_salt(16).unwrap();
            assert!(salts.insert(s), "Salts must be globally unique via CSPRNG");
        }
    }

    #[test]
    fn test_argon2id_determinism_and_differentiation() {
        let salt1 = kdf::generate_salt(16).unwrap();
        let salt2 = kdf::generate_salt(16).unwrap();
        let pw1 = "PasswordOne#2026";
        let pw2 = "PasswordTwo#2026";

        let k1a = kdf::derive_key_profile(pw1, &salt1, KDFProfile::TestFast).unwrap();
        let k1b = kdf::derive_key_profile(pw1, &salt1, KDFProfile::TestFast).unwrap();
        assert_eq!(k1a, k1b, "Same password and salt must be strictly deterministic");

        let k2 = kdf::derive_key_profile(pw2, &salt1, KDFProfile::TestFast).unwrap();
        assert_ne!(k1a, k2, "Different passwords must produce different derived keys");

        let k3 = kdf::derive_key_profile(pw1, &salt2, KDFProfile::TestFast).unwrap();
        assert_ne!(k1a, k3, "Different salts must produce different derived keys");
    }

    #[test]
    fn test_argon2id_unicode_nfc_normalization() {
        let salt = kdf::generate_salt(16).unwrap();
        let pw_nfc = "caf\u{00e9}"; // é composed
        let pw_nfd = "cafe\u{0301}"; // e + combining acute
        let k_nfc = kdf::derive_key_profile(pw_nfc, &salt, KDFProfile::TestFast).unwrap();
        let k_nfd = kdf::derive_key_profile(pw_nfd, &salt, KDFProfile::TestFast).unwrap();
        assert_eq!(k_nfc, k_nfd, "Unicode NFC normalization ensures cross-platform parity");
    }

    #[test]
    fn test_argon2id_parameter_limits_dos_defense() {
        let salt = kdf::generate_salt(16).unwrap();
        // Memory exceeds 1 GiB upper limit
        let res = kdf::derive_key("pw", &salt, 2_000_000, 3, 1, 32);
        assert!(res.is_err(), "Excessive memory cost must be rejected to prevent DoS");

        // Iterations exceed 20
        let res_iter = kdf::derive_key("pw", &salt, 65536, 25, 1, 32);
        assert!(res_iter.is_err(), "Excessive iterations must be rejected");

        // Parallelism exceeds 16
        let res_p = kdf::derive_key("pw", &salt, 65536, 3, 32, 32);
        assert!(res_p.is_err(), "Excessive parallelism must be rejected");
    }

    // ── 2. AES-256-GCM Tests ──
    #[test]
    fn test_aes_gcm_roundtrip() {
        let key = aes_gcm::generate_key().unwrap();
        let nonce = aes_gcm::generate_nonce().unwrap();
        let pt = b"Top secret classified user document payload.";
        let aad = b"tenant:123|record:456";

        let ct = aes_gcm::encrypt(&key, &nonce, pt, Some(aad)).unwrap();
        assert_eq!(ct.len(), pt.len() + TAG_LEN);

        let decrypted = aes_gcm::decrypt(&key, &nonce, &ct, Some(aad)).unwrap();
        assert_eq!(decrypted, pt);
    }

    #[test]
    fn test_aes_gcm_wrong_key_fails() {
        let key1 = aes_gcm::generate_key().unwrap();
        let key2 = aes_gcm::generate_key().unwrap();
        let nonce = aes_gcm::generate_nonce().unwrap();
        let pt = b"Sample payload";

        let ct = aes_gcm::encrypt(&key1, &nonce, pt, None).unwrap();
        let res = aes_gcm::decrypt(&key2, &nonce, &ct, None);
        assert_eq!(res, Err(CryptoError::AuthenticationFailed));
    }

    #[test]
    fn test_aes_gcm_ciphertext_tampering_fails() {
        let key = aes_gcm::generate_key().unwrap();
        let nonce = aes_gcm::generate_nonce().unwrap();
        let pt = b"Tamper resistant buffer";

        let mut ct = aes_gcm::encrypt(&key, &nonce, pt, None).unwrap();
        ct[2] ^= 0x01; // flip single bit in ciphertext
        let res = aes_gcm::decrypt(&key, &nonce, &ct, None);
        assert_eq!(res, Err(CryptoError::AuthenticationFailed));
    }

    #[test]
    fn test_aes_gcm_tag_tampering_fails() {
        let key = aes_gcm::generate_key().unwrap();
        let nonce = aes_gcm::generate_nonce().unwrap();
        let pt = b"Tag verification buffer";

        let mut ct = aes_gcm::encrypt(&key, &nonce, pt, None).unwrap();
        let last_idx = ct.len() - 1;
        ct[last_idx] ^= 0x01; // flip single bit in tag
        let res = aes_gcm::decrypt(&key, &nonce, &ct, None);
        assert_eq!(res, Err(CryptoError::AuthenticationFailed));
    }

    #[test]
    fn test_aes_gcm_nonce_tampering_fails() {
        let key = aes_gcm::generate_key().unwrap();
        let mut nonce = aes_gcm::generate_nonce().unwrap();
        let pt = b"Nonce verification buffer";

        let ct = aes_gcm::encrypt(&key, &nonce, pt, None).unwrap();
        nonce[0] ^= 0x01; // flip bit in nonce
        let res = aes_gcm::decrypt(&key, &nonce, &ct, None);
        assert_eq!(res, Err(CryptoError::AuthenticationFailed));
    }

    #[test]
    fn test_aes_gcm_aad_tampering_fails() {
        let key = aes_gcm::generate_key().unwrap();
        let nonce = aes_gcm::generate_nonce().unwrap();
        let pt = b"AAD binding buffer";
        let aad = b"context:original";

        let ct = aes_gcm::encrypt(&key, &nonce, pt, Some(aad)).unwrap();
        let res = aes_gcm::decrypt(&key, &nonce, &ct, Some(b"context:modified"));
        assert_eq!(res, Err(CryptoError::AuthenticationFailed));

        let res_no_aad = aes_gcm::decrypt(&key, &nonce, &ct, None);
        assert_eq!(res_no_aad, Err(CryptoError::AuthenticationFailed));
    }

    #[test]
    fn test_aes_gcm_empty_plaintext() {
        let key = aes_gcm::generate_key().unwrap();
        let nonce = aes_gcm::generate_nonce().unwrap();
        let pt = b"";

        let ct = aes_gcm::encrypt(&key, &nonce, pt, None).unwrap();
        assert_eq!(ct.len(), 16, "Empty plaintext ciphertext is exactly the 16-byte tag");

        let decrypted = aes_gcm::decrypt(&key, &nonce, &ct, None).unwrap();
        assert_eq!(decrypted, pt);
    }

    #[test]
    fn test_aes_gcm_nonce_uniqueness_across_operations() {
        let mut nonces = std::collections::HashSet::new();
        for _ in 0..1000 {
            let n = aes_gcm::generate_nonce().unwrap();
            assert!(nonces.insert(n), "Every nonce must be unique across operations");
        }
    }

    // ── 3. Envelope Encryption & Key Hierarchy Tests ──
    #[test]
    fn test_master_key_enrollment_and_login_unlock() {
        let pw = "UserSecretPassword2026!";
        let (enc_key, key_bag, recovery_code) = envelope::enroll_master(pw).unwrap();
        assert_eq!(enc_key.len(), 32);
        assert!(!recovery_code.is_empty());

        // Login unlock with password
        let (unlocked_enc_key, _m) = envelope::unlock_with_password(&key_bag, pw).unwrap();
        assert_eq!(unlocked_enc_key, enc_key, "Password unlock must recover identical encKey");

        // Wrong password fails
        let res_wrong_pw = envelope::unlock_with_password(&key_bag, "WrongPassword#1");
        assert!(res_wrong_pw.is_err(), "Wrong password must fail AES-GCM unwrap");
    }

    #[test]
    fn test_master_key_recovery_code_unlock_and_password_rewrap() {
        let original_pw = "OriginalPassword2026!";
        let (original_enc_key, key_bag, recovery_code) = envelope::enroll_master(original_pw).unwrap();

        // Recover using Recovery Code
        let new_pw = "NewPassword2026$";
        let (recovered_enc_key, new_wrapped_pw, _m) =
            envelope::recover_with_code(&key_bag, &recovery_code, new_pw).unwrap();
        assert_eq!(recovered_enc_key, original_enc_key, "Recovery code must restore identical encKey");

        // Validate that new password unlocks the new wrapped_pw
        let new_key_bag = envelope::KeyBag {
            wrapped_pw: new_wrapped_pw,
            wrapped_recovery: key_bag.wrapped_recovery,
        };

        let (unlocked_with_new, _) = envelope::unlock_with_password(&new_key_bag, new_pw).unwrap();
        assert_eq!(unlocked_with_new, original_enc_key, "New password unlocks original files");

        // Old password no longer works
        assert!(envelope::unlock_with_password(&new_key_bag, original_pw).is_err());
    }

    #[test]
    fn test_zero_knowledge_auth_key_separation() {
        let email = "user@rapidxcloud.in";
        let pw = "MySecretMasterPassword";
        let auth_key_hex = envelope::derive_auth_key_hex(email, pw).unwrap();
        assert!(auth_key_hex.ends_with("_Aa1!"));
        assert_eq!(auth_key_hex.len(), 64 + 5); // 32 bytes hex + suffix

        // Same credentials produce deterministic auth proof
        let auth_key_hex_repeat = envelope::derive_auth_key_hex(email, pw).unwrap();
        assert_eq!(auth_key_hex, auth_key_hex_repeat);

        // Different password produces different auth proof
        let auth_key_diff = envelope::derive_auth_key_hex(email, "DifferentPassword").unwrap();
        assert_ne!(auth_key_hex, auth_key_diff);
    }

    // ── 4. Dual-Key Split Tests ──
    #[test]
    fn test_dual_key_split_reconstruction() {
        let file_key = aes_gcm::generate_key().unwrap();
        let (server_share, client_share) = split::split_key(&file_key).unwrap();

        assert_eq!(server_share.len(), 32);
        assert_eq!(client_share.len(), 32);
        assert_ne!(server_share, file_key.to_vec(), "Server share alone reveals 0 key bits");
        assert_ne!(client_share, file_key.to_vec(), "Client share alone reveals 0 key bits");

        let recovered = split::combine_shares(&client_share, &server_share).unwrap();
        assert_eq!(recovered, file_key.to_vec(), "ClientShare XOR ServerShare reconstructs FileKey");
    }

    // ── 5. Streaming Chunk AEAD Tests ──
    #[test]
    fn test_streaming_chunk_roundtrip_and_position_binding() {
        let key = aes_gcm::generate_key().unwrap();
        let chunk0_pt = b"Chunk 0 of 3 data payload.";
        let chunk1_pt = b"Chunk 1 of 3 data payload.";
        let chunk2_pt = b"Chunk 2 of 3 data payload.";

        let chunk0_sealed = streaming::encrypt_stream_chunk(&key, 0, 3, chunk0_pt).unwrap();
        let chunk1_sealed = streaming::encrypt_stream_chunk(&key, 1, 3, chunk1_pt).unwrap();
        let chunk2_sealed = streaming::encrypt_stream_chunk(&key, 2, 3, chunk2_pt).unwrap();

        let mut decryptor = streaming::StreamDecryptor::new(&key, 3);
        assert_eq!(decryptor.process_chunk(&chunk0_sealed).unwrap(), chunk0_pt);
        assert_eq!(decryptor.process_chunk(&chunk1_sealed).unwrap(), chunk1_pt);
        assert_eq!(decryptor.process_chunk(&chunk2_sealed).unwrap(), chunk2_pt);
        assert!(decryptor.is_finished());
        assert!(decryptor.finalize().is_ok());
    }

    #[test]
    fn test_streaming_chunk_reordering_detected() {
        let key = aes_gcm::generate_key().unwrap();
        let _chunk0_sealed = streaming::encrypt_stream_chunk(&key, 0, 2, b"Chunk 0").unwrap();
        let chunk1_sealed = streaming::encrypt_stream_chunk(&key, 1, 2, b"Chunk 1").unwrap();

        // Feed chunk 1 before chunk 0 (reordering attack)
        let mut decryptor = streaming::StreamDecryptor::new(&key, 2);
        let res = decryptor.process_chunk(&chunk1_sealed);
        assert!(res.is_err(), "Reordered chunk must fail AAD position check");
    }

    #[test]
    fn test_streaming_truncation_detected() {
        let key = aes_gcm::generate_key().unwrap();
        let chunk0_sealed = streaming::encrypt_stream_chunk(&key, 0, 2, b"Chunk 0").unwrap();

        let mut decryptor = streaming::StreamDecryptor::new(&key, 2);
        let _ = decryptor.process_chunk(&chunk0_sealed).unwrap();
        // File ends prematurely without chunk 1
        assert!(decryptor.finalize().is_err(), "Premature stream termination must be detected");
    }

    // ── 6. Format Serialization Tests ──
    #[test]
    fn test_encrypted_packet_serialization_roundtrip() {
        let mut metadata = std::collections::HashMap::new();
        metadata.insert("filename".into(), "confidential.pdf".into());

        let packet = format::EncryptedPacket {
            version: 1,
            algorithm: "AES-256-GCM".into(),
            kdf: "argon2id".into(),
            kdf_profile: "production-256mb".into(),
            kdf_memory_kib: 262144,
            kdf_iterations: 3,
            kdf_parallelism: 4,
            salt: base64ct::Base64::encode_string(&[0x11u8; 16]),
            wrapped_dek: base64ct::Base64::encode_string(&[0x22u8; 48]),
            dek_nonce: base64ct::Base64::encode_string(&[0x33u8; 12]),
            data_nonce: base64ct::Base64::encode_string(&[0x44u8; 12]),
            ciphertext: base64ct::Base64::encode_string(&[0x55u8; 64]),
            metadata,
        };

        let json_str = packet.to_json().unwrap();
        let deserialized = format::EncryptedPacket::from_json(&json_str).unwrap();
        assert_eq!(deserialized.version, 1);
        assert_eq!(deserialized.algorithm, "AES-256-GCM");
    }

    #[test]
    fn test_encrypted_packet_rejects_tampered_lengths() {
        let packet_bad_salt = format::EncryptedPacket {
            version: 1,
            algorithm: "AES-256-GCM".into(),
            kdf: "argon2id".into(),
            kdf_profile: "production-256mb".into(),
            kdf_memory_kib: 262144,
            kdf_iterations: 3,
            kdf_parallelism: 4,
            salt: base64ct::Base64::encode_string(&[0x11u8; 8]), // 8 bytes < 16 min
            wrapped_dek: base64ct::Base64::encode_string(&[0x22u8; 48]),
            dek_nonce: base64ct::Base64::encode_string(&[0x33u8; 12]),
            data_nonce: base64ct::Base64::encode_string(&[0x44u8; 12]),
            ciphertext: base64ct::Base64::encode_string(&[0x55u8; 64]),
            metadata: std::collections::HashMap::new(),
        };
        assert!(packet_bad_salt.validate().is_err());
    }
}
