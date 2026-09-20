<#
.SYNOPSIS
    Safely merges the upstream Jellyfin repository into this fork.

.DESCRIPTION
    The fork keeps its own commits (branding, extra features) on `master` and pulls in upstream
    changes with a merge commit. Rebasing is deliberately not used: it would rewrite already
    published commits, invalidate release tags and force-push over the fork's history.

    The script
      1. refuses to run with uncommitted changes,
      2. fetches `upstream`,
      3. reports how far the fork has diverged,
      4. warns when upstream touched files the fork also changed (likely conflicts),
      5. creates a `backup/before-upstream-merge` branch as a safety net,
      6. merges upstream with `--no-ff` (a real merge commit, never a fast-forward),
      7. verifies that the fork's own branding files survived the merge.

    Nothing is pushed. Review the result, build, then push manually.

.PARAMETER DryRun
    Only report the divergence and the overlapping files, change nothing.

.PARAMETER UpstreamBranch
    The upstream branch to merge. Defaults to `master`.

.EXAMPLE
    .\scripts\sync-upstream.ps1 -DryRun
    .\scripts\sync-upstream.ps1
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [string]$UpstreamBranch = 'master'
)

$ErrorActionPreference = 'Stop'

# Make sure we run from the repository root.
$repoRoot = (& git rev-parse --show-toplevel 2>$null)
if ($LASTEXITCODE -ne 0) { throw 'Not inside a git repository.' }
Set-Location $repoRoot
Write-Host "Repository: $repoRoot" -ForegroundColor Cyan

function Invoke-Git {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $output = & git @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed:`n$output" }
    return $output
}

# 1. A clean tree is required so the merge can always be undone with `git merge --abort`.
$dirty = & git status --porcelain
if ($dirty) {
    throw "There are uncommitted changes. Commit or stash them first:`n$dirty"
}

# 2. The fork is expected to have the `upstream` remote.
$remotes = & git remote
if ($remotes -notcontains 'upstream') {
    throw "Remote 'upstream' is not configured. Add it with:`n  git remote add upstream https://github.com/jellyfin/$(Split-Path $repoRoot -Leaf).git"
}
$forkRemote = if ($remotes -contains 'fork') { 'fork' } else { 'origin' }

Write-Host "Fetching upstream..." -ForegroundColor Cyan
Invoke-Git fetch upstream --tags --prune | Out-Null

# 3. Divergence report.
$target = "upstream/$UpstreamBranch"
$counts = (Invoke-Git rev-list --left-right --count "$target...HEAD") -split '\s+'
$behind = [int]$counts[0]
$ahead = [int]$counts[1]

Write-Host ''
Write-Host "vs $target : $behind commit(s) behind, $ahead commit(s) of our own" -ForegroundColor Yellow

if ($behind -eq 0) {
    Write-Host 'Already up to date, nothing to merge.' -ForegroundColor Green
    return
}

$incoming = Invoke-Git log --oneline --no-decorate "HEAD..$target"
Write-Host ''
Write-Host 'Incoming upstream commits:' -ForegroundColor Cyan
$incoming | ForEach-Object { Write-Host "  $_" }

# 4. Overlap check - the cheapest way to predict conflicts.
# `A...B` is the diff against the merge base, so it lists only the files each side changed itself.
$ourFiles = Invoke-Git diff --name-only "$target...HEAD" --diff-filter=d
$theirFiles = Invoke-Git log --name-only --pretty=format: "HEAD..$target"
$theirFiles = $theirFiles | Where-Object { $_ }

$overlap = $ourFiles | Where-Object { $theirFiles -contains $_ }
Write-Host ''
if ($overlap) {
    Write-Host 'Files changed on BOTH sides - conflicts are likely:' -ForegroundColor Red
    $overlap | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Write-Host 'Resolve them by keeping the fork behaviour and folding in the upstream fix.' -ForegroundColor Red
} else {
    Write-Host 'No file was changed on both sides - a clean merge is expected.' -ForegroundColor Green
}

if ($DryRun) {
    Write-Host ''
    Write-Host 'Dry run - nothing was changed.' -ForegroundColor Yellow
    return
}

# 5. Safety net.
& git branch -f backup/before-upstream-merge HEAD
Write-Host ''
Write-Host 'Backup branch: backup/before-upstream-merge' -ForegroundColor Cyan

# 6. Merge with an explicit merge commit.
Write-Host "Merging $target ..." -ForegroundColor Cyan
$mergeOutput = & git merge $target --no-ff -m "Merge upstream $(Split-Path $repoRoot -Leaf) $UpstreamBranch into the fork" 2>&1
$mergeExit = $LASTEXITCODE
$mergeOutput | ForEach-Object { Write-Host "  $_" }

if ($mergeExit -ne 0) {
    Write-Host ''
    Write-Host 'The merge stopped - most likely on conflicts.' -ForegroundColor Red
    Write-Host 'Resolve the files listed above, then run:' -ForegroundColor Red
    Write-Host '  git add <files>' -ForegroundColor Red
    Write-Host '  git commit' -ForegroundColor Red
    Write-Host 'To undo everything instead:' -ForegroundColor Red
    Write-Host '  git merge --abort' -ForegroundColor Red
    throw 'Merge failed.'
}

# 7. Verify the fork's own branding survived.
Write-Host ''
Write-Host 'Fork branding check:' -ForegroundColor Cyan
$brandingChecks = @(
    @{ Path = 'app/src/main/res/values/strings.xml';               Pattern = 'Jellykulik' },
    @{ Path = 'app/src/main/res/values/strings_donottranslate.xml'; Pattern = 'Jellykulik' }
)
$brandingFound = $false
foreach ($check in $brandingChecks) {
    if (Test-Path $check.Path) {
        $brandingFound = $true
        if (Select-String -Path $check.Path -Pattern $check.Pattern -Quiet) {
            Write-Host "  OK   $($check.Path) still contains '$($check.Pattern)'" -ForegroundColor Green
        } else {
            Write-Host "  LOST $($check.Path) no longer contains '$($check.Pattern)'" -ForegroundColor Red
        }
    }
}
if (-not $brandingFound) {
    Write-Host '  (no known branding file found - check manually)' -ForegroundColor Yellow
}

Write-Host ''
Write-Host 'Merge done. Next steps:' -ForegroundColor Cyan
Write-Host "  1. build:  .\gradlew.bat :app:compileDebugKotlin"
Write-Host "  2. review: git log --oneline -5 ; git show --stat HEAD"
Write-Host "  3. push:   git push $forkRemote master"
Write-Host "  4. clean up the backup once you are happy: git branch -D backup/before-upstream-merge"
