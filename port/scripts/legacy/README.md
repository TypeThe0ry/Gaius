# Frozen verification scripts

These scripts assert the structure of Gaius's patches through source text, javap output and
version-specific class shapes. Per Minecraft version they grew into a large share of each
migration, so they are frozen here (maint/D14 3.1 and 3.3, M1 item 8):

- `quick-check.py` and its 26.3 domain modules `quickcheck/profile_263_<domain>.py`;
- the 26.3 one-off smokes `*263*.mjs`.

Rules:

- Keep them passing: when a patch changes on purpose, adjust the assertion it breaks.
- Add no file here and no new structural check to these files. A new check belongs to the patch
  that it verifies (the patch framework's `verify()`), or to a profile-parameterized script under
  `port/scripts/` that names no Minecraft version.
- `check-frozen.py` (CI `repository-guard`, job `wasmgc-gates`) fails on a file added here and on
  a new version-named script elsewhere in `port/scripts`.
- The P3 version-literal lint (`port/scripts/quickcheck/lint_version_literals.py`) skips this
  directory.

`minecraft-263-singleplayer-cdp.mjs` and `minecraft-263-multiplayer-cdp.mjs` stay in
`port/scripts/` for now: they are the active singleplayer and multiplayer acceptance harnesses,
which become `--profile` parameterized acceptance scripts rather than frozen ones.
