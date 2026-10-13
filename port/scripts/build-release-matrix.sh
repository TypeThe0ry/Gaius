#!/usr/bin/env bash
set -euo pipefail

# The v0.4 release build matrix: for every profile the WebAssembly GC client and server
# (build-wasmgc.sh --release, with the binaryen step), the frozen JavaScript fallback (rebuilt
# only when a trigger requires it), the profile's Pages site, and the size gate of the release.
#
#   build-release-matrix.sh [--profiles <id>,<id>...] [--frozen-js <release root>]
#                           [--js-trigger <reason>]... [--plan] [--sites-only] <release root>
#
# Layout under <release root> (the GAIUS_BUILD_ROOT, GAIUS_OVERLAY_DIRECTORY and
# GAIUS_DIST_DIRECTORY of each step):
#   <profile>/js/{build,overlays,dist}    JavaScript fallback built by this release, with
#                                         FROZEN.json (commit, sidecar identities) for the next
#   <profile>/wasm/{build,overlays,dist}  WebAssembly GC client and server, <module>.build.json
#   <profile>/pages-site                  the site (build-pages-site.py --wasm-dist)
#   Gaius-site-<profile>.tar.gz           its deterministic archive (the release asset)
#   pages-size.json                       the size gate (build-pages-site.py --check-total)
#   release-matrix.json                   what was decided and built, per profile
#
# The JavaScript fallback is frozen: --frozen-js names an earlier release root whose
# <profile>/js stays in use (read only) unless a trigger of release-js-triggers.json fires:
#   storage   the profile's storage block, or the browser storage code, changed
#   save      the profile's worldVersion, or the save persistence code, changed
#   relay     the relay connection code of the client changed
#   worker    the page/Worker protocol changed (one Worker bootstrap serves both server
#             runtimes of a site)
#   security  never detected: --js-trigger security
# Paths are checked between the commit the frozen build recorded and this tree (uncommitted
# changes included); a profile without a frozen build, or one whose artifacts no longer match
# their sidecars, is rebuilt too. --js-trigger <reason> rebuilds every profile's fallback.
#
# --plan prints the decisions (JSON) and builds nothing. --sites-only rebuilds the sites and the
# gate from the builds already in <release root>.
#
# Full TeaVM compilations (the JavaScript release, both wasm roles) run behind
# GAIUS_RELEASE_BUILD_WRAPPER when it is set (a command prefix, e.g. a machine-wide build lock).
# The binaryen step is required (GAIUS_WASM_OPT_STRICT=1 unless set otherwise); sites ship the
# modules gzip-only and the JavaScript without .gz copies (GAIUS_RELEASE_SITE_OPTIONS, default
# "--wasm-gzip-only --no-js-gzip"); GAIUS_PAGES_LIMIT_BYTES can only lower the 900000000-byte gate.

root="$(cd "$(dirname "$0")/../.." && pwd)"
# Enabled Pages profiles, newest first by metadata, unless --profiles names some.
profiles_arg="$("$root/port/scripts/run-python.sh" - "$root" <<'PYTHON'
import importlib.util, pathlib, sys
root = pathlib.Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("pages", root / "port/scripts/build-pages-site.py")
pages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pages)
print(",".join(profile["id"] for profile in pages.release_matrix(root / "port/versions")["profiles"]))
PYTHON
)"
frozen_root=""
plan=false
sites_only=false
manual_triggers=()
release_root=""
usage() {
  echo "usage: $0 [--profiles a,b,c] [--frozen-js <release root>] [--js-trigger <reason>]... [--plan] [--sites-only] <release root>" >&2
  exit 2
}
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --profiles) [[ "$#" -ge 2 ]] || usage; profiles_arg="$2"; shift 2 ;;
    --frozen-js) [[ "$#" -ge 2 ]] || usage; frozen_root="$2"; shift 2 ;;
    --js-trigger) [[ "$#" -ge 2 ]] || usage; manual_triggers+=("$2"); shift 2 ;;
    --plan) plan=true; shift ;;
    --sites-only) sites_only=true; shift ;;
    -*) usage ;;
    *) [[ -z "$release_root" ]] || usage; release_root="$1"; shift ;;
  esac
done
[[ -n "$release_root" ]] || usage
IFS=, read -r -a profiles <<<"$profiles_arg"
mkdir -p "$release_root"
release_root="$(cd "$release_root" && pwd)"
[[ -z "$frozen_root" ]] || frozen_root="$(cd "$frozen_root" && pwd)"
triggers_file="$root/port/scripts/release-js-triggers.json"
# jq builds for Windows end lines with CRLF; lists below are read line by line.
jq() { command jq "$@" | tr -d '\r'; }
python_tool() { "$root/port/scripts/run-python.sh" "$@"; }
identity() { python_tool "$root/port/scripts/gaius_build_identity.py" "$@"; }
wrapper=()
[[ -z "${GAIUS_RELEASE_BUILD_WRAPPER:-}" ]] || read -r -a wrapper <<<"$GAIUS_RELEASE_BUILD_WRAPPER"
read -r -a site_options <<<"${GAIUS_RELEASE_SITE_OPTIONS:---wasm-gzip-only --no-js-gzip}"
export GAIUS_WASM_OPT_STRICT="${GAIUS_WASM_OPT_STRICT:-1}"
head_commit="$(git -C "$root" rev-parse HEAD)"
dirty_files="$(git -C "$root" status --porcelain | wc -l | tr -d ' ')"

# The six artifacts of a JavaScript dist with an identity sidecar (plus the shader toolchain
# of a page that loads it), as tools/build-and-publish-prerelease.ps1 checks them.
js_roles() {
  local dist="$1"
  printf '%s\n' client:classes.js singleplayer-worker:singleplayer-server.js \
    wasm-hotpath:gaius-hotpath.wasm worker-bootstrap:singleplayer-server-worker.js \
    vanilla-assets:vanilla-assets.pack.gz relay-registry:relay-nodes.json
  if grep -q 'data-gaius-shader-toolchain' "$dist/index.html" 2>/dev/null; then
    printf '%s\n' shader-toolchain:gaius-shader-toolchain.json
  fi
}

profile_env() {
  local profile="$1" kind="$2" base="$3"
  export GAIUS_VERSION_PROFILE_PATH="versions/$profile.json"
  export GAIUS_BUILD_ROOT="$base/$profile/$kind/build"
  export GAIUS_OVERLAY_DIRECTORY="$base/$profile/$kind/overlays"
  export GAIUS_DIST_DIRECTORY="$base/$profile/$kind/dist"
}

# Decide one profile's JavaScript fallback; prints a JSON object. A frozen build is
# <frozen root>/<profile>/js/FROZEN.json; its build root and dist are the "build" and "dist"
# directories next to it unless the record names others (buildRoot, dist: absolute paths), which
# lets a JavaScript release built outside this script become the first frozen fallback.
decide_js() {
  local profile="$1" frozen="$frozen_root/$profile/js" record reasons=() files changed field trigger
  local frozen_build="" frozen_dist="" changes="{}"
  if [[ "${#manual_triggers[@]}" -gt 0 ]]; then
    reasons+=("${manual_triggers[@]}")
  fi
  if [[ -n "$frozen_root" && -s "$frozen/FROZEN.json" ]]; then
    frozen_build="$(jq -r --arg d "$frozen/build" '.buildRoot // $d' "$frozen/FROZEN.json")"
    frozen_dist="$(jq -r --arg d "$frozen/dist" '.dist // $d' "$frozen/FROZEN.json")"
  fi
  if [[ -z "$frozen_root" || -z "$frozen_dist" || ! -d "$frozen_dist" || ! -d "$frozen_build" ]]; then
    reasons+=(no-frozen-build)
  else
    record="$(cat "$frozen/FROZEN.json")"
    local commit
    commit="$(jq -r '.commit // empty' <<<"$record")"
    if [[ -z "$commit" ]] || ! git -C "$root" cat-file -e "$commit^{commit}" 2>/dev/null; then
      reasons+=(frozen-commit-unknown)
    else
      for trigger in $(jq -r '.triggers | keys[]' "$triggers_file"); do
        mapfile -t files < <(jq -r --arg t "$trigger" '.triggers[$t].paths[]' "$triggers_file")
        if [[ "${#files[@]}" -gt 0 ]]; then
          changed="$(git -C "$root" diff --name-only "$commit" -- "${files[@]}" | head -n 20)"
          if [[ -n "$changed" ]]; then
            reasons+=("$trigger")
            changes="$(jq -c --arg t "$trigger" --arg f "$changed" '. + {($t): ($f | split("\n"))}' <<<"$changes")"
          fi
        fi
      done
    fi
    # Integrity and compatibility fields of every frozen artifact.
    profile_env "$profile" js "$frozen_root"
    local entry role name report
    while IFS=: read -r role name; do
      if ! report="$(identity frozen --root "$root" --role "$role" --artifact "$frozen_dist/$name" 2>/dev/null)"; then
        reasons+=("frozen-artifact-changed:$name")
        continue
      fi
      for field in $(jq -r '.differences[].field' <<<"$report"); do
        for trigger in $(jq -r --arg f "${field%%.*}" \
            '.triggers | to_entries[] | select(.value.profileFields | index($f)) | .key' "$triggers_file"); do
          reasons+=("$trigger")
        done
      done
    done < <(js_roles "$frozen_dist")
  fi
  local unique
  unique="$(printf '%s\n' ${reasons[@]+"${reasons[@]}"} | sort -u | jq -R . | jq -cs 'map(select(length > 0))')"
  if [[ "$unique" == "[]" ]]; then
    jq -cn --arg build "$frozen_build" --arg dist "$frozen_dist" \
      --arg commit "$(jq -r '.commit' "$frozen/FROZEN.json")" \
      '{decision: "frozen", reasons: [], build: $build, dist: $dist, commit: $commit}'
  else
    jq -cn --argjson reasons "$unique" --argjson changes "$changes" --arg build "$release_root/$profile/js/build" \
      --arg dist "$release_root/$profile/js/dist" \
      '{decision: "rebuild", reasons: $reasons, changes: $changes, build: $build, dist: $dist}'
  fi
}

plan_json="$release_root/release-plan.json"
{
  printf '{"commit":"%s","dirtyFiles":%s,"frozenRoot":%s,"profiles":{' \
    "$head_commit" "$dirty_files" "$(jq -Rn --arg v "$frozen_root" '$v | if . == "" then null else . end')"
  first=true
  for profile in "${profiles[@]}"; do
    [[ -f "$root/port/versions/$profile.json" ]] || { echo "No version profile $profile" >&2; exit 2; }
    $first || printf ','
    first=false
    printf '"%s":{"js":%s}' "$profile" "$(decide_js "$profile")"
  done
  printf '}}\n'
} | jq . >"$plan_json"
cat "$plan_json"
[[ "$plan" == true ]] && exit 0

run_build() {
  "${wrapper[@]+"${wrapper[@]}"}" "$@"
}

for profile in "${profiles[@]}"; do
  decision="$(jq -r --arg p "$profile" '.profiles[$p].js.decision' "$plan_json")"
  js_build="$(jq -r --arg p "$profile" '.profiles[$p].js.build' "$plan_json")"
  js_dist="$(jq -r --arg p "$profile" '.profiles[$p].js.dist' "$plan_json")"
  logs="$release_root/$profile/logs"
  mkdir -p "$logs"
  if [[ "$sites_only" != true ]]; then
    if [[ "$decision" == rebuild ]]; then
      echo "== $profile: JavaScript fallback ($(jq -c --arg p "$profile" '.profiles[$p].js.reasons' "$plan_json"))"
      profile_env "$profile" js "$release_root"
      run_build bash "$root/port/scripts/build-version-release.sh" "$profile" >"$logs/js.log" 2>&1 \
        || { tail -n 40 "$logs/js.log" >&2; exit 1; }
      sidecars="{}"
      while IFS=: read -r role name; do
        identity verify --root "$root" --role "$role" --artifact "$GAIUS_DIST_DIRECTORY/$name" >/dev/null
        sidecars="$(jq -c --arg n "$name" --arg s "$(jq -r '.identitySha256' "$GAIUS_DIST_DIRECTORY/$name.build.json")" \
          '. + {($n): $s}' <<<"$sidecars")"
      done < <(js_roles "$GAIUS_DIST_DIRECTORY")
      jq -n --arg profile "$profile" --arg commit "$head_commit" --argjson dirty "$dirty_files" \
        --argjson sidecars "$sidecars" '{profile: $profile, commit: $commit, dirtyFiles: $dirty, sidecars: $sidecars}' \
        >"$release_root/$profile/js/FROZEN.json"
    else
      echo "== $profile: JavaScript fallback frozen ($js_dist)"
    fi
    echo "== $profile: WebAssembly GC client and server"
    profile_env "$profile" wasm "$release_root"
    export GAIUS_WASMGC_RESOURCE_ROOT="$js_build"
    export GAIUS_WASMGC_RESOURCE_DIST="$js_dist"
    run_build bash "$root/port/scripts/build-wasmgc.sh" --release "$profile" >"$logs/wasm-client.log" 2>&1 \
      || { tail -n 40 "$logs/wasm-client.log" >&2; exit 1; }
    run_build bash "$root/port/scripts/build-wasmgc.sh" --release --role singleplayer-worker --skip-overlays "$profile" \
      >"$logs/wasm-server.log" 2>&1 || { tail -n 40 "$logs/wasm-server.log" >&2; exit 1; }
    unset GAIUS_WASMGC_RESOURCE_ROOT GAIUS_WASMGC_RESOURCE_DIST
  fi
  profile_env "$profile" wasm "$release_root"
  identity verify --root "$root" --role wasm-client --artifact "$GAIUS_DIST_DIRECTORY/classes.wasm" >/dev/null
  identity verify --root "$root" --role wasm-singleplayer-worker \
    --artifact "$GAIUS_DIST_DIRECTORY/singleplayer-server.wasm" >/dev/null
  echo "== $profile: Pages site"
  python_tool "$root/port/scripts/build-pages-site.py" "$js_dist" "$release_root/$profile/pages-site" \
    --archive "$release_root/Gaius-site-$profile.tar.gz" --wasm-dist "$GAIUS_DIST_DIRECTORY" "${site_options[@]}" \
    | tee "$logs/site.log"
done

sites=()
python_tool "$root/port/scripts/build-pages-site.py" --write-matrix "$release_root/Gaius-pages-matrix.json"
for profile in "${profiles[@]}"; do sites+=("$release_root/$profile/pages-site"); done
gate_status=0
python_tool "$root/port/scripts/build-pages-site.py" --check-total "${sites[@]}" \
  --limit-bytes "${GAIUS_PAGES_LIMIT_BYTES:-900000000}" --extra-file "$release_root/Gaius-pages-matrix.json" \
  --json "$release_root/pages-size.json" || gate_status=$?

{
  printf '{"commit":"%s","dirtyFiles":%s,"profiles":{' "$head_commit" "$dirty_files"
  first=true
  for profile in "${profiles[@]}"; do
    $first || printf ','
    first=false
    dist="$release_root/$profile/wasm/dist"
    printf '"%s":{"js":%s,"wasm":{"client":%s,"server":%s},"site":{"archive":"Gaius-site-%s.tar.gz","sha256":"%s"}}' \
      "$profile" "$(jq -c --arg p "$profile" '.profiles[$p].js' "$plan_json")" \
      "$(jq -c '{artifact, wasmOpt: .wasm.wasmOpt.applied, identitySha256}' "$dist/classes.wasm.build.json")" \
      "$(jq -c '{artifact, wasmOpt: .wasm.wasmOpt.applied, identitySha256}' "$dist/singleplayer-server.wasm.build.json")" \
      "$profile" "$(sha256sum "$release_root/Gaius-site-$profile.tar.gz" | cut -d' ' -f1)"
  done
  printf '},"pagesSize":%s}\n' "$(jq -c '{sitesBytes, keptByNextDeployBytes, worstCaseBytes, limitBytes, ok}' "$release_root/pages-size.json")"
} | jq . >"$release_root/release-matrix.json"
echo "Release matrix: $release_root/release-matrix.json"
exit "$gate_status"
