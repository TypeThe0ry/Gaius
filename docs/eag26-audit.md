# eag26 artifact and upstream audit

Assessment date: 2026-06-19

## Uploaded artifact

The repository contains a 64,258,197-byte single-file build:

```text
eag26-single(2).html
```

It identifies itself as `Minecraft 26.1.2 — eag26 (single file)`. Static
inspection found:

- TeaVM-generated JavaScript and TeaVM runtime metadata;
- Java 21 runtime properties;
- modern Minecraft client and integrated-server classes;
- WebGPU device and pipeline adapters;
- converted WGSL core shaders and a shader manifest;
- embedded game resources, sounds, structures, recipes, and textures;
- IndexedDB-backed filesystem code;
- Worker roles for the client, integrated server, and mesh work;
- a small embedded `rustnoise.wasm` module used by world generation;
- no source map and no embedded Java sources.

This is strong evidence that a modern official-client source port is possible.
It is not enough to reproduce the build by itself: TeaVM output is optimized,
renamed, tree-shaken JavaScript.

## GitHub search results

The closest public source repository found is:

- <https://github.com/spxmiguel/minecraft-web>

Its history begins as a Minecraft 26.1.2 TeaVM experiment. The public tree
contains:

- a Maven/TeaVM build;
- approximately 165 LWJGL and Blaze3D replacement classes;
- browser launcher, asset loader, and WebGL bridge code.

However, the repository's current playable output was replaced with
EaglercraftX 1.12.2. Its own README and CI comments state that its original
26.1.2 build did not reach a runnable client. The uploaded eag26 artifact is
substantially more advanced than any public commit in that repository.

Other repositories found under names such as “Eaglercraft 1.21.11” are launch
pages, old-client modifications, server bundles, or incomplete demos. They are
not credible source bases for this project.

## Correct Gaius build model

```text
Mojang 1.21.11 client.jar (obfuscated)
  + Mojang official client mappings
  + official dependency JARs
       |
       v
NeoForged AutoRenamingTool
       |
       v
Mojang-named 1.21.11 client bytecode
       |
       + browser replacement classes
       |   LWJGL / GLFW / OpenGL / OpenAL
       |   filesystem / threads / sockets / HTTP
       |   authentication / clipboard / window
       |
       v
TeaVM 0.15 closed-world compiler
       |
       v
JavaScript or WasmGC + browser runtime
```

The original client remains the game implementation. Gaius only replaces
platform facilities that cannot exist in a browser and applies narrowly scoped
compatibility patches.

## Current hard dependency

To reproduce the uploaded eag26 build instead of independently rebuilding its
platform layer, the corresponding source repository or source archive is
required. In particular, the missing pieces include its WebGPU Java bindings,
WGSL conversion pipeline, Worker integrated-server patches, browser filesystem,
and Rust noise source.

