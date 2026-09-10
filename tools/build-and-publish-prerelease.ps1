[CmdletBinding()]
param(
    [string[]]$Profiles = @('1.21.11', '26.2'),
    [string]$Tag,
    [string]$TargetBranch,
    [switch]$SkipBuild,
    [switch]$AllowDirty,
    [switch]$Draft
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'GitHub CLI (gh) is required.' }
if (-not (Get-Command bash -ErrorAction SilentlyContinue)) { throw 'Git Bash (bash) is required.' }
$version = (Get-Content (Join-Path $root 'VERSION') -Raw).Trim()
if ($version -notmatch '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$') { throw "Invalid VERSION: $version" }
if ([string]::IsNullOrWhiteSpace($Tag)) { $Tag = "v$version-pre.$(Get-Date -Format 'yyyyMMdd-HHmmss')" }
if ($Tag -notmatch '^v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$') { throw "Invalid prerelease tag: $Tag" }
$status = git status --short
if ($status -and -not $AllowDirty) { throw 'Working tree is dirty. Re-run with -AllowDirty after review.' }
$head = (git rev-parse --verify HEAD).Trim()
if ([string]::IsNullOrWhiteSpace($TargetBranch)) { $TargetBranch = (git branch --show-current).Trim() }
if ([string]::IsNullOrWhiteSpace($TargetBranch)) { throw 'Detached HEAD requires -TargetBranch.' }
if (-not $SkipBuild) {
    foreach ($profile in $Profiles) {
        $profilePath = "versions/$profile.json"
        if (-not (Test-Path (Join-Path $root "port/$profilePath"))) { throw "Missing profile: $profilePath" }
        $env:GAIUS_VERSION_PROFILE_PATH = $profilePath
        $env:GAIUS_BUILD_ROOT = "port/target/$profile"
        $env:GAIUS_OVERLAY_DIRECTORY = "port/work/overlays/$profile"
        $env:GAIUS_DIST_DIRECTORY = "port/web/dist/$profile"
        Write-Host "== building $profile ==" -ForegroundColor Cyan
        & bash (Join-Path $root 'port/scripts/fetch-version.sh'); if ($LASTEXITCODE) { throw "fetch-version failed for $profile" }
        & bash (Join-Path $root 'port/scripts/remap-client.sh'); if ($LASTEXITCODE) { throw "remap-client failed for $profile" }
        & bash (Join-Path $root 'port/scripts/build-version-release.sh') $profile; if ($LASTEXITCODE) { throw "build-version-release failed for $profile" }
    }
}

function Assert-ProfileArtifactsCurrent([string]$profile) {
    $profilePath = "versions/$profile.json"
    if (-not (Test-Path (Join-Path $root "port/$profilePath"))) {
        throw "Missing profile: $profilePath"
    }
    $env:GAIUS_VERSION_PROFILE_PATH = $profilePath
    $env:GAIUS_BUILD_ROOT = "port/target/$profile"
    $env:GAIUS_OVERLAY_DIRECTORY = "port/work/overlays/$profile"
    $env:GAIUS_DIST_DIRECTORY = "port/web/dist/$profile"
    $dist = Join-Path $root $env:GAIUS_DIST_DIRECTORY
    $roles = @(
        @('client', 'classes.js'),
        @('singleplayer-worker', 'singleplayer-server.js'),
        @('wasm-hotpath', 'gaius-hotpath.wasm'),
        @('worker-bootstrap', 'singleplayer-server-worker.js'),
        @('vanilla-assets', 'vanilla-assets.pack.gz'),
        @('relay-registry', 'relay-nodes.json')
    )
    foreach ($entry in $roles) {
        $artifact = Join-Path $dist $entry[1]
        if (-not (Test-Path $artifact)) { throw "Missing $profile artifact: $artifact" }
        & python (Join-Path $root 'port/scripts/gaius_build_identity.py') verify `
            --root $root --role $entry[0] --artifact $artifact *> $null
        if ($LASTEXITCODE) {
            throw "Stale or invalid $profile artifact identity: $artifact. Rebuild without -SkipBuild."
        }
    }
    $contract = Join-Path $root 'apps/bridge/browser-full-path-artifact-contract-smoke.mjs'
    $report = Join-Path $env:GAIUS_BUILD_ROOT 'browser-full-path-artifact-contract-prerelease.json'
    node $contract *> $report
    if ($LASTEXITCODE) {
        throw "Portable artifact contract failed for $profile; see $report"
    }
}

foreach ($profile in $Profiles) {
    Assert-ProfileArtifactsCurrent $profile
}
$stage = Join-Path $root "port/target/local-prerelease/$Tag"
if (Test-Path $stage) { throw "Refusing to overwrite staging directory: $stage" }
New-Item -ItemType Directory -Force $stage | Out-Null
foreach ($profile in $Profiles) {
    $dist = Join-Path $root "port/web/dist/$profile"
    foreach ($name in @('Gaius.html', 'Gaius.manifest.json')) {
        $source = Join-Path $dist $name
        if (-not (Test-Path $source) -or (Get-Item $source).Length -eq 0) { throw "Missing artifact: $source" }
        $dest = if ($name -eq 'Gaius.html') { "Gaius-$profile.html" } else { "Gaius-$profile.manifest.json" }
        Copy-Item $source (Join-Path $stage $dest)
    }
}
$plugin = Join-Path $root "apps/server-plugin/target/gaius-server-plugin-$version.jar"
if (Test-Path $plugin) { Copy-Item $plugin (Join-Path $stage (Split-Path $plugin -Leaf)) }
[ordered]@{ tag=$Tag; version=$version; sourceHead=$head; sourceBranch=$TargetBranch; dirty=[bool]$status; generatedAt=(Get-Date).ToUniversalTime().ToString('o'); profiles=@($Profiles); relay='t40.sjcmc.cn:14803 via wss://ellan.site/tunnel' } |
    ConvertTo-Json -Depth 4 | Set-Content (Join-Path $stage 'prerelease.manifest.json') -Encoding utf8
$notesPath = Join-Path $stage 'RELEASE-NOTES.md'
@"
Gaius Client local prerelease $Tag

Built locally from $head on branch $TargetBranch.
Profiles: $($Profiles -join ', ').
Multiplayer target: t40.sjcmc.cn:14803 via wss://ellan.site/tunnel.

Compiled and uploaded by tools/build-and-publish-prerelease.ps1; GitHub Actions is not involved.
See prerelease.manifest.json and SHA256SUMS for provenance.
"@ | Set-Content $notesPath -Encoding utf8
$hashLines = foreach ($file in Get-ChildItem $stage -File | Sort-Object Name) { "$( (Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() )  $($file.Name)" }
$hashLines | Set-Content (Join-Path $stage 'SHA256SUMS') -Encoding ascii
$repo = 'TypeThe0ry/Gaius'
gh release view $Tag --repo $repo *> $null
$exists = ($LASTEXITCODE -eq 0)
$assets = @(Get-ChildItem $stage -File | ForEach-Object FullName)
if ($exists) {
    gh release upload $Tag --repo $repo @assets --clobber; if ($LASTEXITCODE) { throw 'gh release upload failed' }
    $edit = @('release','edit',$Tag,'--repo',$repo,'--title',"Gaius Client $version $Tag",'--notes-file',$notesPath)
    $edit += if ($Draft) { '--draft' } else { '--prerelease' }; gh @edit
} else {
    $create = @('release','create',$Tag,'--repo',$repo,'--title',"Gaius Client $version $Tag",'--notes-file',$notesPath,'--target',$TargetBranch)
    $create += if ($Draft) { '--draft' } else { '--prerelease' }; $create += $assets; gh @create
}
if ($LASTEXITCODE) { throw 'GitHub release publish failed' }
gh release view $Tag --repo $repo --json tagName,isPrerelease,isDraft,publishedAt,url
