#!/usr/bin/env bash
#
# Safely merges the upstream Jellyfin repository into this fork.
#
# The fork keeps its own commits (branding, extra features) on `master` and pulls in upstream
# changes with a merge commit. Rebasing is deliberately not used: it would rewrite already
# published commits, invalidate release tags and force-push over the fork's history.
#
# The script
#   1. refuses to run with uncommitted changes,
#   2. fetches `upstream`,
#   3. reports how far the fork has diverged,
#   4. warns when upstream touched files the fork also changed (likely conflicts),
#   5. creates a `backup/before-upstream-merge` branch as a safety net,
#   6. merges upstream with `--no-ff` (a real merge commit, never a fast-forward),
#   7. verifies that the fork's own branding files survived the merge.
#
# Nothing is pushed. Review the result, build, then push manually.
#
# Usage:
#   ./scripts/sync-upstream.sh --dry-run
#   ./scripts/sync-upstream.sh
#   ./scripts/sync-upstream.sh --upstream-branch master

set -euo pipefail

dry_run=false
upstream_branch=master

while [[ $# -gt 0 ]]; do
	case "$1" in
		-n | --dry-run)
			dry_run=true
			shift
			;;
		--upstream-branch)
			upstream_branch="${2:?missing value for --upstream-branch}"
			shift 2
			;;
		-h | --help)
			grep '^#' "$0" | sed 's/^# \{0,1\}//'
			exit 0
			;;
		*)
			echo "Unknown option: $1" >&2
			exit 2
			;;
	esac
done

if [[ -t 1 ]]; then
	c_reset=$'\e[0m'
	c_cyan=$'\e[36m'
	c_yellow=$'\e[33m'
	c_red=$'\e[31m'
	c_green=$'\e[32m'
else
	c_reset=
	c_cyan=
	c_yellow=
	c_red=
	c_green=
fi

info() { printf '%s%s%s\n' "$c_cyan" "$*" "$c_reset"; }

# Make sure we run from the repository root.
repo_root=$(git rev-parse --show-toplevel) || {
	echo 'Not inside a git repository.' >&2
	exit 1
}
cd "$repo_root"
info "Repository: $repo_root"

# 1. A clean tree is required so the merge can always be undone with `git merge --abort`.
if [[ -n "$(git status --porcelain)" ]]; then
	echo 'There are uncommitted changes. Commit or stash them first:' >&2
	git status --porcelain >&2
	exit 1
fi

# 2. The fork is expected to have the `upstream` remote.
remotes=$(git remote)
if ! grep -qx upstream <<<"$remotes"; then
	echo "Remote 'upstream' is not configured. Add it with:" >&2
	echo "  git remote add upstream https://github.com/jellyfin/$(basename "$repo_root").git" >&2
	exit 1
fi
if grep -qx fork <<<"$remotes"; then
	fork_remote=fork
else
	fork_remote=origin
fi

info 'Fetching upstream...'
git fetch upstream --tags --prune

# 3. Divergence report.
target="upstream/$upstream_branch"
read -r behind ahead < <(git rev-list --left-right --count "$target...HEAD")

echo
printf '%svs %s : %s commit(s) behind, %s commit(s) of our own%s\n' "$c_yellow" "$target" "$behind" "$ahead" "$c_reset"

if [[ "$behind" -eq 0 ]]; then
	printf '%sAlready up to date, nothing to merge.%s\n' "$c_green" "$c_reset"
	exit 0
fi

echo
info 'Incoming upstream commits:'
git log --oneline --no-decorate "HEAD..$target" | sed 's/^/  /'

# 4. Overlap check - the cheapest way to predict conflicts.
# `A...B` is the diff against the merge base, so it lists only the files each side changed itself.
our_files=$(git diff --name-only "$target...HEAD" --diff-filter=d | sed '/^$/d' | sort -u)
their_files=$(git log --name-only --pretty=format: "HEAD..$target" | sed '/^$/d' | sort -u)
overlap=$(comm -12 <(printf '%s\n' "$our_files") <(printf '%s\n' "$their_files"))

echo
if [[ -n "$overlap" ]]; then
	printf '%sFiles changed on BOTH sides - conflicts are likely:%s\n' "$c_red" "$c_reset"
	printf '%s  %s%s\n' "$c_red" "${overlap//$'\n'/$'\n'  }" "$c_reset"
	printf '%sResolve them by keeping the fork behaviour and folding in the upstream fix.%s\n' "$c_red" "$c_reset"
else
	printf '%sNo file was changed on both sides - a clean merge is expected.%s\n' "$c_green" "$c_reset"
fi

if $dry_run; then
	echo
	printf '%sDry run - nothing was changed.%s\n' "$c_yellow" "$c_reset"
	exit 0
fi

# 5. Safety net.
git branch -f backup/before-upstream-merge HEAD
echo
info 'Backup branch: backup/before-upstream-merge'

# 6. Merge with an explicit merge commit.
info "Merging $target ..."
if git merge "$target" --no-ff -m "Merge upstream $(basename "$repo_root") $upstream_branch into the fork"; then
	:
else
	echo
	printf '%sThe merge stopped - most likely on conflicts.%s\n' "$c_red" "$c_reset"
	printf '%sResolve the files listed above, then run:%s\n' "$c_red" "$c_reset"
	printf '  git add <files>\n  git commit\n'
	printf '%sTo undo everything instead:%s\n' "$c_red" "$c_reset"
	printf '  git merge --abort\n'
	exit 1
fi

# 7. Verify the fork's own branding survived.
echo
info 'Fork branding check:'
branding_found=false
for path in \
	app/src/main/res/values/strings.xml \
	app/src/main/res/values/strings_donottranslate.xml; do
	if [[ -f "$path" ]]; then
		branding_found=true
		if grep -q Jellykulik "$path"; then
			printf '  %sOK%s   %s still contains '\''Jellykulik'\''\n' "$c_green" "$c_reset" "$path"
		else
			printf '  %sLOST%s %s no longer contains '\''Jellykulik'\''\n' "$c_red" "$c_reset" "$path"
		fi
	fi
done
if ! $branding_found; then
	printf '  %s(no known branding file found - check manually)%s\n' "$c_yellow" "$c_reset"
fi

echo
info 'Merge done. Next steps:'
printf '  1. build:  ./gradlew :app:compileDebugKotlin\n'
printf '  2. review: git log --oneline -5 ; git show --stat HEAD\n'
printf '  3. push:   git push %s master\n' "$fork_remote"
printf '  4. clean up the backup once you are happy: git branch -D backup/before-upstream-merge\n'
