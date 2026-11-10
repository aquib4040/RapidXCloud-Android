# RapidXCloud Android

**rapidxcloud.in open source android apk**

Official open-source Android client for [rapidxcloud.in](https://rapidxcloud.in) — privacy-first, zero-knowledge encrypted cloud vault with automated BYOS background backup and uncompressed direct folder downloads.

[![Build & Release](https://github.com/aquib4040/RapidXCloud-Android/actions/workflows/build-release.yml/badge.svg)](https://github.com/aquib4040/RapidXCloud-Android/actions)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2011%2B%20(API%2030%2B)-green.svg)](https://android.com)

---

## ⚡ Core Features

- **rapidxcloud.in Open Source Client**: 100% client-side code; no hardcoded backend credentials or secrets. Configurable custom server URL.
- **Zero-Knowledge E2EE**:
  - Memory-hard **Argon2id** master key derivation ($m=64\text{ MB}, t=3, p=1$).
  - **Dual-Key Split** ($\text{FileKey} = \text{ClientShare} \oplus \text{ServerShare}$) and **Solo User Key Mode** (zero key shares sent to backend).
  - 4 MiB streaming AES-256-GCM chunk encryption ($< 30\text{ MB}$ RAM footprint).
- **Automated Mobile Backup (Google Photos Style)**:
  - Exclusive for Private Node (**BYOS**) users with Telegram Bot tokens.
  - Automatically mirrors device directories (e.g. `DCIM/Camera` $\rightarrow$ `Mobile Backup/Camera`).
  - SHA-256 content deduplication — **never re-uploads or rewrites existing files**.
  - Customizable extensions (`jpg, png, mp4, pdf, docx, zip`). Auto-delete original is strictly **OFF by default**.
- **Direct Folder Download (No Zipping)**:
  - Downloads complete cloud folder trees directly into `Downloads/CanonDrive/` without server-side zip compression.
- **Remote URL Leech**:
  - Cloud-side leeching at up to 2 Gbps with stream selection and intro subtitle customization.
- **Native System Share Sheet**:
  - Share files from any Android app (WhatsApp, Gallery, Telegram, Chrome) directly to your encrypted vault.

---

## 🛠️ Building From Source

```bash
# 1. Compile native Rust crypto for all architectures
cd crypto-native
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 -o ../app/src/main/jniLibs build --release
cd ..

# 2. Build Release APK
chmod +x gradlew
./gradlew assembleRelease
```
Compiled APKs will be in `app/build/outputs/apk/release/`.

---

## 🐛 Bug Reports & Issues

We actively welcome and encourage community bug reports, feedback, and feature suggestions!

Please report all bugs directly via [**GitHub Issues**](https://github.com/aquib4040/RapidXCloud-Android/issues). Include your device model, Android OS version, and log steps to reproduce.

---

## 📄 License

GPL-3.0 License. See [LICENSE](LICENSE) for details.
