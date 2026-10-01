# v0.1.0 release and signing

[简体中文](RELEASING.zh-CN.md) · [Bilingual release notes](releases/v0.1.0.md)

Release date: 2026-10-01. The first release provides a signed APK on [GitHub Releases](https://github.com/tapdefenser/LM-Reader/releases/tag/v0.1.0). The repository is currently private; downloads require repository access.

## Release assets

| Item | Configuration |
|---|---|
| Version / code | `0.1.0` / `2`, from `gradle/release.properties` |
| Tag | `v0.1.0` |
| Package | `com.lmreader`; Debug uses `com.lmreader.debug` |
| Android | min 26, target 36, compile 37 |
| ABIs | `arm64-v8a`, `x86_64` |
| Optimization | R8 and resource shrinking |
| Signing | Dedicated RSA-4096 key; APK v2/v3 verification passed |
| APK | `LM-Reader-v0.1.0-64bit.apk`, 196,008,539 bytes, about 186.9 MiB |

APK SHA-256: `483f490b50115dbcb5133f533246d7e4c29a19a4e9c30098168998b659770b77`.

Signer certificate SHA-256: `8b1051b89d4e4bf8c423e4f7a2ad19e9ef1cfce3cd19041997a75b838b65ecc7`. Future releases must reuse this key for normal upgrades.

Assets include the APK, `SHA256SUMS`, `release-metadata.json`, `THIRD_PARTY_NOTICES.md` and `LICENSES.zip`. Extract the notices archive before checking document/license paths in the checksum list. Metadata records signing, certificate fingerprint, source revision, ABIs, Android library alignment and separate Hexagon DSP files.

## Build and package

Use JDK 21, Python 3.9+/PyYAML, SDK Platform 37.0, Build Tools 36.0.0, NDK 28.2.13676358 and CMake 3.22.1.

```sh
python -m pip install PyYAML
python tools/fetch_vision_models.py
python tools/fetch_translation_sources.py
./gradlew :app:assembleDebug :app:lintDebug test --no-parallel --max-workers=2
./gradlew :app:assembleRelease :app:lintRelease --no-parallel --max-workers=2
```

On Windows use `gradlew.bat`. If packaging runs out of memory, increase the Java heap with `-Dorg.gradle.jvmargs=-Xmx6g`; reducing parallel work lowers memory usage.

Signing reads four local environment variables: `LMREADER_KEYSTORE_FILE`, `LMREADER_KEYSTORE_PASSWORD`, `LMREADER_KEY_ALIAS`, `LMREADER_KEY_PASSWORD`. Configure all or none. Without them, builds only produce an unsigned candidate.

```sh
./gradlew --no-configuration-cache :app:assembleRelease :app:lintRelease --no-parallel --max-workers=2
python tools/package_release.py --apk app/build/outputs/apk/release/app-release.apk
```

Packaging verifies package/version/SDK, CRC, signature, ABIs, Android ELF/ZIP 16 KB alignment, six model hashes and UI JSON. Hexagon DSP skeletons are recorded separately from Android ARM64 libraries. Unsigned or dirty candidates require `--allow-unsigned` for local preparation.

## Keys and upgrades

The release key is stored outside the repository. Its Windows DPAPI-encrypted password can only be decrypted by the corresponding Windows account. Securely back up the key and password separately before reinstalling Windows or moving computers. Never upload private keys, passwords or signing configuration caches. Signing builds disable configuration caching. See Android’s [signing documentation](https://developer.android.com/studio/publish/app-signing).

Debug/Release use separate packages; migrate with app backups. The first signature is verified; device installation and future same-certificate upgrades still require validation.

## Verification and limits

Debug/Release builds passed; **407 JVM tests passed, 1 skipped, 0 failed**. Lint has no Fatal/Error findings, with 83 Debug and 79 Release warnings. The signed APK passes v2/v3 and 16 KB ZIP checks, LOAD alignment for 35 Android libraries, classification of 7 DSP files and six model hashes.

Earlier, 17 MuMu Android 15 tests passed: 3 language and 14 reliability tests. New completed-task queue tests compiled; device tests were not rerun after the emulator was closed. Follow system selects Chinese for Simplified Chinese primary locales and English otherwise; manual language takes precedence.

Device installation/upgrades, ARM long-running background/power behavior and real SD/cloud providers remain to be validated. Project license selection, independent Seg redistribution evidence and complete dependency review remain unfinished; retained notices do not imply those reviews are complete. Repository visibility has not changed.
