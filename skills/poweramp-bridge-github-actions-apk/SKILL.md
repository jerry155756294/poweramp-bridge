---
name: poweramp-bridge-github-actions-apk
description: Build, inspect, download, and use the `poweramp-bridge` GitHub Actions debug APK artifact as the primary build output for this repository. Use when a task involves CI verification, APK generation, artifact retrieval, reinstalling the latest build, or avoiding local Gradle as the source of truth.
---

# Poweramp Bridge GitHub Actions APK

Use GitHub Actions as the authoritative build path for this repository when the user asks to compile, rebuild, produce an APK, or verify a device install from CI output.

## Workflow

1. Confirm the current branch and whether the request is asking for a fresh CI build or an existing artifact.
2. Prefer the workflow at `.github/workflows/android-ci.yml` and the debug artifact named `poweramp-bridge-debug-apk`.
3. Treat the downloaded APK as the build deliverable. Do not claim success from local `assembleDebug` alone when the user asked to use GitHub Actions.
4. After download, verify the APK version before install if there is any chance the artifact is stale.

## Repository-Specific Rules

- Treat GitHub Actions as the official delivery path when the user explicitly asks for CI builds.
- Keep local Gradle useful for code inspection or narrow checks, but do not present it as the release gate when CI was requested.
- Save downloaded artifacts under a local scratch directory such as `_ci_apk/` and avoid staging that directory unless the user explicitly wants those files committed.
- If multiple runs exist, prefer the latest successful run for the current branch and state the run id you used.

## CI Build Checklist

1. Verify the target branch.
2. Trigger or inspect the Android CI workflow.
3. Wait for success before describing the APK as current.
4. Download the artifact and record the run id locally.
5. Extract the APK to a deterministic path such as `_ci_apk/run_<run-id>/app-debug.apk`.
6. If the next step is device validation, hand off to the A50 adb validation workflow.

## Common Pitfalls

- Do not confuse a previous successful run with the latest branch state.
- Do not mix CI APKs with old local APKs in the same folder without clear naming.
- Do not promise that local unit tests are healthy just because CI produced an APK; this repo has had separate local test runner issues.
- Do not accidentally commit `_ci_apk/` artifacts or other local evidence files.

## Suggested Evidence To Report

- branch name
- workflow file used
- GitHub Actions run id
- artifact name
- extracted APK path
- app version name and version code if verified after install

## Hand-off

If the task continues onto a device:

1. install the CI APK with adb
2. verify package version on-device
3. capture logcat and runtime evidence
4. separate CI/build failures from runtime/bridge failures
