# Offline version-profile cache checks

`node port/scripts/check-version-profile.mjs --all` is read-only and offline unless `--remote`
is explicitly supplied. A cached asset index, when present, must hash to the SHA-1 named by its
cached `version.json`; the metadata must then match the profile's pinned revision. Matching an
index id is insufficient. Cache corruption and a coherent but differently pinned revision are
reported separately, with the metadata, profile and index paths and the actual hashes.

## Cache inspected on 2026-10-08

The `1.21.11` profile records Mojang asset index `29` with SHA-1
`4043c34fdd5d06b2ac1f6f46c5b91a4ac16e3452`. That pin entered repository history in `6d6c53d2`
on 2026-09-29. `docs/feasibility.md` records 4,590 objects and 446,134,512 total asset bytes for
the earlier official metadata snapshot.

The local `port/work/1.21.11/version.json` instead names
`ccece3dfc4c1331584c5cb2655edc51192341efd` in both `assetIndex.sha1` and its content-addressed
Mojang package URL. The cached `assets/indexes/29.json` is 529,966 bytes and its actual SHA-1 is
exactly `ccece3dfc4c1331584c5cb2655edc51192341efd`. It contains 4,591 objects; the metadata
declares 451,545,103 total asset bytes. Client, server and mappings hashes, Java version and
release timestamp agree with the profile.

This is a self-consistent **different cache revision**, not evidence of a damaged index. The
extra object and different total size are consistent with a republished index, but neither a
matching cached digest nor a cached publisher URL authenticates that hypothesis. The old index
bytes and an independently verified official metadata snapshot naming the replacement were
not available in this task. No network access was used. Keep the original repository pin rather
than promote an unauthenticated local cache to the reference merely to make the check green.

## Recovery

1. Keep a copy of the conflicting metadata and index outside shared `port/work`.
2. If keeping the frozen revision, restore a trusted metadata/index pair that names and hashes
   to the profile pin through the repository's cache/build workflow. Refetching current metadata
   is not guaranteed to restore a historical asset-index revision.
3. If adopting a republished revision, first verify publisher metadata identifying that exact
   digest and the index bytes, then change only the profile's `official.assetIndexSha1` and
   record the evidence. Do not weaken the SHA-1 check or select a revision using the index id.
4. Rerun `node port/scripts/check-version-profile.mjs --all --require-local`.

On the inspected cache the all-profile check intentionally fails with an actionable diagnostic;
the checker does not rewrite shared caches, download data or silently relax the frozen pin.
