# Changelog

All notable changes to Gaius are documented here.

## [0.1.0] - 2026-09-05

### Release

- Prepare the dual-profile release for Minecraft Java `1.21.11` (protocol
  `774`, JDK 21) and `26.2` (protocol `776`, JDK 25), with independent browser
  packages, manifests, checksums, and the optional Paper bridge plugin.
- Keep `t40.sjcmc.cn:14803` as the external multiplayer test target through
  `wss://ellan.site/tunnel`. Status/attestation and lease-release checks are
  transport evidence; they do not assert a complete LOGIN/PLAY session.

### Fixed

- Generate the launcher's displayed release version from `VERSION` instead
  of the stale `0.0.1` label, and include it in the build input identity.
- Initialize the real inbound scheduler in the local MessagePort lifecycle
  smoke, matching the production channel constructor. Inspect asynchronous
  failure diagnostics without racing automatic closed-channel retirement.
- Exercise the tracked launcher template in source checks instead of reading
  a generated LFS bundle that is only a pointer in lightweight CI checkouts.
- Align the compiled Netty pump check with its progress-returning boolean
  signature and tightened 256 KiB byte budget. The obsolete void/1 MiB check
  incorrectly rejected both successfully compiled `v0.0.3` profiles.
- Run singleplayer storage, world reload, Worker bootstrap, MessagePort
  ownership, and profile isolation regressions before costly release builds.
- Validate the requested release tag before compilation and retain TeaVM
  build diagnostics on failed CI runs.

## [0.0.3] - 2026-09-05

### Added

- Minecraft Java `26.2` as the primary browser client profile while retaining
  the `1.21.11` compatibility profile.
- Profile-scoped dual-version release builds, artifact identity records, and
  release checks for both portable clients.

### Improved

- Multiplayer packet scheduling, bounded browser inbound and outbound drains,
  RelayNode frame handling, stale-owner isolation, reconnect cleanup, and
  browser-side diagnostics.

### Release Notes

- The `v0.0.3` release is produced by the dual-profile GitHub Actions matrix and
  includes a profile manifest, SHA256 checksums, and the optional Paper plugin.
- The browser client remains experimental; runtime compatibility still depends
  on the selected Minecraft server, Chrome/Chromium, and an available relay or
  compatible server plugin.

## [0.0.1] - 2026-08-09

### Added

- First public release of Gaius Client for Minecraft Java 1.21.11.
- A downloadable browser client package at `port/web/dist/Gaius.html`.
- An optional Paper server plugin for servers that want a direct Gaius WebSocket bridge.
- RelayNode and public relay-registry support for temporary WebSocket-to-TCP translation when a target server does not have the plugin.
- Git LFS tracking for the generated browser release and other intentionally large bundles.

### Improved

- Browser startup, single-player integrated-server handoff, world-generation scheduling, and packet fairness paths.
- Browser audio through the Web Audio/OpenAL bridge.
- Browser rendering and resource setup, including texture atlas and generated asset handling.
- Repository checks and release documentation for source builds, LFS-backed artifacts, Chrome verification, and checksums.

### Release Notes

- The browser client is experimental and is currently validated with Google Chrome.
- Multiplayer access depends on a compatible Gaius server plugin or an available RelayNode/public relay service. This release does not guarantee compatibility with every Java server or protocol configuration.
- Generated client and game assets may have separate redistribution requirements. Review the repository's licensing and attribution information before redistributing artifacts.
