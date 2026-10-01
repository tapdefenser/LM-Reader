# v0.1.0 release preparation

[简体中文](RELEASING.zh-CN.md) · [Bilingual release notes](releases/v0.1.0.md)

Checked on 2026-10-01. The requested deliverable is an **unsigned candidate**, not a published/installable release.

## Candidate configuration

| Item | Value |
|---|---|
| Version / code | `0.1.0` / `2`, from `gradle/release.properties` |
| Intended tag | `v0.1.0`, not yet created |
| Package | `com.lmreader`; Debug: `com.lmreader.debug` |
| SDK | min 26, target 36, compile 37 |
| ABIs | arm64-v8a and x86_64 only |
| Optimization | R8 and resource shrinking enabled |
| Signing | No formal credentials supplied; unsigned candidate |
| Candidate / size | `LM-Reader-v0.1.0-64bit-unsigned.apk` / 195,982,080 bytes, about 186.9 MiB |
| Source | This APK was built from the then-uncommitted worktree; metadata records that HEAD/dirty state |

The local `.scratch/release-v0.1.0/` folder contains the named APK, notes, third-party texts, `release-metadata.json` and `SHA256SUMS`. Local outputs are Git-ignored. An unsigned APK cannot be installed or used as an official download asset.

This candidate's APK SHA-256 is `6b38d2d26a5352c48fd88ba8023cd9b31e16ee75d8580456e045ccb4030ae4df`. Regenerate `SHA256SUMS` after signing or rebuilding.

## Language policy

The shared AppLanguage policy supplies Activity, notifications, UI text and preference language labels. Manual Chinese/English takes priority. Follow system examines only the primary system language: Simplified Chinese, including zh-Hans/zh-CN/zh-SG and unspecified zh, uses Chinese; all others use English. Traditional zh-Hant/TW/HK/MO uses English. Explicit script wins over region, so zh-Hans-HK stays Chinese and zh-Hant-CN is English. Empty system language falls back to English.

Default Android resources are English; Chinese uses `values-b+zh+Hans`. The null Follow system preference is preserved. Titles, paths, user input and translation targets remain unchanged. Four JVM tests cover policy and three device tests cover real resource selection, the offline UI catalog and preferences using isolated configurations.

App Bundle language splitting is disabled so both UI languages remain available after a manual switch in bundle installs.

## Build and verify

Use JDK 21, Python 3.9+/PyYAML, SDK Platform 37.0, Build Tools 36.0.0, NDK 28.2.13676358 and CMake 3.22.1.

```sh
python -m pip install PyYAML
python tools/fetch_vision_models.py
python tools/fetch_translation_sources.py
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease test --console=plain
python tools/package_release.py --allow-unsigned
```

Packaging checks the actual APK package/version/SDK, CRC, signature status, 64-bit ABIs, both native engines, ELF LOAD alignment of at least 16 KB, ZIP alignment, six model hashes and UI JSON. It does not sign, commit, tag or upload. Without `--allow-unsigned`, unsigned APKs or dirty sources are rejected. Signing does not automatically satisfy license/device gates.

The read-only GitHub workflow builds/tests/lints and uploads reports, with no automatic Release/APK publication. All new modules/resources/tests/tools/source locks must be committed before CI can build them. Local success does not establish remote CI success.

## Formal signing later

Keep a reliable keystore/password backup. Consult Android's [signing documentation](https://developer.android.com/studio/publish/app-signing) for installation/update rules. The app reads four environment variables: `LMREADER_KEYSTORE_FILE`, `LMREADER_KEYSTORE_PASSWORD`, `LMREADER_KEY_ALIAS`, `LMREADER_KEY_PASSWORD`. Set all four or clear all four. Do not commit credentials or share them in chat/issues/logs.

Use `--no-configuration-cache` for signing builds to keep passwords out of configuration caches:

```sh
./gradlew --no-configuration-cache :app:assembleRelease :app:lintRelease
python tools/package_release.py --apk app/build/outputs/apk/release/app-release.apk
```

Verify the certificate with `apksigner`, then test install/same-certificate upgrades, shrunk JNI, all input formats, translation/edits, export/recovery and notifications. Debug/Release are separate packages; migrate through backups. Regenerate checksums for the actual signed APK.

## GitHub steps

1. Review/commit the complete worktree, including new modules, schemas, resources, tests and tools; validate a clean checkout.
2. Select the project's own LICENSE and complete dependency/Seg redistribution evidence. The Seg notice explicitly records the unresolved independent weight terms; the code's MIT declaration does not establish those terms.
3. Finish signing and Release install/upgrade/ARM validation. Update bilingual notes and checksums.
4. Tag the verified commit as v0.1.0 and push it. Create a draft/prerelease with signed APK, checksums, notices and source references; review before publishing.

Releases use tagged commits, per [GitHub documentation](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases). Use the web UI, or an installed/authenticated CLI after completing the steps above:

```sh
git tag -a v0.1.0 -m 'LM-Reader v0.1.0'
git push origin v0.1.0
gh release create v0.1.0 --verify-tag --draft --prerelease \
  --title 'LM-Reader v0.1.0' --notes-file docs/releases/v0.1.0.md \
  .scratch/release-v0.1.0/LM-Reader-v0.1.0-64bit.apk \
  .scratch/release-v0.1.0/SHA256SUMS
```

No remote Release/tag was created in this preparation. A dirty candidate must not use the old HEAD as its complete release source. Update the candidate-status note once official assets are ready.

## Evidence and remaining work

Local Debug/Release builds and both lint variants passed. All-module JVM tests recorded **407 passes, 1 skip and 0 failures**, counting each shared Debug/Release test set once. Earlier **17 MuMu Android 15 tests passed:** 3 language and 14 reliability tests. Language coverage includes Japanese, Korean, French, Arabic and Traditional Chinese English fallback, Simplified Chinese resources and manual priority.

Lint has no Fatal/Error findings, with 83 Debug and 79 Release warnings remaining. Packaging verified 35 Android native libraries and 7 Hexagon DSP files, six models and 40 checksum-listed files. Public documentation links were checked; the GitHub workflow passed static YAML parsing.

Logs: `.scratch/release-preparation-build.log`, `.scratch/release-preparation-final-build.log` and `.scratch/release-preparation-device-tests.log`. Candidate metadata records size/hash, ABIs and ELF alignment; the local summary is `.scratch/release-preparation-verification.json`.

Remaining: formal signing/install/upgrade, clean checkout/remote CI, project license/transitive notices/Seg redistribution evidence, long-running ARM lock-screen/power behavior, real SD/cloud providers, full disks and system-kill matrices. Additional export formats, arbitrary workflow checkpoints and cross-schema migration are outside the current first-release capability.

This revision adds About/GitHub/update checking, completed-task dequeueing and bilingual documentation. The new queue device tests compiled; device tests were not rerun after the emulator was closed. Current build logs are `.scratch/github-preparation-repair-build.log` and `.scratch/github-preparation-package-build.log`.
