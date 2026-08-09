# Changelog

All notable changes to Gaius are documented here.

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
