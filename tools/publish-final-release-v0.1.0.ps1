[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Multiplayer12111EvidencePath,
    [Parameter(Mandatory = $true)][string]$Multiplayer262EvidencePath,
    [string]$Stage = 'port/target/release-v0.1.0-final-20260913',
    [string]$Repo = 'TypeThe0ry/Gaius',
    [int]$PagesTimeoutSeconds = 1200,
    [int]$PagesPollSeconds = 10,
    [int]$PagesVerifierTimeoutSeconds = 180,
    [string]$PagesEvidence = 'artifacts/github-pages-cdp-release-final.json',
    [switch]$ExecuteUpload
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# DEPRECATED: this publisher targets the v0.1.0 release layout (Gaius-1.21.11.html +
# Gaius-26.2.html). Since 0.3.0 the Pages workflow publishes exactly Gaius-26.2.html and
# Gaius-26.3.html, so this script's asset gate and Pages dispatch no longer apply.
# Use tools/build-and-publish-prerelease.ps1 and docs/releasing.md instead.
if (-not $env:GAIUS_ALLOW_LEGACY_V010_PUBLISHER) {
    throw 'publish-final-release-v0.1.0.ps1 is deprecated: the Pages workflow now publishes Gaius-26.2.html and Gaius-26.3.html. Use tools/build-and-publish-prerelease.ps1 (see docs/releasing.md).'
}
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $root
$tag = 'v0.1.0'
$requiredAssets = @(
    'Gaius-1.21.11.html', 'Gaius-1.21.11.manifest.json',
    'Gaius-26.2.html', 'Gaius-26.2.manifest.json',
    'gaius-server-plugin-0.1.0.jar', 'RELEASE-NOTES.md',
    'release.manifest.json', 'SHA256SUMS'
)

function Fail([string]$Message) { throw "FINAL RELEASE PUBLISH: $Message" }
if ($PagesTimeoutSeconds -lt 1) { Fail 'PagesTimeoutSeconds must be >= 1' }
if ($PagesPollSeconds -lt 1) { Fail 'PagesPollSeconds must be >= 1' }
if ($PagesVerifierTimeoutSeconds -lt 1 -or $PagesVerifierTimeoutSeconds -gt 2147483) {
    Fail 'PagesVerifierTimeoutSeconds must be between 1 and 2147483'
}
function Assert-ExactAssets([string]$Directory) {
    $actual = @(Get-ChildItem -LiteralPath $Directory -Force -File | ForEach-Object Name | Sort-Object)
    $expected = @($requiredAssets | Sort-Object)
    if (($actual -join "`n") -ne ($expected -join "`n")) {
        Fail "stage is not exact-eight (actual=$($actual -join ', '); expected=$($expected -join ', '))"
    }
    if (@(Get-ChildItem -LiteralPath $Directory -Force -Directory).Count -ne 0) { Fail 'stage contains unexpected directories' }
}
function Assert-TagUnchanged([string]$ExpectedObject, [string]$Label) {
    $local = (git rev-parse --verify "refs/tags/$tag").Trim()
    if ($local -ne $ExpectedObject) { Fail "$Label changed local tag ref ($local != $ExpectedObject)" }
    $remoteLine = @(git ls-remote --tags origin "refs/tags/$tag")
    if ($LASTEXITCODE -ne 0 -or $remoteLine.Count -ne 1) { Fail "$Label could not verify remote tag ref" }
    $remote = ([string]$remoteLine[0] -split "`t")[0]
    if ($remote -ne $ExpectedObject) { Fail "$Label changed/mismatched remote tag ref ($remote != $ExpectedObject)" }
}
function Assert-ReleaseSourceState {
    $branch = (& git symbolic-ref --quiet --short HEAD 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $branch -ne 'main') {
        Fail "release publication requires the checked-out main branch (actual=$branch)"
    }
    $trackedStatus = @(& git status --porcelain=v1 --untracked-files=no)
    if ($LASTEXITCODE -ne 0) { Fail 'could not inspect tracked source state' }
    if ($trackedStatus.Count -ne 0) {
        Fail "tracked source changes must be committed before publication: $($trackedStatus -join '; ')"
    }
}
function Assert-NoOpenPullRequestsOrIssues {
    $pullRequestJson = & gh pr list --repo $Repo --state open --limit 100 --json number,title,url
    if ($LASTEXITCODE -ne 0) { Fail 'could not list open pull requests' }
    $issueJson = & gh issue list --repo $Repo --state open --limit 100 --json number,title,url
    if ($LASTEXITCODE -ne 0) { Fail 'could not list open issues' }
    try {
        $pullRequests = @($pullRequestJson | ConvertFrom-Json)
        $issues = @($issueJson | ConvertFrom-Json)
    } catch { Fail "GitHub PR/Issue inventory returned invalid JSON: $($_.Exception.Message)" }
    if ($pullRequests.Count -ne 0) {
        Fail "open pull requests remain: $(@($pullRequests | ForEach-Object { '#{0} {1}' -f $_.number, $_.title }) -join '; ')"
    }
    if ($issues.Count -ne 0) {
        Fail "open issues remain: $(@($issues | ForEach-Object { '#{0} {1}' -f $_.number, $_.title }) -join '; ')"
    }
}
function Get-PagesRuns {
    $json = & gh run list --repo $Repo --workflow pages.yml --event workflow_dispatch --limit 50 `
        --json databaseId,displayTitle,headSha,event,status,conclusion,createdAt,startedAt,url
    if ($LASTEXITCODE -ne 0) { Fail 'could not list GitHub Pages workflow runs' }
    try { @($json | ConvertFrom-Json) }
    catch { Fail "gh run list returned invalid JSON: $($_.Exception.Message)" }
}
function Resolve-EvidencePath([string]$Path, [string]$Profile) {
    $resolved = if ([IO.Path]::IsPathRooted($Path)) {
        [IO.Path]::GetFullPath($Path)
    } else {
        [IO.Path]::GetFullPath((Join-Path $root $Path))
    }
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        Fail "$Profile multiplayer evidence is missing: $resolved"
    }
    $resolved
}
function Revalidate-MultiplayerEvidence(
    [string]$Profile,
    [string]$EvidencePath,
    [object]$DeclaredEvidence,
    [string]$ArtifactPath,
    [string]$Validator
) {
    try { $evidence = Get-Content -LiteralPath $EvidencePath -Raw | ConvertFrom-Json }
    catch { Fail "$Profile multiplayer evidence is invalid JSON: $($_.Exception.Message)" }
    if ($evidence.profile -ne $Profile) {
        Fail "$Profile multiplayer evidence profile mismatch: $($evidence.profile)"
    }
    $evidenceIdentity = [pscustomobject]@{
        bytes = [long](Get-Item -LiteralPath $EvidencePath).Length
        sha256 = (Get-FileHash -LiteralPath $EvidencePath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    if ($DeclaredEvidence.profile -ne $Profile -or $DeclaredEvidence.status -ne 'passed' -or
        [string]::IsNullOrWhiteSpace([string]$DeclaredEvidence.target) -or
        [string]::IsNullOrWhiteSpace([string]$DeclaredEvidence.relay) -or
        [string]::IsNullOrWhiteSpace([string]$DeclaredEvidence.validatorSchema) -or
        $DeclaredEvidence.file -ne [IO.Path]::GetFileName($EvidencePath) -or
        [long]$DeclaredEvidence.identity.bytes -ne $evidenceIdentity.bytes -or
        [string]$DeclaredEvidence.identity.sha256 -ne $evidenceIdentity.sha256) {
        Fail "$Profile supplied multiplayer evidence does not match the prepared release manifest"
    }

    $artifactIdentity = [pscustomobject]@{
        bytes = [long](Get-Item -LiteralPath $ArtifactPath).Length
        sha256 = (Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    if ([long]$DeclaredEvidence.artifactIdentity.bytes -ne $artifactIdentity.bytes -or
        [string]$DeclaredEvidence.artifactIdentity.sha256 -ne $artifactIdentity.sha256) {
        Fail "$Profile multiplayer manifest artifact identity is stale"
    }

    $priorTarget = $env:TARGET
    $priorRelay = $env:RELAY
    $priorArtifact = $env:ARTIFACT
    $priorProfile = $env:PROFILE
    $validatorOutput = $null
    $validatorExitCode = $null
    try {
        $env:TARGET = [string]$DeclaredEvidence.target
        $env:RELAY = [string]$DeclaredEvidence.relay
        $env:PROFILE = $Profile
        $env:ARTIFACT = $ArtifactPath
        $validatorOutput = & node $Validator $EvidencePath
        $validatorExitCode = $LASTEXITCODE
    } finally {
        if ($null -eq $priorTarget) { Remove-Item Env:TARGET -ErrorAction SilentlyContinue } else { $env:TARGET = $priorTarget }
        if ($null -eq $priorRelay) { Remove-Item Env:RELAY -ErrorAction SilentlyContinue } else { $env:RELAY = $priorRelay }
        if ($null -eq $priorArtifact) { Remove-Item Env:ARTIFACT -ErrorAction SilentlyContinue } else { $env:ARTIFACT = $priorArtifact }
        if ($null -eq $priorProfile) { Remove-Item Env:PROFILE -ErrorAction SilentlyContinue } else { $env:PROFILE = $priorProfile }
    }
    if ($validatorExitCode -ne 0) { Fail "$Profile tracked multiplayer evidence revalidation failed" }
    try { $validation = $validatorOutput | Out-String | ConvertFrom-Json }
    catch { Fail "$Profile multiplayer validator returned invalid JSON: $($_.Exception.Message)" }
    if ($validation.success -ne $true -or $validation.schema -ne $DeclaredEvidence.validatorSchema) {
        Fail "$Profile multiplayer validator status/schema does not match the prepared release manifest"
    }
    $validation | ConvertTo-Json -Depth 12 | Out-Host
}

$stagePath = if ([IO.Path]::IsPathRooted($Stage)) { [IO.Path]::GetFullPath($Stage) } else { [IO.Path]::GetFullPath((Join-Path $root $Stage)) }
$targetRoot = [IO.Path]::GetFullPath((Join-Path $root 'port/target')).TrimEnd('\', '/')
if (-not $stagePath.StartsWith($targetRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    Fail "stage must be below port/target: $stagePath"
}
if (-not (Test-Path -LiteralPath $stagePath -PathType Container)) { Fail "stage does not exist: $stagePath" }
Assert-ReleaseSourceState
Assert-ExactAssets $stagePath
foreach ($name in $requiredAssets) {
    if ((Get-Item -LiteralPath (Join-Path $stagePath $name)).Length -le 0) { Fail "empty stage asset: $name" }
}

try { $manifest = Get-Content -LiteralPath (Join-Path $stagePath 'release.manifest.json') -Raw | ConvertFrom-Json }
catch { Fail "release.manifest.json is invalid JSON: $($_.Exception.Message)" }
$head = (git rev-parse --verify HEAD).Trim()
if ($manifest.schemaVersion -ne 4 -or $manifest.tag -ne $tag -or $manifest.sourceHead -ne $head -or
    $manifest.artifactBuildReason -ne 'rebuilt-from-final-main') {
    Fail "release manifest is stale or invalid for HEAD $head"
}
if ($manifest.relay.url -ne 'wss://ellan.site/tunnel' -or
    [string]::IsNullOrWhiteSpace([string]$manifest.relay.targets.'1.21.11') -or
    [string]::IsNullOrWhiteSpace([string]$manifest.relay.targets.'26.2') -or
    [string]::IsNullOrWhiteSpace([string]$manifest.pages.defaultTarget) -or
    [string]::IsNullOrWhiteSpace([string]$manifest.pages.defaultTargets.'1.21.11') -or
    [string]::IsNullOrWhiteSpace([string]$manifest.pages.defaultTargets.'26.2') -or
    $manifest.relay.target -ne $manifest.pages.defaultTarget -or
    $manifest.acceptanceEvidence.'1.21.11.multiplayer'.target -ne $manifest.relay.targets.'1.21.11' -or
    $manifest.acceptanceEvidence.'26.2.multiplayer'.target -ne $manifest.relay.targets.'26.2' -or
    $manifest.acceptanceEvidence.'1.21.11.multiplayer'.relay -ne $manifest.relay.url -or
    $manifest.acceptanceEvidence.'26.2.multiplayer'.relay -ne $manifest.relay.url -or
    $manifest.relay.strictTerrainGate -ne 'passed' -or
    $manifest.acceptanceEvidence.'1.21.11.multiplayer'.status -ne 'passed' -or
    $manifest.acceptanceEvidence.'26.2.multiplayer'.status -ne 'passed') {
    Fail 'release manifest strict multiplayer gate is not passed'
}

$resolved12111Evidence = Resolve-EvidencePath $Multiplayer12111EvidencePath '1.21.11'
$resolved262Evidence = Resolve-EvidencePath $Multiplayer262EvidencePath '26.2'
if ([string]::Equals($resolved12111Evidence, $resolved262Evidence, [StringComparison]::OrdinalIgnoreCase)) {
    Fail '1.21.11 and 26.2 multiplayer evidence must be independent files'
}
$resolved12111EvidenceSha256 = (Get-FileHash -LiteralPath $resolved12111Evidence -Algorithm SHA256).Hash.ToLowerInvariant()
$resolved262EvidenceSha256 = (Get-FileHash -LiteralPath $resolved262Evidence -Algorithm SHA256).Hash.ToLowerInvariant()
if ($resolved12111EvidenceSha256 -eq $resolved262EvidenceSha256) {
    Fail '1.21.11 and 26.2 multiplayer evidence identities must be independent'
}
$multiplayerValidator = Join-Path $root 'tools/check-multiplayer-terrain-evidence.mjs'
if (-not (Test-Path -LiteralPath $multiplayerValidator -PathType Leaf)) { Fail 'tracked multiplayer validator is missing' }
Revalidate-MultiplayerEvidence '1.21.11' $resolved12111Evidence `
    $manifest.acceptanceEvidence.'1.21.11.multiplayer' (Join-Path $stagePath 'Gaius-1.21.11.html') $multiplayerValidator
Revalidate-MultiplayerEvidence '26.2' $resolved262Evidence `
    $manifest.acceptanceEvidence.'26.2.multiplayer' (Join-Path $stagePath 'Gaius-26.2.html') $multiplayerValidator

$sumBytes = [IO.File]::ReadAllBytes((Join-Path $stagePath 'SHA256SUMS'))
if (@($sumBytes | Where-Object { $_ -eq 13 }).Count -ne 0) { Fail 'SHA256SUMS contains CR bytes' }
$sumNames = @()
foreach ($line in ([Text.Encoding]::ASCII.GetString($sumBytes) -split "`n" | Where-Object { $_ -ne '' })) {
    if ($line -notmatch '^([0-9a-f]{64})  ([^/\\]+)$') { Fail "invalid SHA256SUMS record: $line" }
    $expected = (Get-FileHash -LiteralPath (Join-Path $stagePath $Matches[2]) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($expected -ne $Matches[1]) { Fail "checksum mismatch: $($Matches[2])" }
    $sumNames += $Matches[2]
}
$expectedSumNames = @($requiredAssets | Where-Object { $_ -ne 'SHA256SUMS' } | Sort-Object)
if ((@($sumNames | Sort-Object) -join "`n") -ne ($expectedSumNames -join "`n")) {
    Fail 'SHA256SUMS does not cover exactly the seven non-sum assets'
}

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Fail 'GitHub CLI (gh) is required' }
Assert-NoOpenPullRequestsOrIssues
$localTagObject = (git rev-parse --verify "refs/tags/$tag").Trim()
Assert-TagUnchanged $localTagObject 'pre-upload'
$remoteMainLine = @(git ls-remote origin 'refs/heads/main')
if ($LASTEXITCODE -ne 0 -or $remoteMainLine.Count -ne 1) { Fail 'could not verify origin/main' }
$remoteMain = ([string]$remoteMainLine[0] -split "`t")[0]
if ($remoteMain -ne $head) { Fail "origin/main must equal release sourceHead before publish ($remoteMain != $head)" }
$releaseJson = gh release view $tag --repo $Repo --json tagName,isDraft,isPrerelease,assets
if ($LASTEXITCODE -ne 0) { Fail "release does not exist: $Repo $tag" }
try { $release = $releaseJson | ConvertFrom-Json }
catch { Fail "gh release view returned invalid JSON: $($_.Exception.Message)" }
if ($release.tagName -ne $tag) { Fail "release tag mismatch: $($release.tagName)" }

Write-Host "Final release gate PASS (dry-run): exactAssets=8 sourceHead=$head tagObject=$localTagObject" -ForegroundColor Green
if (-not $ExecuteUpload) {
    Write-Host 'No upload, release deletion, tag/ref mutation, or Pages dispatch was performed.' -ForegroundColor Yellow
    exit 0
}

# Clobber the required assets before deleting extras. If upload fails, the
# existing release remains usable instead of first losing unrelated assets.
# No command in this script creates, edits, or moves a tag.
$unexpectedAssets = @($release.assets | Where-Object { $_.name -notin $requiredAssets })
$uploadPaths = @($requiredAssets | ForEach-Object { Join-Path $stagePath $_ })
& gh release upload $tag --repo $Repo @uploadPaths --clobber
if ($LASTEXITCODE -ne 0) { Fail 'exact-eight release upload failed' }
Assert-TagUnchanged $localTagObject 'after upload'
foreach ($asset in $unexpectedAssets) {
    & gh release delete-asset $tag $asset.name --repo $Repo --yes
    if ($LASTEXITCODE -ne 0) { Fail "failed to delete unexpected release asset: $($asset.name)" }
}
Assert-TagUnchanged $localTagObject 'after extra-asset cleanup'

& gh release edit $tag --repo $Repo --title 'Gaius Client 0.1.0' `
    --notes-file (Join-Path $stagePath 'RELEASE-NOTES.md') --latest
if ($LASTEXITCODE -ne 0) { Fail 'release notes/title update failed' }
Assert-TagUnchanged $localTagObject 'after release metadata update'

$remote = gh release view $tag --repo $Repo --json tagName,isDraft,isPrerelease,assets | ConvertFrom-Json
$remoteNames = @($remote.assets | ForEach-Object name | Sort-Object)
if (($remoteNames -join "`n") -ne (@($requiredAssets | Sort-Object) -join "`n")) {
    Fail "post-upload remote asset set is not exact-eight: $($remoteNames -join ', ')"
}
if ($remote.isDraft -eq $true -or $remote.isPrerelease -eq $true) { Fail 'v0.1.0 is not a final published release' }

& (Join-Path $root 'tools/fresh-download-verify-v0.1.0.ps1') -Repo $Repo -Tag $tag -ExpectedSourceHead $head
if ($LASTEXITCODE -ne 0) { Fail 'tracked fresh-download verifier failed' }
Assert-TagUnchanged $localTagObject 'after fresh verification'

# Pages dispatch happens only after the remote exact-eight/fresh-download gate.
$pagesReleaseToken = [Guid]::NewGuid().ToString('N')
$expectedPagesTitle = "Deploy Gaius Pages [$pagesReleaseToken]"
$beforePagesRuns = @(Get-PagesRuns)
Assert-TagUnchanged $localTagObject 'after pre-dispatch Pages run inventory'
$beforePagesIds = @{}
foreach ($run in $beforePagesRuns) { $beforePagesIds[[string]$run.databaseId] = $true }
$pagesDispatchNotBefore = (Get-Date).ToUniversalTime().AddMinutes(-1)
# Pin Pages to the tag published above instead of whichever release is Latest.
& gh workflow run pages.yml --repo $Repo --ref main -f "release_token=$pagesReleaseToken" -f "release_tag=$tag"
if ($LASTEXITCODE -ne 0) { Fail 'GitHub Pages workflow dispatch failed' }
Assert-TagUnchanged $localTagObject 'after Pages dispatch'

$pagesRun = $null
$pagesDeadline = (Get-Date).AddSeconds($PagesTimeoutSeconds)
while ((Get-Date) -lt $pagesDeadline -and $null -eq $pagesRun) {
    $runs = @(Get-PagesRuns)
    Assert-TagUnchanged $localTagObject 'during Pages dispatch run discovery'
    $candidates = @($runs | Where-Object {
        -not $beforePagesIds.ContainsKey([string]$_.databaseId) -and
        $_.event -eq 'workflow_dispatch' -and $_.headSha -eq $head -and
        $_.displayTitle -eq $expectedPagesTitle -and
        [DateTimeOffset]$_.createdAt -ge $pagesDispatchNotBefore
    } | Sort-Object createdAt, databaseId)
    if ($candidates.Count -gt 1) {
        Fail "ambiguous Pages dispatch association for $head (newRuns=$($candidates.databaseId -join ', '))"
    }
    if ($candidates.Count -eq 1) { $pagesRun = $candidates[0] }
    if ($null -eq $pagesRun) { Start-Sleep -Seconds $PagesPollSeconds }
}
if ($null -eq $pagesRun) { Fail "timed out associating Pages token $pagesReleaseToken for $head" }
Write-Host "Associated Pages run: $($pagesRun.databaseId) token=$pagesReleaseToken $($pagesRun.url)" -ForegroundColor Cyan

$pagesDeadline = (Get-Date).AddSeconds($PagesTimeoutSeconds)
while ((Get-Date) -lt $pagesDeadline) {
    $viewJson = & gh run view ([string]$pagesRun.databaseId) --repo $Repo `
        --json databaseId,displayTitle,headSha,event,status,conclusion,createdAt,startedAt,url
    if ($LASTEXITCODE -ne 0) { Fail "could not inspect Pages run $($pagesRun.databaseId)" }
    Assert-TagUnchanged $localTagObject 'during Pages run wait'
    try { $pagesRun = $viewJson | ConvertFrom-Json }
    catch { Fail "gh run view returned invalid JSON: $($_.Exception.Message)" }
    if ($pagesRun.headSha -ne $head -or $pagesRun.event -ne 'workflow_dispatch' -or
        $pagesRun.displayTitle -ne $expectedPagesTitle -or
        [DateTimeOffset]$pagesRun.createdAt -lt $pagesDispatchNotBefore) {
        Fail "associated Pages run identity changed: $($pagesRun.databaseId)"
    }
    if ($pagesRun.status -eq 'completed') { break }
    Start-Sleep -Seconds $PagesPollSeconds
}
if ($pagesRun.status -ne 'completed') { Fail "timed out waiting for Pages run $($pagesRun.databaseId)" }
if ($pagesRun.conclusion -ne 'success') { Fail "Pages run $($pagesRun.databaseId) concluded $($pagesRun.conclusion)" }
Assert-TagUnchanged $localTagObject 'after Pages workflow success'

$pagesVerifier = Join-Path $root 'tools/verify-github-pages-cdp.mjs'
if (-not (Test-Path -LiteralPath $pagesVerifier -PathType Leaf)) { Fail 'tracked GitHub Pages CDP verifier is missing' }
$priorOutput = $env:OUTPUT
$priorProfileRoot = $env:GAIUS_CDP_PROFILE_ROOT
$priorPagesTarget = $env:TARGET
$priorPagesRelay = $env:RELAY
$priorPagesTarget12111 = $env:GAIUS_TARGET_12111
$priorPagesTarget262 = $env:GAIUS_TARGET_262
$priorPagesDefaultTarget = $env:GAIUS_PAGES_DEFAULT_TARGET
$priorPageDefaultTarget12111 = $env:GAIUS_PAGE_DEFAULT_TARGET_12111
$priorPageDefaultTarget262 = $env:GAIUS_PAGE_DEFAULT_TARGET_262
$priorPagesExpectedSha256 = $env:GAIUS_PAGES_EXPECTED_SHA256
$priorPagesSha256RetryMs = $env:GAIUS_PAGES_SHA256_RETRY_MS
$pagesVerifierTempRoot = Join-Path ([IO.Path]::GetTempPath()) ("gaius-pages-publish-" + [Guid]::NewGuid().ToString('N'))
$pagesVerifierStdout = Join-Path $pagesVerifierTempRoot 'stdout.log'
$pagesVerifierStderr = Join-Path $pagesVerifierTempRoot 'stderr.log'
$process = $null
$pagesVerifierTempCleanupFailed = $false
$pagesVerifierFailure = $null
try {
    [void](New-Item -ItemType Directory -Path $pagesVerifierTempRoot -Force)
    $env:OUTPUT = if ([IO.Path]::IsPathRooted($PagesEvidence)) { $PagesEvidence } else { Join-Path $root $PagesEvidence }
    $env:GAIUS_CDP_PROFILE_ROOT = $pagesVerifierTempRoot
    $env:TARGET = [string]$manifest.pages.defaultTarget
    $env:RELAY = [string]$manifest.relay.url
    $env:GAIUS_PAGES_DEFAULT_TARGET = [string]$manifest.pages.defaultTarget
    $env:GAIUS_PAGE_DEFAULT_TARGET_12111 = [string]$manifest.pages.defaultTargets.'1.21.11'
    $env:GAIUS_PAGE_DEFAULT_TARGET_262 = [string]$manifest.pages.defaultTargets.'26.2'
    $env:GAIUS_TARGET_12111 = [string]$manifest.relay.targets.'1.21.11'
    $env:GAIUS_TARGET_262 = [string]$manifest.relay.targets.'26.2'
    # The live Pages bytes must be the staged (and fresh-download verified) 26.2 client.
    $env:GAIUS_PAGES_EXPECTED_SHA256 = (Get-FileHash -LiteralPath (Join-Path $stagePath 'Gaius-26.2.html') -Algorithm SHA256).Hash.ToLowerInvariant()
    # Keep the verifier's CDN retry window inside this script's own verifier timeout, so a slow edge
    # fails with the verifier's recorded attempts instead of being killed without a report.
    $env:GAIUS_PAGES_SHA256_RETRY_MS = [string]([Math]::Max(0, $PagesVerifierTimeoutSeconds - 120) * 1000)
    $node = Get-Command node -ErrorAction Stop
    # Start-Process joins ArgumentList entries into one command line. Preserve
    # the verifier path as one argv item when the checkout path contains spaces.
    $quotedPagesVerifier = '"' + $pagesVerifier.Replace('"', '\"') + '"'
    $process = Start-Process -FilePath $node.Source -ArgumentList @($quotedPagesVerifier) -PassThru `
        -WindowStyle Hidden -RedirectStandardOutput $pagesVerifierStdout -RedirectStandardError $pagesVerifierStderr
    if (-not $process.WaitForExit($PagesVerifierTimeoutSeconds * 1000)) {
        # Kill the complete tree: the verifier owns a Chrome child process.
        # Killing node alone would orphan Chrome and leave its profile locked.
        $taskkill = Get-Command taskkill.exe -ErrorAction SilentlyContinue
        if ($taskkill) {
            & $taskkill.Source /PID $process.Id /T /F 2>&1 | Out-Null
        } else {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
        [void]$process.WaitForExit(10000)
        Get-Content -LiteralPath $pagesVerifierStdout -ErrorAction SilentlyContinue | Out-Host
        Get-Content -LiteralPath $pagesVerifierStderr -ErrorAction SilentlyContinue | Out-Host
        Fail "GitHub Pages Chrome/CDP verifier timed out after $PagesVerifierTimeoutSeconds seconds"
    }
    Get-Content -LiteralPath $pagesVerifierStdout -ErrorAction SilentlyContinue | Out-Host
    Get-Content -LiteralPath $pagesVerifierStderr -ErrorAction SilentlyContinue | Out-Host
    if ($process.ExitCode -ne 0) { Fail "GitHub Pages Chrome/CDP verification failed (exit=$($process.ExitCode))" }
} catch {
    $pagesVerifierFailure = $_
} finally {
    if ($null -eq $priorOutput) { Remove-Item Env:OUTPUT -ErrorAction SilentlyContinue } else { $env:OUTPUT = $priorOutput }
    if ($null -eq $priorProfileRoot) { Remove-Item Env:GAIUS_CDP_PROFILE_ROOT -ErrorAction SilentlyContinue } else { $env:GAIUS_CDP_PROFILE_ROOT = $priorProfileRoot }
    if ($null -eq $priorPagesTarget) { Remove-Item Env:TARGET -ErrorAction SilentlyContinue } else { $env:TARGET = $priorPagesTarget }
    if ($null -eq $priorPagesRelay) { Remove-Item Env:RELAY -ErrorAction SilentlyContinue } else { $env:RELAY = $priorPagesRelay }
    if ($null -eq $priorPagesTarget12111) { Remove-Item Env:GAIUS_TARGET_12111 -ErrorAction SilentlyContinue } else { $env:GAIUS_TARGET_12111 = $priorPagesTarget12111 }
    if ($null -eq $priorPagesTarget262) { Remove-Item Env:GAIUS_TARGET_262 -ErrorAction SilentlyContinue } else { $env:GAIUS_TARGET_262 = $priorPagesTarget262 }
    if ($null -eq $priorPagesDefaultTarget) { Remove-Item Env:GAIUS_PAGES_DEFAULT_TARGET -ErrorAction SilentlyContinue } else { $env:GAIUS_PAGES_DEFAULT_TARGET = $priorPagesDefaultTarget }
    if ($null -eq $priorPageDefaultTarget12111) { Remove-Item Env:GAIUS_PAGE_DEFAULT_TARGET_12111 -ErrorAction SilentlyContinue } else { $env:GAIUS_PAGE_DEFAULT_TARGET_12111 = $priorPageDefaultTarget12111 }
    if ($null -eq $priorPageDefaultTarget262) { Remove-Item Env:GAIUS_PAGE_DEFAULT_TARGET_262 -ErrorAction SilentlyContinue } else { $env:GAIUS_PAGE_DEFAULT_TARGET_262 = $priorPageDefaultTarget262 }
    if ($null -eq $priorPagesExpectedSha256) { Remove-Item Env:GAIUS_PAGES_EXPECTED_SHA256 -ErrorAction SilentlyContinue } else { $env:GAIUS_PAGES_EXPECTED_SHA256 = $priorPagesExpectedSha256 }
    if ($null -eq $priorPagesSha256RetryMs) { Remove-Item Env:GAIUS_PAGES_SHA256_RETRY_MS -ErrorAction SilentlyContinue } else { $env:GAIUS_PAGES_SHA256_RETRY_MS = $priorPagesSha256RetryMs }
    for ($attempt = 0; $attempt -lt 20 -and (Test-Path -LiteralPath $pagesVerifierTempRoot); $attempt++) {
        Remove-Item -LiteralPath $pagesVerifierTempRoot -Recurse -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $pagesVerifierTempRoot) { Start-Sleep -Milliseconds 250 }
    }
    if (Test-Path -LiteralPath $pagesVerifierTempRoot) {
        $pagesVerifierTempCleanupFailed = $true
    }
}
if ($pagesVerifierTempCleanupFailed) {
    $priorFailure = if ($pagesVerifierFailure) { "; priorFailure=$($pagesVerifierFailure.Exception.Message)" } else { '' }
    Fail "Pages verifier temporary directory cleanup failed: $pagesVerifierTempRoot$priorFailure"
}
if ($pagesVerifierFailure) { throw $pagesVerifierFailure }
Assert-TagUnchanged $localTagObject 'after Pages Chrome/CDP verification'
Write-Host "FINAL RELEASE v0.1.0 complete: exact-eight, fresh-download, Pages run $($pagesRun.databaseId) token=$pagesReleaseToken, and Pages CDP PASS." -ForegroundColor Green
