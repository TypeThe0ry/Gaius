# Offline version bring-up

```bash
node port/tools/bringup/gaius-bringup.mjs <id> --fast
node port/tools/bringup/gaius-bringup.mjs <id> --fast --from <previous-id> --out <output-directory>
GAIUS_BRINGUP_TEST_OUT=<disposable-directory> node --test port/tools/bringup/bringup.test.mjs
```

Inputs must already exist. Fast mode never fetches, installs a profile, refreshes
reviewed locks, runs TeaVM or starts Chrome. Reports and all scratch evidence go
under `--out`, defaulting to `port/mc/profiles/<id>`. Exit codes are 0 (automatic),
1 (review, no blockers), and 2 (blocking). The overall child-process budget is
20 minutes; the report records elapsed time, command logs and artifact identities.

`--draft-only` is an offline bootstrap aid, not a completed fast check. `--full`
is a guarded handbook entry point: it refuses automatic execution and points to
the reviewed full build/acceptance procedure in `docs/new-version.md`. This is
intentional: a draft is not a release profile and a fast pass cannot authorize
full builds, browser use or promotion on a shared machine.

`snapshot-bringup.sh` and `snapshot-profile.mjs` are CI-only. The scheduled
workflow is disabled unless `GAIUS_SNAPSHOT_BRINGUP_ENABLED` is exactly `true`.
See the handbook for offline cache prerequisites, decision rules and proof runs.
