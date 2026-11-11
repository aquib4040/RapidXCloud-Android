//! split.rs — Dual-Key Split (Defense-in-Depth Zero-Knowledge Key Separation)
//! Information-Theoretic One-Time Pad: FileKey = ServerShare ⊕ ClientShare

use crate::error::{CryptoError, Result};

pub const SHARE_LEN: usize = 32;

/// Constant-time bitwise XOR of two key shares.
pub fn xor_shares(a: &[u8], b: &[u8]) -> Result<Vec<u8>> {
    if a.len() != b.len() {
        return Err(CryptoError::InvalidParameter("Share length mismatch for XOR"));
    }
    let mut out = vec![0u8; a.len()];
    for i in 0..a.len() {
        out[i] = a[i] ^ b[i];
    }
    Ok(out)
}

/// Splits a 32-byte raw FileKey into ServerShare (random) and ClientShare (FileKey ⊕ ServerShare).
pub fn split_key(file_key: &[u8]) -> Result<(Vec<u8>, Vec<u8>)> {
    if file_key.len() != SHARE_LEN {
        return Err(CryptoError::InvalidParameter("File key must be 32 bytes"));
    }
    let mut server_share = vec![0u8; SHARE_LEN];
    getrandom::getrandom(&mut server_share).map_err(|_| CryptoError::EntropyError)?;

    let client_share = xor_shares(file_key, &server_share)?;
    // ServerShare reveals zero information about FileKey
    Ok((server_share, client_share))
}

/// Recombines ClientShare and ServerShare to recover original FileKey.
pub fn combine_shares(client_share: &[u8], server_share: &[u8]) -> Result<Vec<u8>> {
    xor_shares(client_share, server_share)
}
