# Security Policy — RapidXCloud Android & Native Crypto

RapidXCloud Android is engineered on the principle of **Zero-Knowledge Client-Side Encryption**. Plaintext file contents and encryption keys remain exclusively in client device memory and are mathematically inaccessible to storage nodes or network relays.

---

## 1. Native Cryptographic Implementation

- **100% Pure Rust Core:** All key derivation, envelope encryption, and chunk AEAD sealing are executed in our memory-safe Rust native crate (`rapidxclouddrive-crypto`).
- **Memory Zeroization:** All derived secrets and sensitive key buffers implement `zeroize::ZeroizeOnDrop` (`SecureKey`), ensuring registers and RAM are overwritten with zeros immediately upon release.
- **Argon2id (RFC 9106):** Configured with 64 MiB to 256 MiB of memory, $t=3$ iterations, and Unicode NFC normalization.
- **AES-256-GCM (NIST SP 800-38D):** 256-bit symmetric keys with fresh 96-bit CSPRNG nonces per chunk and 128-bit authentication tags verified in constant time.
- **AAD Position Binding:** Chunk index and total chunks are authenticated into Additional Authenticated Data (`UTF-8(index|total)`), rejecting reordering, dropped chunks, or file splicing.
- **Information-Theoretic Key Splitting:** $FileKey = ClientShare \oplus ServerShare$.

---

## 2. Source Code & Public Auditability

The full Android application and native Rust cryptographic engine are published openly on GitHub:
- **Repository:** [github.com/aquib4040/RapidXCloud-Android](https://github.com/aquib4040/RapidXCloud-Android)
- **Native Rust Engine:** [crypto-native](https://github.com/aquib4040/RapidXCloud-Android/tree/main/crypto-native)
- **C-ABI Header:** [include/rapidxclouddrive.h](https://github.com/aquib4040/RapidXCloud-Android/blob/main/crypto-native/include/rapidxclouddrive.h)
- **Kotlin JNI Bridge:** [NativeCrypto.kt](https://github.com/aquib4040/RapidXCloud-Android/blob/main/app/src/main/java/in/rapidxcloud/drive/crypto/NativeCrypto.kt)

---

## 3. Reporting a Vulnerability

We welcome responsible security research and vulnerability reports:
- **Email:** [security@rapidxcloud.in](mailto:security@rapidxcloud.in)
- **Direct Telegram:** [@ExE_AQUIB](https://t.me/ExE_AQUIB)

We commit to acknowledging reports within **24 hours** and providing regular remediation status updates.
