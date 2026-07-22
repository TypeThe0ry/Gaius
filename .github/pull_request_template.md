## Summary

Describe the player-visible or operator-visible change.

## Scope

- [ ] Browser client or rendering
- [ ] Single-player Worker or local persistence
- [ ] Multiplayer bridge or plugin
- [ ] Build, tooling, or documentation

## Verification

- [ ] `git diff --check`
- [ ] Focused smoke test or build command listed below
- [ ] Chrome runtime verification where the change affects gameplay

Commands and results:

```text
Paste the exact command and concise result here.
```

## Artifact Policy

- [ ] No fetched Minecraft inputs, secrets, `target/`, or local world data are included.
- [ ] Browser release changes are rebuilt, stored only in the approved LFS paths, and pass `./tools/check-lfs.sh`.
