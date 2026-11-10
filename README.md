<p align="center">
  <img src="art/logo.png" alt="RapidXCloud Logo" width="140" />
</p>

<h1 align="center">RapidXCloud Android</h1>

<p align="center">
  <strong>rapidxcloud.in open source android apk</strong><br>
  Official open-source Android client for <a href="https://rapidxcloud.in">rapidxcloud.in</a> — privacy-first, zero-knowledge encrypted cloud vault with automated BYOS background backup and uncompressed direct folder downloads.
</p>

<p align="center">
  <a href="https://github.com/aquib4040/RapidXCloud-Android/actions"><img src="https://github.com/aquib4040/RapidXCloud-Android/actions/workflows/build-release.yml/badge.svg" alt="Build Status" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3" /></a>
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Platform-Android%2011%2B%20(API%2030%2B)-green.svg" alt="Android 11+" /></a>
  <a href="https://github.com/aquib4040/RapidXCloud-Android/releases"><img src="https://img.shields.io/badge/Release-APK%20Downloads-emerald.svg" alt="Releases" /></a>
</p>

---

## 📖 Open Source Philosophy (Telegram-Style Model)

RapidXCloud Android follows the **Telegram open-source model**:
* **Client Frontend & API Contracts**: 100% public and open-source under the GPL-3.0 license. Anyone can inspect, audit, verify the cryptographic implementations, and trace every network request sent to the server.
* **Zero Secrets in Client**: The client codebase contains zero backend secrets, database connection strings, bot tokens, or private signing keys.
* **Managed Backend**: The distributed cloud storage clusters, databases, metadata engines, and remote worker pipelines running at [rapidxcloud.in](https://rapidxcloud.in) are proprietary and closed-source.

This model guarantees end-to-end transparency and verifiable zero-knowledge privacy while ensuring high availability, scale, and enterprise infrastructure management.

---

## ⚡ Core Features

### 🛡️ Military-Grade Zero-Knowledge Cryptography (E2EE)
* **Hardware-Accelerated Native Rust Engine**: Key derivation and cryptographic primitives run in native Rust via JNI (`crypto-native`) with buffer zeroization.
* **Argon2id Master Key Derivation**: Memory-hard parameters ($m=64\text{ MB}, t=3, p=1$) defend against GPU/ASIC brute-force attacks.
* **Dual-Key Split Architecture**: Master encryption keys are split into shares ($\text{FileKey} = \text{ClientShare} \oplus \text{ServerShare}$).
* **Solo User Key Mode**: When enabled, zero key shares ever leave your device or reach the backend cluster.
* **4 MiB Streaming AES-256-GCM Chunks**: Large file transfers are processed in 4 MiB authenticated chunks with Associated Authenticated Data (AAD) index binding, maintaining a steady RAM footprint of under $30\text{ MB}$.
* **32-Character Crockford Emergency Recovery**: Mnemonic recovery system allowing vault restoration without server assistance.

### 📸 Automated Background Backup (Google Photos Parity)
* **Exclusive to BYOS (Private Node) Users**: Available for accounts with Private Node / Telegram bot token integration.
* **Real-Time Write Detection**: Native `ContentObserver` monitors storage writes (e.g. `DCIM/Camera`, `Pictures/Screenshots`, `Download`) and triggers uploads instantly.
* **Direct Folder Hierarchy Mirroring**: Preserves device folder structures on cloud storage without flat file scattering.
* **Intelligent Deduplication**: Room SQLite database tracks SHA-256 headers and metadata so already backed-up files are never rewritten or re-uploaded.
* **Custom File Extension Filtering**: Granular filter selection (`jpg, png, mp4, mov, pdf, docx, zip, apk`).
* **Safe-by-Design Auto-Delete**: The option to free up local phone storage after successful backup is **strictly disabled by default** to prevent accidental data loss.

### 📁 Direct Recursive Folder Download (No Zipping)
* In web browsers, downloading folder trees requires server-side zip compression.
* RapidXCloud Android recursively queries folder metadata and downloads the entire directory hierarchy directly into `Downloads/CanonDrive/`, preserving tree structure without zipping or unzipping overhead.

### 🚀 Remote URL Leeching (Up to 2 Gbps)
* Offload remote downloads directly to high-speed cloud lanes with stream selection, custom container naming, and intro subtitle customization.

### 🖼️ RapidxPics Photo Gallery
* Native photo and video grid with hardware-accelerated previews, zero-knowledge thumbnail decryption, full-resolution lightbox viewer, and instant public embed code generation.

### 🔄 System Share Sheet Integration
* Seamlessly upload files, media, and documents directly to your encrypted vault from WhatsApp, Telegram, Gallery, Chrome, or any Android app via standard Android send intents.

---

## 🐛 Bug Reports & Community Feedback

We actively welcome and encourage community bug reports! If you encounter any bugs, unexpected behavior, UI glitches, or transfer errors:

1. Visit our [**GitHub Issues**](https://github.com/aquib4040/RapidXCloud-Android/issues) tracker.
2. Open a new issue with:
   - Your device model and Android version (Android 11+ / API 30+).
   - Clear steps to reproduce the issue.
   - Any relevant error messages or logs.

Our team actively tracks and resolves issues reported by the community.

---

## 🔒 Security & Privacy Audits

Because the client is open-source, security researchers and developers can independently verify:
- No unencrypted file data or solo master keys ever leave the device.
- All network interactions strictly communicate with authenticated HTTPS REST endpoints.
- All credentials in memory are cleared on logout or session timeout.

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)** — see the [LICENSE](LICENSE) file for details.
