# Local prerelease publishing

Use the local-only PowerShell path to build both profiles, stage portable
clients and manifests, write SHA-256 checksums, and upload a GitHub
prerelease with `gh`. GitHub Actions is not involved.

```powershell
Set-Location D:\Projects\Gaius-migration-2026-08-14
gh auth status
.\tools\build-and-publish-prerelease.ps1 -AllowDirty
```

The default tag is `v<VERSION>-pre.YYYYMMDD-HHMMSS`. `-Tag` makes retries
address the same release, while `-SkipBuild` reuses already verified profile
outputs. The command refuses a dirty tree unless `-AllowDirty` is explicit and
records that fact in `prerelease.manifest.json`. Staging is kept under
`port/target/local-prerelease/<tag>/` and is not committed.
