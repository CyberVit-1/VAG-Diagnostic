# VAG Diagnostic — GitHub bootstrap

Version: **0.1.0 (source archive, not yet compiled as Android APK)**.  
Date: **2026-10-21**.

## Current status

- This repository initially had only a short README on `main`.
- A GitHub Actions workflow for Android SDK 35, JDK 17, Gradle 8.11.1 and debug APK artifacts was added on branch `feature/initial-android-build`.
- **The Android source archive is not yet committed to this repository.** Therefore the workflow's source check is expected to fail until the contents are imported.
- The source archive `VagDiagnostic_v0.1.0_SOURCE.zip` was previously generated in ChatGPT, containing `VagDiagnostic_0.1.0/` (19 project files).

## Required bootstrap

Import the **contents** of `VagDiagnostic_0.1.0/` into the repository root (not the directory itself):

- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle.properties`
- `app/` (including Kotlin sources and `app/build.gradle.kts`)
- `tools/`
- `PROJECT_CONTEXT.md`, `RESEARCH.md`, `BUILDING.md`
- `.gitignore`

The upload may be performed as a pull request commit or a push to the feature branch. Avoid importing over an existing more recent implementation.

## Android build

Go to **Actions → Android APK (debug)**, select `feature/initial-android-build`, and run the workflow (or push source changes). APK appears under the successful run's **Artifacts**, named `VAG-Diagnostic-v0.1.0-debug-apk`.

## Diagnostic scope

The existing source archive implements read-only OBD-II DTC modes 03/07/0A and draft transports for ELM327 Wi-Fi TCP, Bluetooth SPP and BLE/NUS. VAG gateway Auto-Scan is **not implemented**. Android build and physical vehicle tests **have not been confirmed**.

**User action needed now:** none for the workflow itself; source files must be imported before any APK can be produced.

**Next engineering task:** import project, run workflow, resolve compile errors, then test on a physical ELM327 without assuming multi-ECU VAG support.
