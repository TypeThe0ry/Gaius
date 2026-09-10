# Minecraft TeaVM browser port

This directory is the active Gaius implementation. The primary profile is
Minecraft `26.2`; the `1.21.11` profile remains supported for compatibility.

## Rules

- Each profile's game implementation comes from its official Minecraft client.
- Browser adaptations are maintained as replacement/patch classes.
- No rewritten TypeScript world simulation is used.
- Official game JARs, mappings, libraries, and assets stay under `port/work/`
  and are never committed.
- Builds must fail on unresolved TeaVM errors; errors are not hidden with
  `|| true` or `stopOnErrors=false`.

## Stage A: acquire and remap

Requirements: JDK 21 for `1.21.11`, JDK 25 or newer for `26.2`, plus `curl`,
`jq`, `unzip`, and `shasum`.

```sh
./port/scripts/fetch-version.sh
./port/scripts/remap-client.sh
```

Outputs are profile-scoped (the example below selects `1.21.11`):

```text
port/work/1.21.11/client-obfuscated.jar
port/work/1.21.11/client-mappings.txt
port/work/1.21.11/client-named.jar
port/work/1.21.11/libraries/
port/work/1.21.11/classpath.txt
```

Select the profile explicitly before acquiring inputs for another version:

```sh
GAIUS_VERSION_PROFILE_PATH=versions/26.2.json ./port/scripts/fetch-version.sh
GAIUS_VERSION_PROFILE_PATH=versions/26.2.json ./port/scripts/remap-client.sh
```

`client-named.jar` is the actual official client bytecode remapped with Mojang's
official mappings. It is the input to every later browser build.

## Stage B: first TeaVM analysis

No system Maven installation is required. The checked-in `port/mvnw` bootstrap
downloads and verifies Apache Maven under the ignored `port/work/tools/`
directory.

The full client analysis currently uses a 20 GB maximum Java heap. A machine
with at least 32 GB physical memory is recommended. The reachable graph grows
as compatibility classes are added, so the compiler requires more memory than
the initial unpatched baseline.

```sh
./port/scripts/build-teavm.sh
```

Before every TeaVM run, `build-overlays.sh` creates a patched classlib JAR under
`port/work/overlays/`. This is required for extending existing TeaVM classes
such as `java.util.UUID`; adding a duplicate class at the end of the Maven
classpath cannot override TeaVM's own classlib.

The first run intentionally uses the real `net.minecraft.client.main.Main` and
`stopOnErrors=true`. It is expected to fail until browser substitutions exist.
The error report in `port/target/teavm-build.log` is the authoritative platform
port backlog. Every run also writes:

```text
port/target/teavm-gap.json
port/target/teavm-gap.md
```

The build keeps TeaVM's original non-zero exit status. The reports do not turn
a failed compilation into a successful one.

## Stage C: browser platform overlays

The next build stage will compile classes under:

```text
port/src/main/java/
```

Those classes shadow desktop-only surfaces in the named client JAR. The first
bootstrap target is not a fake game screen: it is the real
`net.minecraft.client.main.Main`, stopped only by explicit unsupported platform
calls so each missing subsystem can be replaced in order.

## Runtime architecture

The release client is served from `port/web/dist/<profile>/`. Single-player
launches the official server entry point in
`singleplayer-server-worker.js`; the client and server communicate through a
paired `MessageChannel`, while IndexedDB persists world data between Worker
sessions. Use `build-version-release.sh <profile>` so generated target,
overlay, and dist state cannot cross profiles.

`build-teavm-release.sh` also writes a self-contained `Gaius.html`. The portable
file reconstructs the compressed client and Wasm as browser-local Blob URLs and
passes the compressed official server payload into its Worker. Single-player
therefore remains frontend-only and works without a hosted Gaius service.

External Java servers remain unmodified. `apps/bridge/dist/main.js` is a raw,
backpressured WebSocket-to-TCP tunnel with SRV resolution and constrained HTTP
proxies for Mojang authentication, skins, Realms, and resource packs. The
resource-pack proxy retries interrupted upstream bodies before exposing a
response to Chrome, publishes the verified byte length, cancels orphaned
downloads, and keeps a bounded short-lived disk cache for repeated joins;
Mojang's
`blockedservers` request is proxied as well, so it does not depend on cross-origin
access from the page. The
online-mode smoke starts an official plugin-free `server.jar`, performs the
RSA/AES session handshake against a controlled session fixture, and requires
PLAY plus real chunk packets before passing.

RelayNode discovery also negotiates the node's per-target TCP timeout. The
browser keeps the WebSocket alive long enough for an SRV target to time out and
fall back to the entered host without leaving an orphaned TCP attempt when the
player cancels.

Remote connection order is: optional same-host Paper plugin, then configured
and default RelayNodes ranked for the requested normalized `host:port` by
active target affinity, recent reachability, free capacity, and configured
priority. Every status ping or join remains an isolated, short-lived TCP
tunnel. The browser accepts repeated `bridge` query parameters,
`globalThis.__gaiusBridgeUrls`, or the `gaius.bridgeNodes` local-storage JSON
array, allowing already-deployed relay capacity to be contributed independently
of the client. Short discovery caches avoid repeating an unavailable plugin
probe and RelayNode manifest fetch between a status ping and its join; they do
not share the underlying protocol stream.

For unencrypted `1.21.11` and `26.2` traffic, RelayNode also tracks
server-initiated `PLAY -> CONFIGURATION -> PLAY` cycles. Synthetic
browser-stall ticks are disabled throughout reconfiguration, preventing stale
PLAY packets from being sent into the configuration protocol after a plugin
changes server state.
