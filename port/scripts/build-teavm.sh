#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
if [[ "${GAIUS_SKIP_OVERLAY_BUILD:-false}" != "true" ]]; then
  "$root/port/scripts/build-overlays.sh" >/dev/null
else
  echo "Skipping overlay rebuild because GAIUS_SKIP_OVERLAY_BUILD=true"
fi
version="$(jq -er '.minecraftVersion' "$root/port/config.json")"
work="$root/port/work/$version"
resource_list_dir="$root/port/target/generated-resources/dev/gaius/browser"
resource_list="$resource_list_dir/minecraft-resources.txt"
generated_assets="$root/port/target/generated-resources/assets"
mkdir -p "$resource_list_dir"
jar tf "$root/port/work/overlays/client-named-$version-gaius.jar" |
  awk '(index($0, "assets/") == 1 || index($0, "data/") == 1 || $0 == "pack.png") && substr($0, length($0), 1) != "/" { print }' >"$resource_list"
jar tf "$root/port/work/overlays/libraries/com/ibm/icu/icu4j/77.1/icu4j-77.1.jar" |
  awk 'index($0, "com/ibm/icu/impl/data/icudata/") == 1 && substr($0, length($0), 1) != "/" { print }' >>"$resource_list"
rm -rf "$generated_assets/minecraft/sounds" "$generated_assets/minecraft/sounds.json"
asset_index_id="$(jq -er '.assetIndex.id // .assets' "$work/version.json" 2>/dev/null || true)"
asset_index="$work/assets/indexes/$asset_index_id.json"
browser_sound_assets=(
  "minecraft/sounds/random/click.ogg"
  "minecraft/sounds/random/click_stereo.ogg"
  "minecraft/sounds/random/wood_click.ogg"
  "minecraft/sounds/random/levelup.ogg"
  "minecraft/sounds/random/orb.ogg"
  "minecraft/sounds/ui/toast/in.ogg"
  "minecraft/sounds/ui/toast/out.ogg"
  "minecraft/sounds/ui/toast/challenge_complete.ogg"
  "minecraft/sounds/ui/stonecutter/cut1.ogg"
  "minecraft/sounds/ui/stonecutter/cut2.ogg"
  "minecraft/sounds/dig/grass1.ogg"
  "minecraft/sounds/dig/grass2.ogg"
  "minecraft/sounds/dig/grass3.ogg"
  "minecraft/sounds/dig/grass4.ogg"
  "minecraft/sounds/step/grass1.ogg"
  "minecraft/sounds/step/grass2.ogg"
  "minecraft/sounds/step/grass3.ogg"
  "minecraft/sounds/step/grass4.ogg"
  "minecraft/sounds/step/grass5.ogg"
  "minecraft/sounds/step/grass6.ogg"
)
copied_sound_assets=0
if [[ -n "$asset_index_id" && -f "$asset_index" ]]; then
  browser_sound_names=()
  for logical_path in "${browser_sound_assets[@]}"; do
    hash="$(jq -r --arg path "$logical_path" '.objects[$path].hash // ""' "$asset_index")"
    if [[ -z "$hash" ]]; then
      echo "WARNING: browser sound asset not listed in asset index: $logical_path" >&2
      continue
    fi
    source="$work/assets/objects/${hash:0:2}/$hash"
    if [[ ! -f "$source" ]]; then
      echo "WARNING: missing browser sound asset object for $logical_path ($hash)" >&2
      continue
    fi
    target="$generated_assets/$logical_path"
    mkdir -p "$(dirname "$target")"
    cp "$source" "$target"
    printf 'assets/%s\n' "$logical_path" >>"$resource_list"
    sound_name="${logical_path#minecraft/sounds/}"
    browser_sound_names+=("${sound_name%.ogg}")
    copied_sound_assets=$((copied_sound_assets + 1))
  done
  sounds_json_hash="$(jq -r '.objects["minecraft/sounds.json"].hash // ""' "$asset_index")"
  if [[ -n "$sounds_json_hash" ]]; then
    sounds_json_source="$work/assets/objects/${sounds_json_hash:0:2}/$sounds_json_hash"
    if [[ -f "$sounds_json_source" ]]; then
      sounds_json_target="$generated_assets/minecraft/sounds.json"
      allowed_sounds_json="$(printf '%s\n' "${browser_sound_names[@]}" | jq -R . | jq -s .)"
      mkdir -p "$(dirname "$sounds_json_target")"
      jq --argjson allowed "$allowed_sounds_json" '
        def sound_name:
          (if type == "string" then . else (.name // "") end)
          | sub("^minecraft:"; "")
          | sub("^sounds/"; "")
          | sub("\\.ogg$"; "");
        def playable_file:
          . as $entry
          | ((if ($entry | type) == "object" then ($entry.type // "file") else "file" end) == "file")
          and (($allowed | index($entry | sound_name)) != null);
        with_entries(
          .value |= (
            if type == "object" and (.sounds | type) == "array" then
              .sounds = (.sounds | map(select(playable_file)))
            else
              .
            end
          )
          | select((.value.sounds | type) == "array" and (.value.sounds | length) > 0)
        )
      ' "$sounds_json_source" >"$sounds_json_target"
      printf 'assets/minecraft/sounds.json\n' >>"$resource_list"
      echo "Filtered browser sounds.json: $(jq 'length' "$sounds_json_target") events"
    else
      echo "WARNING: missing browser sounds.json object ($sounds_json_hash)" >&2
    fi
  else
    echo "WARNING: browser sounds.json not listed in asset index" >&2
  fi
else
  echo "WARNING: asset index not found at $asset_index; run port/scripts/fetch-version.sh to enable browser game sounds" >&2
fi
sort -u -o "$resource_list" "$resource_list"
echo "Generated browser resource list: $(wc -l <"$resource_list" | tr -d ' ') entries"
echo "Mapped browser sound assets: $copied_sound_assets"
pom="$("$root/port/scripts/generate-pom.sh")"
log="$root/port/target/teavm-build.log"

echo "Compiling the official Minecraft 1.21.11 client with TeaVM"
echo "POM: $pom"
echo "Log: $log"

set +e
MAVEN_OPTS="${MAVEN_OPTS:--Xms4g -Xmx20g -XX:+UseG1GC -XX:MaxGCPauseMillis=300}" \
  "$root/port/mvnw" \
  --batch-mode \
  --errors \
  --file "$pom" \
  package >"$log" 2>&1
build_status="$?"
set -e

tail -n 160 "$log" || true

set +e
"$root/port/scripts/analyze-teavm-log.py" \
  "$log" \
  "$root/port/target/teavm-gap.json" \
  "$root/port/target/teavm-gap.md"
analysis_status="$?"
set -e

if [[ "$analysis_status" -ne 0 ]]; then
  echo "TeaVM analysis did not complete; canonical gap report was preserved" >&2
fi

if [[ "$build_status" -eq 0 ]]; then
  target_js="$root/port/web/dist/${GAIUS_TARGET_FILE:-classes.js}"
  "$root/port/scripts/postprocess-teavm-js.py" "$target_js"
  "$root/port/scripts/postprocess-index-html.py" \
    "$root/port/web/dist/index.html" \
    "$target_js"
  "$root/port/scripts/compress-dist.sh" >/dev/null
fi

exit "$build_status"
