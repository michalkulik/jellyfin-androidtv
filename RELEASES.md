# Releasing this fork

This repository is a fork of [jellyfin/jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv).
It publishes its own signed APK builds through GitHub releases. Releases are built, signed and
published locally with [`scripts/release-local.sh`](scripts/release-local.sh); the
[`Fork / Release`](.github/workflows/fork-release.yaml) workflow is kept only as a manual fallback.

## Remotes

| Remote     | Points at                                       |
| ---------- | ----------------------------------------------- |
| `fork`     | `michalkulik/jellyfin-androidtv` (this fork)     |
| `upstream` | `jellyfin/jellyfin-androidtv` (the original)     |

`master` tracks `fork/master`, so a plain `git push` goes to the fork.

## Keeping the fork up to date with upstream

The fork keeps its own commits (the Jellykulik branding, the release workflow) on `master` and pulls
in upstream changes with a **merge commit**. Rebasing is deliberately avoided: it rewrites already
published commits, invalidates the release tags and would need a force-push over the fork history.

The whole procedure is scripted:

```bash
# look first, change nothing
./scripts/sync-upstream.sh --dry-run

# then actually merge
./scripts/sync-upstream.sh
```

The script refuses to run with uncommitted changes, fetches `upstream`, prints how far the fork has
diverged, lists the incoming commits, warns about files that changed on **both** sides (the usual
source of conflicts), creates a `backup/before-upstream-merge` branch, merges with `--no-ff` and
finally checks that the Jellykulik branding survived. It never pushes.

Afterwards build and push by hand:

```bash
./gradlew :app:compileDebugKotlin
git push fork master
```

If the merge went wrong, undo it and start over:

```bash
git merge --abort                                  # during the merge
git reset --hard backup/before-upstream-merge      # after the merge commit
```

Once you are happy with the result, drop the safety net:

```bash
git branch -D backup/before-upstream-merge
git push fork --delete backup/before-upstream-merge
```

## Publishing a new version

Releases are built, signed and published locally — one build, no GitHub Actions:

```bash
git push fork master
./scripts/release-local.sh 0.2.21
```

The script builds the signed release APK, writes `latest.json`, creates and pushes the `vX.Y.Z`
tag, and publishes the GitHub release at `https://github.com/michalkulik/jellyfin-androidtv/releases`
with both the APK and the manifest (creating the release if needed, updating it otherwise).

Signing credentials are read from the environment or from `../signing/github-secrets.txt`
(`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), together with the keystore at
`../signing/fork-release.jks`; override with the `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` /
`KEY_ALIAS` / `KEY_PASSWORD` environment variables.

The `Fork / Release` workflow still exists as a manual fallback (**Actions** → *Fork / Release* →
*Run workflow*). It is deliberately no longer triggered by tags: GitHub throttles Actions usage on
forks, and building locally once avoids building the same thing twice.

## Release notes

The notes are generated from the git history, so there is nothing to write by hand:

* **Changes** — the fork's own commits since the previous release (`--first-parent`, so the commits
  pulled in from upstream are not repeated here).
* **Upstream changes included** — the commits the merges brought in. For a merge commit `M` created
  by `git merge upstream/master`, those are exactly `M^1..M^2`, because the fork is the first parent
  and upstream the second.

The previous release is taken from `gh release list`, not from `git tag`: the clone also carries
every upstream tag (`v0.19.x`, `v0.20.0-beta.x`, …) and those sort higher than the fork's `v0.1.x`
line. This is why the checkout uses `fetch-depth: 0` — tags and history have to be present.

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
