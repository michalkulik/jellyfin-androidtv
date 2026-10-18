#!/usr/bin/env bash
#
# Build, sign and publish a release locally — without GitHub Actions.
#
# This is the primary release path for the fork. The "Fork / Release" workflow is kept
# only as a manual fallback (workflow_dispatch) because GitHub throttles Actions usage on
# forks ("Workflows are not currently being run on this fork due to the scale of GitHub
# Actions usage"), and because building here once avoids building the same thing twice.
#
# Usage:
#   scripts/release-local.sh 0.2.21
#
# The script builds the signed release APK, writes latest.json (the manifest the in-app
# updater reads) and creates/updates the GitHub release with both assets, exactly like the
# CI workflow used to do.
#
# Signing (never commit secrets):
#   KEYSTORE_FILE      keystore path       (default: ../signing/fork-release.jks)
#   KEYSTORE_PASSWORD  keystore password   (or read from ../signing/github-secrets.txt)
#   KEY_ALIAS          key alias           (default: jellyfin-fork)
#   KEY_PASSWORD       key password        (or read from ../signing/github-secrets.txt)
#
# Requirements: JDK 21, Android SDK, `gh` authenticated for the fork.

set -euo pipefail

REPO="${REPO:-michalkulik/jellyfin-androidtv}"
REMOTE="${REMOTE:-fork}"
BRANCH="${BRANCH:-master}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/tools/android-sdk}}"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/tools/jdk21/jdk-21.0.12.1+1}"
SECRETS_FILE="${SECRETS_FILE:-../signing/github-secrets.txt}"
KEYSTORE_FILE="${KEYSTORE_FILE:-../signing/fork-release.jks}"
KEY_ALIAS="${KEY_ALIAS:-jellyfin-fork}"

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
	echo "użycie: $0 <wersja>   (np. $0 0.2.21)" >&2
	exit 1
fi
TAG="v$VERSION"

cd "$(git rev-parse --show-toplevel)"

# --- sanity -------------------------------------------------------------------------
branch=$(git rev-parse --abbrev-ref HEAD)
if [ "$branch" != "$BRANCH" ]; then
	echo "jesteś na gałęzi '$branch', a wydania robi się z '$BRANCH'" >&2
	exit 1
fi
if ! git diff --ignore-cr-at-eol --quiet || ! git diff --cached --ignore-cr-at-eol --quiet; then
	echo "Working tree nie jest czysty (pomijając końce linii). Zacommituj zmiany." >&2
	git status --short
	exit 1
fi

# --- signing secrets ----------------------------------------------------------------
secret() {
	[ -f "$SECRETS_FILE" ] || return 1
	# Supports both "KEY=value" and the "KEY\nvalue" layout of github-secrets.txt.
	tr -d '\r' < "$SECRETS_FILE" | awk -F= -v k="$1" '
		$1 == k {
			if (index($0, "=") > 0) { sub(/^[^=]*=/, ""); print; exit }
			getline; print; exit
		}
	'
}
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-$(secret KEYSTORE_PASSWORD || true)}"
KEY_PASSWORD="${KEY_PASSWORD:-$(secret KEY_PASSWORD || true)}"
[ -f "$KEYSTORE_FILE" ] || { echo "brak keystore: $KEYSTORE_FILE" >&2; exit 1; }
[ -n "$KEYSTORE_PASSWORD" ] || { echo "brak KEYSTORE_PASSWORD" >&2; exit 1; }
[ -n "$KEY_PASSWORD" ] || { echo "brak KEY_PASSWORD" >&2; exit 1; }

# --- SDK path (local.properties is gitignored) --------------------------------------
LOCAL_PROPS_BAK=""
if [ -f local.properties ]; then
	sdk_dir=$(sed -n 's/^sdk.dir=//p' local.properties || true)
	if [ ! -d "$sdk_dir" ] && [ -d "$SDK" ]; then
		LOCAL_PROPS_BAK="$(mktemp)"
		cp local.properties "$LOCAL_PROPS_BAK"
		printf 'sdk.dir=%s\n' "$SDK" > local.properties
	fi
fi
restore_local_props() {
	if [ -n "$LOCAL_PROPS_BAK" ]; then cp "$LOCAL_PROPS_BAK" local.properties; rm -f "$LOCAL_PROPS_BAK"; fi
}
trap restore_local_props EXIT

# --- build & sign -------------------------------------------------------------------
echo ">>> Buduję $TAG (assembleRelease, podpisany)"
JAVA_HOME="$JAVA_HOME" ANDROID_HOME="$SDK" \
	sh ./gradlew --no-daemon :app:assembleRelease \
		"-Pjellyfin.version=$VERSION" \
		"-Pkeystore.file=$KEYSTORE_FILE" \
		"-Pkeystore.password=$KEYSTORE_PASSWORD" \
		"-Psigning.key.alias=$KEY_ALIAS" \
		"-Psigning.key.password=$KEY_PASSWORD"

APK="app/build/outputs/apk/release/jellyfin-androidtv-v${VERSION}-release.apk"
[ -f "$APK" ] || { echo "nie znaleziono APK: $APK" >&2; exit 1; }
NAME=$(basename "$APK")
SIZE=$(stat -c '%s' "$APK")
SHA=$(sha256sum "$APK" | cut -d ' ' -f 1)

# --- update manifest (same version code formula as buildSrc/VersionUtils) ------------
CORE="${VERSION%%-*}"
PRE=$(echo "$VERSION" | sed -n 's/^[^-]*-[^.]*\.\([0-9]\{1,\}\)$/\1/p')
VERSION_CODE=$(( $(echo "$CORE" | cut -d '.' -f 1) * 1000000 \
	+ $(echo "$CORE" | cut -d '.' -f 2) * 10000 \
	+ $(echo "$CORE" | cut -d '.' -f 3) * 100 \
	+ ${PRE:-99} ))

mkdir -p build/fork-release
cp "$APK" "build/fork-release/$NAME"
cat > build/fork-release/latest.json <<EOF
{
  "version": "$VERSION",
  "versionCode": $VERSION_CODE,
  "publishedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "variants": {
    "tv": {
      "url": "https://github.com/$REPO/releases/download/$TAG/$NAME",
      "size": $SIZE,
      "sha256": "$SHA"
    }
  }
}
EOF

# --- tag (create + push if missing) -------------------------------------------------
if ! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
	echo ">>> Tworzę tag $TAG"
	git tag -a "$TAG" -m "Jellykulik for Android TV $VERSION"
	git push "$REMOTE" "$TAG"
fi
COMMIT=$(git rev-parse "$TAG^{commit}")

# --- release notes (same shape as the CI workflow) ----------------------------------
PREVIOUS_TAG=$(gh release list --repo "$REPO" --limit 20 --json tagName --jq '.[].tagName' \
	| grep -v "^$TAG$" | head -1 || true)
if [ -n "$PREVIOUS_TAG" ]; then RANGE="$PREVIOUS_TAG..$COMMIT"; else RANGE="$COMMIT"; fi

CHANGELOG=$(git log --no-merges --first-parent --pretty='- %s' "$RANGE" || true)
[ -z "$CHANGELOG" ] && CHANGELOG="- No changes"

UPSTREAM_NOTES=""
for merge in $(git rev-list --merges "$RANGE"); do
	brought_in=$(git log --no-merges --pretty='- %s' "$merge^1..$merge^2" 2>/dev/null || true)
	[ -n "$brought_in" ] && UPSTREAM_NOTES="$UPSTREAM_NOTES$brought_in"$'\n'
done
UPSTREAM_NOTES=$(printf '%s' "$UPSTREAM_NOTES" | sort -u | sed '/^$/d')

NOTES=$(cat <<EOF
Local build of Jellykulik for Android TV (Jellyfin for Android TV fork).

Version: \`$VERSION\`
Commit: \`$COMMIT\`

### Changes

$CHANGELOG

### Downloads

| APK | Description |
| --- | --- |
| \`$NAME\` | Signed release build |

\`latest.json\` describes the build and is read by the in-app updater, so installed apps
offer this version without any further setup.
This is a release build signed with the fork's signing key, so it updates in place.
EOF
)
if [ -n "$UPSTREAM_NOTES" ]; then
	NOTES="$NOTES

### Upstream changes included

$UPSTREAM_NOTES"
fi

# --- publish ------------------------------------------------------------------------
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
	gh release edit "$TAG" --repo "$REPO" --notes "$NOTES"
	for asset in $(gh release view "$TAG" --repo "$REPO" --json assets --jq '.assets[].name'); do
		gh release delete-asset "$TAG" "$asset" --repo "$REPO" --yes
	done
else
	gh release create "$TAG" --repo "$REPO" --title "$TAG" --notes "$NOTES"
fi
gh release upload "$TAG" --repo "$REPO" build/fork-release/*.apk build/fork-release/latest.json --clobber

echo
echo ">>> Opublikowano https://github.com/$REPO/releases/tag/$TAG"
gh release view "$TAG" --repo "$REPO" --json assets --jq '.assets[] | "\(.name)  \(.size) B"'
