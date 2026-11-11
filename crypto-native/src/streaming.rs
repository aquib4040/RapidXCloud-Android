//! streaming.rs — Large File Chunked AEAD Streaming Engine
//! MTProto-aligned, constant-memory chunked streaming with anti-splicing & anti-truncation AAD.

use crate::aes_gcm::{self, NONCE_LEN, TAG_LEN};
use crate::error::{CryptoError, Result};

pub const STREAM_CHUNK_OVERHEAD: usize = NONCE_LEN + TAG_LEN; // 28 bytes
pub const STREAM_CHUNK_SEALED: usize = 1024 * 1024; // 1 MiB (MTProto transport part)
pub const STREAM_CHUNK_PLAINTEXT: usize = STREAM_CHUNK_SEALED - STREAM_CHUNK_OVERHEAD; // 1,048,548 bytes

/// Builds deterministic chunk AAD: binds (chunk_index, total_chunks) as UTF-8 bytes.
/// Matches WebCrypto chunkAAD: "index|total"
pub fn build_chunk_aad(chunk_index: usize, total_chunks: usize) -> Vec<u8> {
    format!("{}|{}", chunk_index, total_chunks).into_bytes()
}

/// Extended AAD binding file_id and finalization flag
pub fn build_strict_chunk_aad(
    file_id: &str,
    chunk_index: usize,
    total_chunks: usize,
    is_final: bool,
) -> Vec<u8> {
    let flag = if is_final { "1" } else { "0" };
    format!("{}:{}:{}:{}", file_id, chunk_index, total_chunks, flag).into_bytes()
}

/// Encrypts one plaintext chunk into: [12-byte IV][Ciphertext][16-byte Tag]
pub fn encrypt_stream_chunk(
    key: &[u8],
    chunk_index: usize,
    total_chunks: usize,
    plaintext: &[u8],
) -> Result<Vec<u8>> {
    let nonce = aes_gcm::generate_nonce()?;
    let aad = build_chunk_aad(chunk_index, total_chunks);
    let ciphertext = aes_gcm::encrypt(key, &nonce, plaintext, Some(&aad))?;

    let mut sealed = Vec::with_capacity(NONCE_LEN + ciphertext.len());
    sealed.extend_from_slice(&nonce);
    sealed.extend_from_slice(&ciphertext);
    Ok(sealed)
}

/// Decrypts one [12-byte IV][Ciphertext][16-byte Tag] chunk.
/// Fails if chunk position, content, or tag was manipulated.
pub fn decrypt_stream_chunk(
    key: &[u8],
    chunk_index: usize,
    total_chunks: usize,
    sealed_chunk: &[u8],
) -> Result<Vec<u8>> {
    if sealed_chunk.len() < STREAM_CHUNK_OVERHEAD {
        return Err(CryptoError::InvalidFormat(
            "Sealed chunk shorter than nonce + tag overhead".into(),
        ));
    }

    let nonce = &sealed_chunk[..NONCE_LEN];
    let ciphertext = &sealed_chunk[NONCE_LEN..];
    let aad = build_chunk_aad(chunk_index, total_chunks);

    aes_gcm::decrypt(key, nonce, ciphertext, Some(&aad)).map_err(|_| {
        CryptoError::TamperingDetected(format!(
            "Chunk {} of {} failed GCM authentication check",
            chunk_index, total_chunks
        ))
    })
}

/// Stateful stream validator that processes chunks in order and verifies completion.
pub struct StreamDecryptor<'a> {
    key: &'a [u8],
    total_chunks: usize,
    expected_index: usize,
    completed: bool,
}

impl<'a> StreamDecryptor<'a> {
    pub fn new(key: &'a [u8], total_chunks: usize) -> Self {
        Self {
            key,
            total_chunks,
            expected_index: 0,
            completed: false,
        }
    }

    pub fn process_chunk(&mut self, sealed_chunk: &[u8]) -> Result<Vec<u8>> {
        if self.completed {
            return Err(CryptoError::TamperingDetected(
                "Extraneous chunk received after stream finalization".into(),
            ));
        }
        if self.expected_index >= self.total_chunks {
            return Err(CryptoError::TamperingDetected(
                "Stream chunk index overflow".into(),
            ));
        }

        let plaintext = decrypt_stream_chunk(
            self.key,
            self.expected_index,
            self.total_chunks,
            sealed_chunk,
        )?;

        self.expected_index += 1;
        if self.expected_index == self.total_chunks {
            self.completed = true;
        }

        Ok(plaintext)
    }

    pub fn is_finished(&self) -> bool {
        self.completed && self.expected_index == self.total_chunks
    }

    pub fn finalize(&self) -> Result<()> {
        if !self.completed || self.expected_index != self.total_chunks {
            return Err(CryptoError::TamperingDetected(format!(
                "Stream truncated: processed only {} of {} chunks",
                self.expected_index, self.total_chunks
            )));
        }
        Ok(())
    }
}
