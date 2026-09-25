# Releasing Gaius

Gaius keeps source and the runnable browser release in the same repository.
Large browser bundles are tracked through Git LFS, which keeps GitHub's normal
Git object store free of files above its 100 MiB limit.

The public release version is stored in the root `VERSION` file. Keep the
RelayNode package and server-plugin project version aligned with it. Tags use
the form `v<version>`; for example, `VERSION=0.1.0` produces tag `v0.1.0`.

Before cloning or updating a release checkout, run:

```sh
git lfs install
git lfs pull
```

## Build and Verify

Run the fast source checks before compiling:

```sh
node tools/check-release-metadata.mjs
node tools/check-relay-registry.mjs
node tools/check-singleplayer-lifecycle.mjs
npm ci --prefix apps/bridge
npm run smoke --prefix apps/bridge
npm run smoke:profiles --prefix apps/bridge
```

The singleplayer lifecycle checks cover storage, reload hydration, Worker
bootstrap, MessagePort ownership and retirement, and profile isolation. They
are source-level fixtures; compiled Worker and browser results are separate.
For the public multiplayer transport probe, provide the target explicitly at
runtime. Never store the target IP or private origin address in the repository:

```sh
for profile in 1.21.11 26.2; do
  GAIUS_PUBLIC_RELAY_TARGET="$AUTHORIZED_TARGET_HOST:$AUTHORIZED_TARGET_PORT" \
    GAIUS_PUBLIC_RELAY_MINECRAFT_VERSION="$profile" \
    npm run smoke:public --prefix apps/bridge
done
```

These probes check STATUS, target attestation, and tunnel release, not LOGIN
or PLAY. Keep their actual scope in release notes.

The final `v0.1.0` prepare gate accepts independent multiplayer targets for
each compiled profile while keeping one audited RelayNode. Defaults remain the
public probe above; override only when the saved Chrome/CDP evidence was
captured against different profile-specific servers:

```powershell
./tools/prepare-final-release-v0.1.0.ps1 `
  -Singleplayer12111Evidence artifacts/file-entry-1.21.11.json `
  -Singleplayer262Evidence artifacts/file-entry-26.2.json `
  -Multiplayer12111Evidence artifacts/join-terrain-1.21.11.json `
  -Multiplayer262Evidence artifacts/join-terrain-26.2.json `
  -Multiplayer12111Target 'legacy.example:25565' `
  -Multiplayer262Target 'modern.example:25565' `
  -PagesDefaultTarget 'example.invalid:25565'
```

The two multiplayer targets are bound into each evidence declaration and into
`release.manifest.json` under `relay.targets`. They are independent from the
public launcher defaults under `pages.defaultTarget` and
`pages.defaultTargets`; `relay.target` is retained only as an alias for the
home-page default. Publish and fresh-download gates revalidate both sets of
bindings. The Pages CDP gate checks the displayed default on the home and each
profile page, then injects the matching multiplayer evidence target when it
verifies each release link.

From a clean source checkout, build each supported Minecraft profile in its
own state and output roots. The wrapper never changes `port/config.json` and
does not reuse the legacy shared `port/target`, `port/work/overlays`, or
`port/web/dist` roots:

```sh
for profile in 1.21.11 26.2; do
  export GAIUS_VERSION_PROFILE_PATH="versions/${profile}.json"
  export GAIUS_BUILD_ROOT="port/target/${profile}"
  export GAIUS_OVERLAY_DIRECTORY="port/work/overlays/${profile}"
  export GAIUS_DIST_DIRECTORY="port/web/dist/${profile}"
  ./port/scripts/fetch-version.sh
  ./port/scripts/remap-client.sh
  bash port/scripts/build-version-release.sh "$profile"
  python3 port/scripts/quick-check.py
  GAIUS_SMOKE_MAX_GAMEPLAY_STALL_MS=500 \
    node port/scripts/singleplayer-worker-runtime-smoke.mjs
done
env -u GAIUS_BUILD_ROOT -u GAIUS_OVERLAY_DIRECTORY -u GAIUS_DIST_DIRECTORY \
  GAIUS_VERSION_PROFILE_PATH=versions/1.21.11.json \
  ./port/mvnw -B -ntp -f apps/server-plugin/pom.xml package
```

The `1.21.11` profile requires JDK 21 and `26.2` requires JDK 25 or newer;
set `GAIUS_JAVA_HOME` or `JAVA_HOME` to the matching JDK before each loop
iteration. The commands above are release gates to run, not claims about
every checkout. Record their actual results in the release notes or release
checklist. Release bundles are compiled and verified on the maintainer's
machine. GitHub Actions does not compile TeaVM release artifacts.

For a lightweight source-only hygiene check, run:

```sh
git diff --check
git status --short
git lfs ls-files
./tools/check-lfs.sh
```

For a browser release, serve each `port/web/dist/<profile>/` directory locally
as the corresponding `/dist/<profile>/` launch in a real Chrome session. Enter
a new single-player world for both profiles, let terrain load, move through at
least one chunk boundary, and confirm sound, visual rendering, block
interaction, and settings. For multiplayer, verify both the plugin path and
the RelayNode path for each supported protocol when those endpoints are
available. Save screenshots of the actual main menu, single-player world, and
multiplayer flow for the release documentation; do not use placeholders or
mock UI captures.

## Publish Artifacts

The local release build generates the following profile-scoped files. They are
not automatically added to Git; if a release maintainer deliberately checks
them in, `port/web/dist/**` is covered by the repository's Git LFS attributes:

| Artifact | Use |
| --- | --- |
| `port/web/dist/<profile>/Gaius.html` | Downloadable, browser-local single-player package |
| `port/web/dist/<profile>/Gaius.html.gz` | Optional compressed portable payload |
| `port/web/dist/<profile>/Gaius.manifest.json` | Profile, protocol, input, and artifact identity record |
| `port/web/dist/<profile>/` | Static-host deployment input for that profile's launcher |
| `apps/server-plugin/target/gaius-server-plugin-<version>.jar` | Optional Paper bridge plugin |

For the version in `VERSION`, stage both profile assets outside Git's tracked source
tree, then create a checksum file:

```sh
release_dir="port/target/release-v$(tr -d '[:space:]' < VERSION)"
mkdir -p "$release_dir"
for profile in 1.21.11 26.2; do
  cp "port/web/dist/${profile}/Gaius.html" \
    "$release_dir/Gaius-${profile}.html"
  cp "port/web/dist/${profile}/Gaius.manifest.json" \
    "$release_dir/Gaius-${profile}.manifest.json"
done
cp "apps/server-plugin/target/gaius-server-plugin-$(tr -d '[:space:]' < VERSION).jar" "$release_dir/"
source port/scripts/version-profile.sh
(cd "$release_dir" && for artifact in *; do
  [[ "$artifact" == SHA256SUMS ]] && continue
  printf '%s  %s\n' "$(gaius_sha256_file "$artifact")" "$artifact"
done > SHA256SUMS)
```

For the already-published `v0.1.0` tag, do not recreate, force-update, or push
the tag. Commit the release-gate changes on `main`, push `main`, prepare an
exact-eight stage, then run the tracked publisher first without
`-ExecuteUpload` and review its dry-run result:

```powershell
./tools/publish-final-release-v0.1.0.ps1 `
  -Multiplayer12111EvidencePath artifacts/join-terrain-1.21.11.json `
  -Multiplayer262EvidencePath artifacts/join-terrain-26.2.json

./tools/publish-final-release-v0.1.0.ps1 `
  -Multiplayer12111EvidencePath artifacts/join-terrain-1.21.11.json `
  -Multiplayer262EvidencePath artifacts/join-terrain-26.2.json `
  -ExecuteUpload
```

The publisher requires a clean tracked `main`, `origin/main == HEAD`, no open
pull requests or issues, an unchanged local/remote tag object, schema-v4
manifest provenance, and the exact eight assets. It clobbers the eight named
release assets, removes extras, performs a fresh download verification, then
dispatches and verifies the uniquely-bound Pages run. Untracked local evidence
and build output are permitted and remain outside the source provenance check.

For a genuinely new version only, create a new annotated tag after all gates
pass. Never repoint an existing published tag.

Download every uploaded asset to a fresh directory, verify it against the
published `SHA256SUMS`, and repeat the Chrome launch and single-player and
multiplayer acceptance checks on the downloaded HTML files. Never move an
already published release tag to include later fixes; create a new version
instead.

The release page should identify the browser package, optional plugin, SHA256
checksums, supported client version, and any known runtime limitations.

Before publishing, review the provenance and redistribution rights for every
embedded Minecraft asset and client-derived file. The repository's source
policy does not by itself grant permission to redistribute generated game
artifacts.

## Keep Git Pushable

`.gitattributes` routes release files through Git LFS. It cannot repair a large
ordinary Git blob that already exists in a branch ancestor. Start from the
clean LFS-backed mainline for new work; do not force-push `main` as a cleanup
shortcut.

Run these checks before opening a pull request:

```sh
git diff --check
git status --short
git lfs ls-files
./tools/check-lfs.sh
git rev-list --objects origin/main..HEAD \
  | git cat-file --batch-check='%(objecttype) %(objectsize) %(rest)' \
  | awk '$1 == "blob" && $2 > 100000000 { print }'
```

The final command must produce no output: every oversized release file should
be an LFS pointer in Git, visible through the preceding `git lfs ls-files`.
