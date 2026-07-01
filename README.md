# Gaius

Gaius is an experimental browser port of the **real Minecraft Java Edition
1.21.11 client**.

The project does not reimplement Minecraft gameplay in TypeScript and does not
backport modern content onto an old Eaglercraft client. The build starts from
Mojang's official 1.21.11 client JAR and official mappings, remaps the original
bytecode to Mojang names, overlays browser platform replacements, and feeds the
result to TeaVM.

The uploaded [`eag26-single(2).html`](eag26-single(2).html) is retained as a
runtime reference. Inspection shows that it contains a TeaVM-compiled modern
Minecraft client with Java 21 semantics, WebGPU, WGSL shaders, an integrated
server, IndexedDB storage, and a Rust noise WebAssembly helper. It is a compiled
artifact, not a reproducible source tree.


## Run single-player now

The reproducible 1.21.11 TeaVM build is still blocked on downloading Mojang and
Maven inputs in some environments, but the repository includes the audited
`eag26-single(2).html` reference build. To run its single-player mode locally:

```sh
./port/scripts/serve-singleplayer.sh
```

Then open `http://127.0.0.1:8080/port/web/singleplayer/` in a desktop browser
with WebGPU support. The launcher opens the checked-in single-file build with
`?localserver` so world simulation runs in-page when workers cannot be used.

## Start here

```sh
./port/scripts/fetch-version.sh
./port/scripts/remap-client.sh
```

See [the port build notes](port/README.md) and
[the eag26 audit](docs/eag26-audit.md).

