# Security Policy — RapidXCloud Android, Web & Shared Rust Cryptography Core

RapidXCloud is engineered on the principle of **Zero-Knowledge Client-Side Encryption (E2EE)**. Plaintext file contents, master keys, and file encryption keys remain exclusively in client device memory and are mathematically inaccessible to the backend, storage nodes, or network relays.

---

## 1. Authoritative Shared Rust Cryptography Core (`crypto/core`)

All cryptographic operations across Android (JNI) and Web (WebAssembly) are powered by a single authoritative, audited pure Rust core (`rapidxcloud-crypto-core`):

- **Pure Rust Core (`crypto/core`):** Memory-safe, zero-allocation/bounded-memory cryptographic primitives.
- **Android Native Integration (`crypto/android` / `crypto-native`):** Direct JNI bindings exposing chunk streaming (`encryptStreamChunk`, `decryptStreamChunk`), Argon2id KDF, dual-key splitting, and MTProto part framing.
- **Browser WebAssembly Integration (`crypto/wasm`):** Pure WebAssembly compilation (`wasm32-unknown-unknown`) eliminating application-level `SubtleCrypto` and `hash-wasm` dependencies while using `crypto.getRandomValues()` for entropy.
- **Guaranteed Memory Zeroization:** All sensitive buffers wrap `zeroize::ZeroizeOnDrop` (`SecureKey<N>`, `SecureBytes`), ensuring keys, nonces, and intermediate secrets are overwritten with zeros immediately when leaving scope.

---

## 2. Cryptographic Specifications & Protocol Integrity

- **Argon2id Key Derivation (RFC 9106):**
  - Interactive profile: $m = 65536\text{ KiB}$ (64 MiB), $t = 3$ iterations, $p = 1$ parallelism.
  - Unicode NFC normalization enforced across all platforms before hashing.
  - Strict DoS boundary validation on all memory and iteration parameters.
- **AES-256-GCM (NIST SP 800-38D):**
  - 256-bit symmetric keys with fresh 96-bit CSPRNG nonces per chunk.
  - Self-describing chunk layout: `[ 12-byte IV ][ ciphertext ][ 16-byte Tag ]`.
  - Constant-time 128-bit authentication tag verification.
  - Protocol V1 Associated Authenticated Data (AAD): `UTF-8("${chunk_index}|${total_chunks}")` binding preventing chunk reordering, dropped chunks, or inter-file splicing.
- **Information-Theoretic Key Splitting:**
  - $\text{FileKey} = \text{ClientShare} \oplus \text{ServerShare}$.
  - ClientShare is encrypted under user's master file encryption key (`encKey`). Server possesses only a random one-time pad share that reveals zero information about the file key.
- **Telegram MTProto Utilities:**
  - Dynamic `RwLock<HashMap<i32, bool>>` cancellation registry, completely eliminating modulo-64 slot collisions.
  - Telethon session parser supporting both URL-safe (padded and unpadded) and standard Base64 with instant zeroization of intermediate decoded auth keys.
- **Recovery Key Envelope:**
  - 160-bit entropy Crockford Base32 recovery code.
  - Normalization translates ambiguous characters (`O/o -> 0`, `I/i/L/l -> 1`) and ignores spaces/dashes.

---

## 3. Audit Verification & Remediation Matrix

The codebase has undergone comprehensive remediation against the 35 Gemini 3.8 audit findings. All findings have been verified, remediated, and protected with regression tests in `crypto/core` and `crypto/android`:
- 0 critical vulnerabilities remaining.
- 0 high vulnerabilities remaining.
- 100% test pass rate across native Rust test suite, Android JNI, and WebAssembly integration tests.

---

## 4. Reporting a Vulnerability

We welcome responsible security research and vulnerability reports:
- **Email:** [security@rapidxcloud.in](mailto:security@rapidxcloud.in)
- **Direct Telegram:** [@ExE_AQUIB](https://t.me/ExE_AQUIB)

We commit to acknowledging reports within **24 hours** and providing regular remediation status updates.
