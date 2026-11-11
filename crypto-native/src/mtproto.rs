//! mtproto.rs — Telegram MTProto Upload Framing & Session Utilities

use base64ct::{Base64, Encoding};
use grammers_tl_types as tl;
use sha1::{Digest as Sha1Digest, Sha1};
use std::sync::atomic::AtomicBool;
use tl::Serializable;

/// Global atomic cancellation flags mapped by transfer task slot (0..64)
pub static CANCELLATION_FLAGS: [AtomicBool; 64] = {
    #[allow(clippy::declare_interior_mutable_const)]
    const INIT: AtomicBool = AtomicBool::new(false);
    [INIT; 64]
};

/// MTProto Maximum Allowed Part Size: 512 KiB (524,288 bytes)
pub const MTPROTO_UPLOAD_CHUNK_BYTES: usize = 512 * 1024;

/// 10 MB threshold in bytes
pub const TEN_MEGABYTES: i64 = 10 * 1024 * 1024;

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

pub fn compute_auth_key_id(auth_key: &[u8]) -> i64 {
    let mut hasher = Sha1::new();
    hasher.update(auth_key);
    let result = hasher.finalize();
    let mut bytes = [0u8; 8];
    bytes.copy_from_slice(&result[12..20]);
    i64::from_le_bytes(bytes)
}

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
        let ip = format!("{}.{}.{}.{}", decoded[1], decoded[2], decoded[3], decoded[4]);
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
