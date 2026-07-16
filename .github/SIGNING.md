# Android release signing

All installable release APKs and Play Store bundles use one permanent release keystore. It is intentionally not tracked in Git. GitHub Actions restores it only inside the runner while a signed build is running:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Create the Base64 secret from the local keystore with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('.signing/poweramp-bridge-release.jks')) | Set-Clipboard
```

Copy the store password, alias, and key password from `.signing/release-signing.properties`. Never commit either file. The signing workflow only uploads GitHub Actions artifacts; it does not create or publish a GitHub Release. Pull requests never receive signing secrets.

## Which artifact to install

- Pushes and manual/tagged Actions runs produce `poweramp-bridge-release-apk` and `poweramp-bridge-release-aab`. These are signed with the stable release certificate and are the only artifacts intended for device updates or distribution.
- Pull requests may produce `poweramp-bridge-pr-debug-apk`. It uses an ephemeral CI debug key and is for validation only; do not install it over a release build.

The CI release path fails closed when the signing secrets are missing. This prevents an unsigned or newly generated key from silently becoming a distribution build. The build also uses APK Signature Scheme v2/v3.

Because earlier debug APKs were signed by temporary runner keys, the first migration to the permanent release key requires uninstalling the old debug package once. Future release APKs can update each other as long as the application id remains `com.jerry155756294.powerampbridge` and `versionCode` increases.

To publish through Google Play, upload the AAB and enroll the app in Google Play App Signing. A stable local/CI certificate prevents signature mismatch, but no sideloaded APK can guarantee that Play Protect will never warn; Play distribution is the path that provides Google Play's normal trust and verification flow.
