# Android release signing

The release keystore is intentionally not tracked in Git. The release workflow reads these repository secrets only while a GitHub Actions job is running:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Create the Base64 secret from the local keystore with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('.signing/poweramp-bridge-release.jks')) | Set-Clipboard
```

Copy the store password, alias, and key password from `.signing/release-signing.properties`. Never commit either file. The release workflow is limited to manual runs and version tags, so pull requests never receive signing secrets.
