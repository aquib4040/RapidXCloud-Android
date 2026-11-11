<p align="center">
  <img src="art/logo.png" alt="RapidXCloud Logo" width="140" />
</p>

<h1 align="center">RapidXCloud Android</h1>

<p align="center">
  <strong>rapidxcloud.in open source android apk</strong><br>
  Official open-source Android client for <a href="https://rapidxcloud.in">rapidxcloud.in</a> — encrypted cloud storage vault with automated BYOS background backup and uncompressed direct folder downloads.
</p>

<p align="center">
  <a href="https://github.com/aquib4040/RapidXCloud-Android/actions"><img src="https://github.com/aquib4040/RapidXCloud-Android/actions/workflows/build-release.yml/badge.svg" alt="Build Status" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3" /></a>
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Platform-Android%2011%2B%20(API%2030%2B)-green.svg" alt="Android 11+" /></a>
  <a href="https://github.com/aquib4040/RapidXCloud-Android/releases"><img src="https://img.shields.io/badge/Release-APK%20Downloads-emerald.svg" alt="Releases" /></a>
</p>

---

## Features

### End-to-End Encryption (E2EE)
* **Native Rust Cryptography Engine**: Cryptographic encryption, decryption, Argon2id derivation, and Telegram MTProto TL framing are written in native Rust (`crypto-native/`) for blazing fast performance and ultra-optimized RAM usage (< 30 MB).
* **Argon2id Key Derivation**: Memory-hard parameters ($m=65536\text{ KB}, t=3, p=1$) defend against GPU brute-forcing.
* **Dual-Key Split**: Master file keys are split into shares ($\text{FileKey} = \text{ClientShare} \oplus \text{ServerShare}$).
* **Solo User Key Mode**: When enabled, zero key shares are sent to the backend.
* **4 MiB Streaming AES-256-GCM Chunks**: Large file transfers are encrypted and decrypted in 4 MiB chunks with Associated Authenticated Data (AAD) index binding, keeping memory footprint under 30 MB.
* **Pure-Rust MTProto Engine**: Uses pure-Rust Telegram MTProto TL schema types (`grammers`) with 512 KiB upload parts and dynamic endpoint dispatch.
* **32-Character Crockford Recovery Key**: Mnemonic recovery system allows vault restoration if credentials are forgotten.

### Automated Background Backup
* **Restricted to BYOS (Private Node) Accounts**: Available for users with connected personal bot tokens.
* **Real-Time Storage Monitoring**: Uses Android `ContentObserver` to detect new media writes (Camera, Screenshots, Downloads) and upload them promptly.
* **Mobile Directory Mirroring**: Preserves device folder structures (e.g., `DCIM/Camera` maps to `Mobile Backup/Camera`).
* **Hash Deduplication**: Tracks SHA-256 headers in a local Room database so existing files are never re-uploaded or overwritten.
* **Extension Filters**: Select which file extensions to back up (`jpg, png, mp4, mov, pdf, docx, zip, apk`).
* **Auto-Delete Local Copy**: Optional storage cleanup after successful backup is **disabled by default**.

### Direct Folder Download (No Zipping)
* Downloads full directory trees recursively into `Downloads/RapidXCloud/`, preserving tree structure without requiring server-side zip compression.

### Remote URL Leeching
* Triggers remote downloads on high-speed server lanes (up to 2 Gbps) directly into your cloud vault, with stream selection, custom container naming, and subtitle options.

### System Share Sheet
* Supports uploading files directly to your vault from WhatsApp, Telegram, Gallery, Chrome, or any Android app via standard Android share intents.

### In-App Auto-Updater
* **GitHub Releases Integration**: Check for updates directly inside Settings.
* **Architecture-Aware**: Automatically detects device CPU architecture (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, or universal) and downloads the matching build.
* **Background Download & Notification**: Live download progress bar in system notification tray and one-tap in-place installation.

---

## System Requirements & Compatibility

* **Minimum Android Version**: **Android 11 (API Level 30)**
* **Target Android Version**: **Android 14 (API Level 34)**
* **Supported Architectures**: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, Universal

### Why Android 11+ is Required
1. **Directory Mirroring (`MANAGE_EXTERNAL_STORAGE`)**: Android 11 introduced All Files Access, which is necessary for automated background photo and document backup across storage trees (`DCIM/Camera`, `Pictures`, `Documents`, `Download`) without repetitive manual permission prompts for every single folder.
2. **Hardware Cryptography & Modern TLS 1.3**: Zero-knowledge AES-256-GCM chunk streaming and Argon2id derivation require the modern Android Keystore hardware provider and TLS 1.3 socket suites natively supported in API 30+.
3. **High-Speed NIO Segmented Downloads**: The aria2-style parallel downloader relies on kernel-level `FileChannel` sparse file pre-allocation and non-blocking multi-connection chunk writes.
4. **Foreground Data Synchronization**: Reliable background synchronization without OS termination requires Android 11's `foregroundServiceType="dataSync"` scheduling.

---

## Bug Reports & Community Feedback

If you encounter bugs, glitches, or transfer errors:

1. Visit [**GitHub Issues**](https://github.com/aquib4040/RapidXCloud-Android/issues).
2. Submit an issue with:
   - Device model and Android version (Android 11+).
   - Steps to reproduce.
   - Relevant error logs.

---

## License

This project is licensed under the GNU General Public License v3.0 (GPL-3.0) — see [LICENSE](LICENSE) for details.
