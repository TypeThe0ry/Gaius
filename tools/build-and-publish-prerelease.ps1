[CmdletBinding()]
param(
    [string[]]$Profiles = @('1.21.11', '26.2', '26.3'),
    [string]$Tag,
    [string]$TargetBranch,
    [switch]$SkipBuild,
    [switch]$AllowDirty,
    [switch]$Draft,
    # v0.4: the release root of port/scripts/build-release-matrix.sh. Nothing is built here then;
    # the sites, the WebAssembly GC builds and the JavaScript fallback (rebuilt or frozen) come
    # from that root and its release-matrix.json, and its size gate (pages-size.json) must pass.
    [string]$ReleaseRoot
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
$matrix = $null
if (-not [string]::IsNullOrWhiteSpace($ReleaseRoot)) {
    $ReleaseRoot = (Resolve-Path $ReleaseRoot).Path
    $matrix = Get-Content (Join-Path $ReleaseRoot 'release-matrix.json') -Raw | ConvertFrom-Json
    $pagesSize = Get-Content (Join-Path $ReleaseRoot 'pages-size.json') -Raw | ConvertFrom-Json
    if (-not $pagesSize.ok) { throw "The Pages sites in $ReleaseRoot do not pass the size gate (pages-size.json)." }
    $SkipBuild = $true
}
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

# Where a profile's JavaScript build and site archive are: the classic per-profile roots, or
# the release matrix's JavaScript fallback (rebuilt in the release root, or frozen elsewhere)
# and its site archive.
function Get-ProfileLayout([string]$profile) {
    if ($null -eq $matrix) {
        return @{
            Build = "port/target/$profile"; Overlays = "port/work/overlays/$profile"; Dist = "port/web/dist/$profile"
            Site = Join-Path $root "port/target/$profile/Gaius-site-$profile.tar.gz"; Frozen = $false; Wasm = $null
            Reports = "port/target/$profile"
        }
    }
    $entry = $matrix.profiles.$profile
    if ($null -eq $entry) { throw "Profile $profile is not in $ReleaseRoot/release-matrix.json" }
    return @{
        Build = $entry.js.build; Overlays = Join-Path (Split-Path $entry.js.build -Parent) 'overlays'; Dist = $entry.js.dist
        Site = Join-Path $ReleaseRoot "Gaius-site-$profile.tar.gz"; Frozen = ($entry.js.decision -eq 'frozen')
        Wasm = Join-Path $ReleaseRoot "$profile/wasm"; Reports = Join-Path $ReleaseRoot "$profile/logs"
    }
}

function Assert-ProfileArtifactsCurrent([string]$profile) {
    $profilePath = "versions/$profile.json"
    if (-not (Test-Path (Join-Path $root "port/$profilePath"))) {
        throw "Missing profile: $profilePath"
    }
    $layout = Get-ProfileLayout $profile
    $env:GAIUS_VERSION_PROFILE_PATH = $profilePath
    $env:GAIUS_BUILD_ROOT = $layout.Build
    $env:GAIUS_OVERLAY_DIRECTORY = $layout.Overlays
    $env:GAIUS_DIST_DIRECTORY = $layout.Dist
    $dist = if ([System.IO.Path]::IsPathRooted($layout.Dist)) { $layout.Dist } else { Join-Path $root $layout.Dist }
    $roles = @(
        @('client', 'classes.js'),
        @('singleplayer-worker', 'singleplayer-server.js'),
        @('wasm-hotpath', 'gaius-hotpath.wasm'),
        @('worker-bootstrap', 'singleplayer-server-worker.js'),
        @('vanilla-assets', 'vanilla-assets.pack.gz'),
        @('relay-registry', 'relay-nodes.json')
    )
    # A page that loads the WebAssembly shader toolchain (26.3+) also carries the
    # toolchain identity that build-teavm-release.sh wrote on its manifest; the
    # six roles above stay the complete set for 1.21.11 and 26.2.
    $index = Join-Path $dist 'index.html'
    if (-not (Test-Path $index)) { throw "Missing $profile artifact: $index" }
    if ((Get-Content $index -Raw).Contains('data-gaius-shader-toolchain')) {
        $roles += ,@('shader-toolchain', 'gaius-shader-toolchain.json')
    }
    # A frozen fallback was built from an earlier tree: its artifacts must still be the ones its
    # sidecars name (the matrix decided that no trigger requires a rebuild).
    $check = if ($layout.Frozen) { 'frozen' } else { 'verify' }
    foreach ($entry in $roles) {
        $artifact = Join-Path $dist $entry[1]
        if (-not (Test-Path $artifact)) { throw "Missing $profile artifact: $artifact" }
        & python (Join-Path $root 'port/scripts/gaius_build_identity.py') $check `
            --root $root --role $entry[0] --artifact $artifact *> $null
        if ($LASTEXITCODE) {
            throw "Stale or invalid $profile artifact identity: $artifact. Rebuild without -SkipBuild."
        }
    }
    $contract = Join-Path $root 'apps/bridge/browser-full-path-artifact-contract-smoke.mjs'
    $reports = if ([System.IO.Path]::IsPathRooted($layout.Reports)) { $layout.Reports } else { Join-Path $root $layout.Reports }
    New-Item -ItemType Directory -Force $reports | Out-Null
    $report = Join-Path $reports 'browser-full-path-artifact-contract-prerelease.json'
    node $contract *> $report
    if ($LASTEXITCODE) {
        throw "Portable artifact contract failed for $profile; see $report"
    }
    if ($null -ne $layout.Wasm) {
        # The WebAssembly GC client and server of the release matrix (build-wasmgc.sh sidecars).
        $env:GAIUS_BUILD_ROOT = Join-Path $layout.Wasm 'build'
        $env:GAIUS_OVERLAY_DIRECTORY = Join-Path $layout.Wasm 'overlays'
        $env:GAIUS_DIST_DIRECTORY = Join-Path $layout.Wasm 'dist'
        foreach ($entry in @(@('wasm-client', 'classes.wasm'), @('wasm-singleplayer-worker', 'singleplayer-server.wasm'))) {
            $artifact = Join-Path $env:GAIUS_DIST_DIRECTORY $entry[1]
            if (-not (Test-Path $artifact)) { throw "Missing $profile artifact: $artifact" }
            & python (Join-Path $root 'port/scripts/gaius_build_identity.py') verify `
                --root $root --role $entry[0] --artifact $artifact *> $null
            if ($LASTEXITCODE) { throw "Stale or invalid $profile artifact identity: $artifact. Rebuild the release matrix." }
        }
    }
}

foreach ($profile in $Profiles) {
    Assert-ProfileArtifactsCurrent $profile
}
$stage = Join-Path $root "port/target/local-prerelease/$Tag"
if (Test-Path $stage) { throw "Refusing to overwrite staging directory: $stage" }
New-Item -ItemType Directory -Force $stage | Out-Null
if (-not [string]::IsNullOrWhiteSpace($ReleaseRoot)) {
    $pagesMatrix = Join-Path $ReleaseRoot 'Gaius-pages-matrix.json'
    if (-not (Test-Path $pagesMatrix)) { throw "Missing Pages matrix: $pagesMatrix" }
    Copy-Item $pagesMatrix (Join-Path $stage 'Gaius-pages-matrix.json')
}
foreach ($profile in $Profiles) {
    $layout = Get-ProfileLayout $profile
    $dist = if ([System.IO.Path]::IsPathRooted($layout.Dist)) { $layout.Dist } else { Join-Path $root $layout.Dist }
    foreach ($name in @('Gaius.html', 'Gaius.manifest.json')) {
        $source = Join-Path $dist $name
        if (-not (Test-Path $source) -or (Get-Item $source).Length -eq 0) { throw "Missing artifact: $source" }
        $dest = if ($name -eq 'Gaius.html') { "Gaius-$profile.html" } else { "Gaius-$profile.manifest.json" }
        Copy-Item $source (Join-Path $stage $dest)
    }
    # The multi-file GitHub Pages site (build-version-release.sh, or the release matrix); pages.yml
    # only deploys the site layout when all three archives are release assets listed in SHA256SUMS.
    $site = $layout.Site
    if (-not (Test-Path $site) -or (Get-Item $site).Length -eq 0) { throw "Missing artifact: $site" }
    if ((Get-Item $site).LastWriteTimeUtc -lt (Get-Item (Join-Path $dist 'Gaius.html')).LastWriteTimeUtc) {
        throw "Stale $profile site archive: $site predates its Gaius.html. Rebuild without -SkipBuild."
    }
    Copy-Item $site (Join-Path $stage "Gaius-site-$profile.tar.gz")
}
$plugin = Join-Path $root "apps/server-plugin/target/gaius-server-plugin-$version.jar"
if (Test-Path $plugin) { Copy-Item $plugin (Join-Path $stage (Split-Path $plugin -Leaf)) }
[ordered]@{ tag=$Tag; version=$version; sourceHead=$head; sourceBranch=$TargetBranch; dirty=[bool]$status; generatedAt=(Get-Date).ToUniversalTime().ToString('o'); profiles=@($Profiles); relay='runtime-configured via relay URL'; releaseMatrix=$(if ($null -ne $matrix) { $matrix.commit } else { $null }) } |
    ConvertTo-Json -Depth 4 | Set-Content (Join-Path $stage 'prerelease.manifest.json') -Encoding utf8
$notesPath = Join-Path $stage 'RELEASE-NOTES.md'
@"
Gaius Client local prerelease $Tag

Built locally from $head on branch $TargetBranch.
Profiles: $($Profiles -join ', ').
Multiplayer target: supplied by the private acceptance environment via the configured RelayNode URL.

Compiled and uploaded by tools/build-and-publish-prerelease.ps1; GitHub Actions is not involved.
See prerelease.manifest.json and SHA256SUMS for provenance.
"@ | Set-Content $notesPath -Encoding utf8
$hashLines = foreach ($file in Get-ChildItem $stage -File | Sort-Object Name) { "$( (Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() )  $($file.Name)" }
# sha256sum(1) expects one LF-delimited record per line.  Set-Content emits CRLF on
# Windows, which made the published manifest fail strict `sha256sum -c` verification.
$hashPath = Join-Path $stage 'SHA256SUMS'
$ascii = [System.Text.Encoding]::ASCII
[System.IO.File]::WriteAllText($hashPath, (($hashLines -join "`n") + "`n"), $ascii)
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
