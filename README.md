# RapidXCloud Android Client

[![Build & Release](https://github.com/RapidXCloud/RapidXCloud-Android/actions/workflows/build-release.yml/badge.svg)](https://github.com/RapidXCloud/RapidXCloud-Android/actions)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%2011%2B%20(API%2030%2B)-green.svg)](https://android.com)
[![Crypto: Zero-Knowledge](https://img.shields.io/badge/Cryptography-Argon2id%20%2B%20AES--256--GCM-cyan.svg)](#cryptographic-architecture)

> **Official open-source Android client for RapidXCloud** — a privacy-first, zero-knowledge encrypted cloud storage vault with automated Google Photos-style background backup, direct uncompressed folder downloads, and server-side URL leeching.

---

## 🔒 Cryptographic Architecture (Proton-Grade Zero-Knowledge)

RapidXCloud Android is engineered with a strict zero-knowledge security boundary. Neither the server nor any intermediary ever receives your master password, your encryption keys, or your plaintext file contents.

```
Master Password + Public Salt
             │
             ▼
   Argon2id (m=64MB, t=3, p=1)
             │
             ▼
      Master Secret (M)  ── [Stable 256-bit Key Hierarchy]
             │
    ┌────────┴────────┐
    ▼                 ▼
authKey            encKey (AES-256-GCM)
(Server proof)        │
                      ├─► Zero-Knowledge Metadata (Filename, MIME)
                      ├─► Thumbnail Encryption (AAD "0|1")
                      └─► Dual-Key Split (Seals ClientShare)
```

### 1. Dual-Key Split Parity
Every file uploaded via RapidXCloud generates an independent, ephemeral 256-bit `FileKey`.
- **Dual-Key Mode (Default):**
  $$\text{FileKey} = \text{ServerShare} \oplus \text{ClientShare}$$
  `ServerShare` is stored plainly on the server, while `ClientShare` is sealed with the user's `encKey`. **Neither share alone can decrypt the file.** Even in the event of a full server database leak, files remain cryptographically impenetrable without the user's master password.
- **Solo User Key Mode:**
  Users can toggle off Dual-Key Split in Settings. In this mode, `ServerShare` is strictly empty ($0$ bytes) and the backend receives **zero key shares**.

### 2. Low-RAM 4 MiB Streaming Chunk Encryption
Unlike standard mobile uploaders that load entire files into memory, RapidXCloud streams and encrypts files block-by-block in **4 MiB plaintext chunks**:
- **Format:** `[12-byte IV][Ciphertext][16-byte GCM Tag]`
- **AAD Binding:** UTF-8 byte string `${index}|${totalChunks}` ensures chunks cannot be reordered, truncated, or spliced by a malicious relay.
- **RAM Footprint:** Active memory consumption stays strictly **under 30 MB** even when processing 25 GB+ files.

### 3. Native Rust Crypto Engine (`canondrive-crypto`)
Core cryptographic primitives (Argon2id memory-hard hashing, HKDF-SHA256, and constant-time XOR operations) are implemented in Rust within `crypto-native/` to eliminate memory safety bugs (buffer overflows, use-after-free) and leverage hardware ARMv8 Crypto Extensions.

---

## ⚡ Core Features

- **Automated Mobile Backup (Google Photos Style):**
  - Exclusively for Private Node (**BYOS**) users to guarantee unlimited personal storage bandwidth.
  - Event-driven live write tracking via `MediaStore` and `ContentObserver`.
  - Recreates your exact phone directory hierarchy in the cloud (`Mobile Backup/Camera`, `Mobile Backup/Screenshots`, `Mobile Backup/WhatsApp`).
  - Automatic deduplication via SHA-256 content hashes in local Room SQLite — **never rewrites or re-uploads already existing files**.
  - Customizable file extensions (`.jpg, .png, .mp4, .mkv, .pdf, .docx, .zip`).
  - Auto-delete original from device after verified backup (strictly **OFF** by default).
- **Direct Folder Download (No Zipping):**
  - Download entire cloud folders recursively straight to `Downloads/CanonDrive/` without server zipping.
- **Remote URL Leeching:**
  - Transfer web files at up to 2 Gbps directly to your cloud drive. Supports audio/subtitle stream filtering, container renaming, and intro subtitles.
- **1:1 Dark Cyberpunk Interface:**
  - Modern, utilitarian design language built with Jetpack Compose matching the RapidXCloud web interface.
  - Offline metadata browsing and persistent DiskLRU cache for decrypted thumbnails.
- **Device Session Management:**
  - Telegram-style device manager: view active sessions, terminate individual devices, or terminate all other devices remotely.

---

## 🛠️ Building From Source

### Prerequisites
1. **JDK 17** (`openjdk-17-jdk`)
2. **Android SDK & NDK** (`ndkVersion = "26.1.10909125"` or higher)
3. **Rust Toolchain** with Android cross-compilation targets:
   ```bash
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
   cargo install cargo-ndk
   ```

### Compile Native Rust Crypto
```bash
cd crypto-native
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 -o ../app/src/main/jniLibs build --release
cd ..
```

### Build APKs
```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```
Compiled APKs will be located in `app/build/outputs/apk/release/`.

---

## 🚀 CI/CD Pipeline

A fully automated GitHub Actions workflow is provided in [`.github/workflows/build-release.yml`](.github/workflows/build-release.yml).
- **Trigger:** Manual trigger (`workflow_dispatch`) or tag push (`v*`).
- **Matrix Build:** Compiles native Rust `.so` libraries and builds optimized APKs for `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, and universal packages.
- **Verification:** Runs static analysis, linting, and SHA-256 checksum generation before creating a published GitHub Release.

---

## 🛡️ Security Vulnerability Reporting

If you discover a security vulnerability in this client, please submit a report privately via security advisory or email `security@rapidxcloud.in`. Please do not report security vulnerabilities through public GitHub issues.

---

## 📄 License

RapidXCloud Android is licensed under the **GNU General Public License v3.0** (GPL-3.0). See [LICENSE](LICENSE) for details.
