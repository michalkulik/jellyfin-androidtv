# Releasing this fork

This repository is a fork of [jellyfin/jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv).
It publishes its own APK builds through GitHub releases using the
[`Fork / Release`](.github/workflows/fork-release.yaml) workflow.

## Publishing a new version

1. Make sure your changes are committed and pushed to `master`:

   ```bash
   git push fork master
   ```

2. Create and push a version tag (the `v` prefix is stripped from the app version):

   ```bash
   git tag -a v0.1.0 -m "Jellykulik for Android TV 0.1.0"
   git push fork v0.1.0
   ```

3. The workflow builds the APK and creates (or updates) the GitHub release at
   `https://github.com/michalkulik/jellyfin-androidtv/releases`.

You can also run the workflow manually from the **Actions** tab (*Fork / Release* → *Run workflow*)
and provide a version number.

## What gets published

Each release contains one signed APK, and never a debug build:

| File                                              | Description          |
| ------------------------------------------------- | -------------------- |
| `jellyfin-androidtv-v<version>-release.apk`       | signed release build |

## Signing

Signing is required — the workflow fails if the secrets are missing, because an unsigned APK cannot
be installed, and a debug APK is a different application id (`org.jellyfin.androidtv.debug`).

The fork reuses the same keystore as the [Jellyfin for Android
fork](https://github.com/michalkulik/jellyfin-android), which lives outside the repository at
`signing/fork-release.jks`. Both applications being signed with the same key is fine: each keeps its
own application id.

Keep the keystore and its passwords safe — losing them means you can no longer update an installed
build in place.

### Repository secrets

In the fork open
[Settings → Secrets and variables → Actions](https://github.com/michalkulik/jellyfin-androidtv/settings/secrets/actions)
and create four *New repository secret* entries:

| Secret              | Value                              |
| ------------------- | ---------------------------------- |
| `KEYSTORE`          | base64 encoded `fork-release.jks`  |
| `KEYSTORE_PASSWORD` | keystore password                  |
| `KEY_ALIAS`         | key alias                          |
| `KEY_PASSWORD`      | key password                       |

To base64 encode the keystore on Windows:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('signing\fork-release.jks')) | Set-Content signing\keystore.b64
```

> Note: the fork keeps the upstream application id `org.jellyfin.androidtv`, so it cannot be
> installed over an official Jellyfin for Android TV build (different signing key). Uninstall the
> official build first; afterwards fork releases update in place.
