# Official Minecraft 1.21.11 TeaVM port

This directory is the active Gaius implementation.

## Rules

- The game implementation comes from the official 1.21.11 client.
- Browser adaptations are maintained as replacement/patch classes.
- No rewritten TypeScript world simulation is used.
- Official game JARs, mappings, libraries, and assets stay under `port/work/`
  and are never committed.
- Builds must fail on unresolved TeaVM errors; errors are not hidden with
  `|| true` or `stopOnErrors=false`.

## Stage A: acquire and remap

Requirements: JDK 21, `curl`, `jq`, `unzip`, and `shasum`.

```sh
./port/scripts/fetch-version.sh
./port/scripts/remap-client.sh
```

Outputs:

```text
port/work/1.21.11/client-obfuscated.jar
port/work/1.21.11/client-mappings.txt
port/work/1.21.11/client-named.jar
port/work/1.21.11/libraries/
port/work/1.21.11/classpath.txt
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

The release client is served from `port/web/dist/`. Single-player launches the
official server entry point in `singleplayer-server-worker.js`; the client and
server communicate through a paired `MessageChannel`, while IndexedDB persists
world data between Worker sessions.

`build-teavm-release.sh` also writes a self-contained `Gaius.html`. The portable
file reconstructs the compressed client and Wasm as browser-local Blob URLs and
passes the compressed official server payload into its Worker. Single-player
therefore remains frontend-only and works without a hosted Gaius service.

External Java servers remain unmodified. `apps/bridge/dist/main.js` is a raw,
backpressured WebSocket-to-TCP tunnel with SRV resolution and constrained HTTP
proxies for Mojang authentication, skins, Realms, and resource packs. The
online-mode smoke starts an official plugin-free `server.jar`, performs the
RSA/AES session handshake against a controlled session fixture, and requires
PLAY plus real chunk packets before passing.

Remote connection order is: optional same-host Paper plugin, configured relay
nodes, then the default relay. Every status ping or join is an isolated,
short-lived tunnel. The browser accepts repeated `bridge` query parameters,
`globalThis.__gaiusBridgeUrls`, or the `gaius.bridgeNodes` local-storage JSON
array, allowing relay capacity to be contributed independently of the client.

## Reference artifact

The root `eag26-single(2).html` proves that a more advanced 26.1.2 platform layer
exists. It cannot be treated as source code. If its corresponding Java, Python,
Rust, and build files become available, they should be imported as a reference
implementation after checking provenance and redistribution terms.
