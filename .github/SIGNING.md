# Android release signing

Release artifacts use one permanent keystore. The keystore and its properties are local secrets and must never be committed. GitHub Actions restores the keystore only in the runner for a signed build.

Required repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Create the Base64 secret from the local keystore with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('.signing/poweramp-bridge-release.jks')) | Set-Clipboard
```

Copy the remaining values from `.signing/release-signing.properties`. Do not commit either file.

## Artifact policy

- `.github/workflows/android-ci.yml` runs compilation and unit tests for its configured pushes, pull requests, and manual dispatches.
- Non-pull-request CI runs build and upload the signed `poweramp-bridge-release-apk` and `poweramp-bridge-release-aab` artifacts.
- Pull-request CI runs upload the validation-only `poweramp-bridge-pr-debug-apk` artifact.
- `.github/workflows/android-release.yml` is a manual signed-release workflow with the same release artifact names.

Install or distribute only the signed release APK. The pull-request debug APK uses an ephemeral CI debug key and cannot reliably update a release installation.

The signing steps fail closed when a secret is missing. The workflows verify the release APK with `apksigner` before upload and publish artifacts only; they do not create GitHub Releases.

The first move from an older debug-signed installation to the permanent release certificate requires one uninstall. Later release APKs can update each other when the application id remains `com.jerry155756294.powerampbridge` and `versionCode` increases.
