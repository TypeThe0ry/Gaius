# TeaVM platform gap for the official 1.21.11 client

## Confirmed baseline

The official Minecraft Java Edition 1.21.11 client has been:

1. downloaded from Mojang and SHA-1 verified;
2. remapped to Mojang names with AutoRenamingTool 2.0.18;
3. loaded by TeaVM 0.15.0 from the real
   `net.minecraft.client.main.Main` entry point;
4. analyzed to the end of TeaVM's reachable call graph without an out-of-memory
   failure.

The remapped input contains 10,291 classes and Java 21 class files. This is not
an older Eaglercraft client or a rewritten game implementation.

## First blocking layer

The first complete TeaVM run reached the Minecraft bootstrap and then failed on
missing browser/JDK compatibility surfaces. The largest groups are:

- Java concurrency: `CompletableFuture`, executors, blocking queues, atomic
  arrays, locks and read/write locks;
- Java NIO: `FileChannel`, filesystem and file-store operations;
- JVM internals and native loading: `sun.misc.Unsafe`, `System.load`,
  `System.loadLibrary`, and `UnsatisfiedLinkError`;
- reflection and runtime metadata: class loaders, generic reflection, modules,
  proxies, stack walking, and shutdown hooks;
- security and hashing: `MessageDigest`, certificates, authenticators;
- desktop-only monitoring and process APIs.

Run `./port/scripts/build-teavm.sh` to regenerate the exact occurrence counts
and first reachable call sites in `port/target/teavm-gap.md`.

## Public Eagler TeaVM fork result

`Eaglercraft-TeaVM-Fork/eagler-teavm` branch `eagler-r3` was inspected at
commit `c0bac887ae63dba929ca546f07394eca3bd09b80`. Its class library includes
basic concurrent collections and atomics, but does not provide the modern
surfaces above. Substituting that fork alone therefore cannot compile the
official 1.21.11 client.

## Evidence from the uploaded eag26 artifact

`eag26-single(2).html` contains TeaVM runtime metadata for modern classes such
as `CompletableFuture`, `ReentrantLock`, `AtomicIntegerArray`, `StackWalker`,
`FileChannel`, and `MessageDigest`. It also contains explicit browser fallback
messages including:

- Unsafe is unavailable under TeaVM;
- dynamic proxies are unavailable under TeaVM;
- NIO selectors are unavailable pending WebSocket transport;
- JCA key materialization is unsupported in the browser runtime.

This proves the artifact was compiled with an additional compatibility and
replacement layer that is not present in the public `eagler-r3` class library.
The single-file HTML does not contain Java source paths or source maps, so that
layer cannot be reliably reconstructed as source from the artifact alone.

## Required implementation direction

The next code work must be a source-level TeaVM class-library extension plus
targeted replacements for desktop libraries. It must preserve the official
Minecraft client bytecode as the game implementation. The platform work should
be split in this order:

1. deterministic single-thread concurrency and futures;
2. browser filesystem and NIO facade backed by IndexedDB/OPFS;
3. native/Unsafe removal in Netty, JOML, compression and image/audio paths;
4. resource loading and reflection substitutions;
5. GLFW/OpenGL/OpenAL replacement with browser APIs;
6. integrated-server worker separation for single-player.

## First class-library extension

The initial single-thread lock implementation now supplies TeaVM versions of
`Lock`, `Condition`, `ReadWriteLock`, `ReentrantLock`, and
`ReentrantReadWriteLock`. These classes live under TeaVM's existing
`org.teavm.classlib.java` substitution namespace; no Minecraft gameplay class
is replaced.

After this extension, all four original missing lock classes and Guava's
inherited `lock`, `tryLock`, and `unlock` method errors disappeared. The newly
reachable graph then exposed additional scheduler, SSL, JNA, and atomic-array
surfaces. Gap totals are therefore compared by removed symbols and newly
reachable symbols, not only by a raw error count.

The verified post-lock baseline was 345 missing-symbol occurrences across 115
unique symbols, with no remaining lock-class or lock-method errors.

Atomic reference, integer, and long arrays have now also been added and
verified. Their 31 original class errors and all atomic-array method errors are
gone. The current complete analysis reports 338 occurrences across 118 unique
symbols; the larger unique count comes from the deeper Guava and Log4j call
graph that became reachable. The next high-value work is futures, executors,
queues, and targeted removal of Unsafe/native desktop paths.

The subsequent concurrency pass added deterministic browser implementations
for queues, concurrent deque/map types, Future/CompletionStage,
CompletableFuture, executor services, schedulers, and ForkJoinPool. The latest
complete analysis is 300 occurrences across 104 unique symbols. Native desktop
surfaces now dominate the remaining backlog.
