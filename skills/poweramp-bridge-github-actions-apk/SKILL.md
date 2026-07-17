---
name: poweramp-bridge-github-actions-apk
description: Inspect, trigger, download, and report the current Poweramp Bridge GitHub Actions artifacts without treating a local build as CI validation.
---

# Poweramp Bridge GitHub Actions artifacts

Use this skill when the user asks for CI verification, an installable APK, a build artifact, or the current run status.

## Workflows and artifacts

- `.github/workflows/android-ci.yml` compiles and runs unit tests.
- A non-pull-request CI run uploads signed `poweramp-bridge-release-apk` and `poweramp-bridge-release-aab`.
- A pull-request CI run uploads `poweramp-bridge-pr-debug-apk` for validation only.
- `.github/workflows/android-release.yml` is the manual signed-release workflow.

## Workflow

1. Inspect the target branch and its commit SHA with `gh.exe`.
2. Trigger a run only when a fresh build is needed.
3. Report success only after the final run is complete and its `headSha` matches the target commit.
4. Download only the requested artifact. Extract an APK to a deterministic local scratch path when installation is requested.
5. Keep `_ci_apk/`, `_gh_artifacts/`, downloaded ZIPs, and generated APKs out of Git.

## Reporting minimum

- workflow and run id;
- target branch and commit SHA;
- final conclusion;
- artifact name; and
- extracted APK path and installed version when device installation was requested.
