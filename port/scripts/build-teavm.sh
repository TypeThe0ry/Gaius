#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$root/port/scripts/version-profile.sh"
gaius_load_version_profile "$root"
version="$GAIUS_MINECRAFT_VERSION"
if [[ "${GAIUS_SKIP_OVERLAY_BUILD:-false}" != "true" ]]; then
  "$root/port/scripts/build-overlays.sh" >/dev/null
  gson_type_token_patches="$root/port/target/gson-type-token-client-patches"
  mkdir -p "$gson_type_token_patches"
  find "$gson_type_token_patches" -type f -delete
  java -classpath \
    "$root/port/work/overlays/tool-classes:$HOME/.m2/repository/org/ow2/asm/asm/9.8/asm-9.8.jar:$HOME/.m2/repository/org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar" \
    dev.gaius.tools.GsonTypeTokenClientPatcher \
    "$root/port/work/overlays/client-named-$version-gaius.jar" \
    "$gson_type_token_patches"
  jar --update \
    --file "$root/port/work/overlays/client-named-$version-gaius.jar" \
    -C "$gson_type_token_patches" .
else
  echo "Skipping overlay rebuild because GAIUS_SKIP_OVERLAY_BUILD=true"
fi
work="$root/port/work/$version"
resource_list_dir="$root/port/target/generated-resources/dev/gaius/browser"
resource_list="$resource_list_dir/minecraft-resources.txt"
embedded_resource_list="$resource_list_dir/minecraft-embedded-resources.txt"
generated_resources="$root/port/target/generated-resources"
generated_assets="$root/port/target/generated-resources/assets"
target_directory="${GAIUS_TARGET_DIRECTORY:-$root/port/web/dist}"
vanilla_asset_pack="$target_directory/vanilla-assets.pack.gz"
mkdir -p "$resource_list_dir"
jar tf "$root/port/work/overlays/client-named-$version-gaius.jar" |
  awk '(index($0, "assets/") == 1 || index($0, "data/") == 1 || $0 == "pack.png") && substr($0, length($0), 1) != "/" { print }' >"$resource_list"
jar tf "$root/port/work/overlays/libraries/com/ibm/icu/icu4j/77.1/icu4j-77.1.jar" |
  awk 'index($0, "com/ibm/icu/impl/data/icudata/") == 1 && substr($0, length($0), 1) != "/" { print }' >>"$resource_list"
rm -rf "$generated_assets/minecraft/sounds" "$generated_assets/minecraft/sounds.json" \
  "$generated_assets/minecraft/font"
asset_index_id="$(jq -er '.assetIndex.id // .assets' "$work/version.json" 2>/dev/null | tr -d '\r\n' || true)"
asset_index="$work/assets/indexes/$asset_index_id.json"
copied_sound_assets=0
copied_font_assets=0
if [[ -n "$asset_index_id" && -f "$asset_index" ]]; then
  browser_sound_manifest="$root/port/target/browser-sound-assets.tsv"
  jq -r '
    .objects
    | to_entries[]
    | select(
        .key as $path
        | ($path == "minecraft/sounds/ui/toast/in.ogg")
          or ($path == "minecraft/sounds/ui/toast/out.ogg")
          or ($path == "minecraft/sounds/ui/toast/challenge_complete.ogg")
          or ($path == "minecraft/sounds/ui/stonecutter/cut1.ogg")
          or ($path == "minecraft/sounds/ui/stonecutter/cut2.ogg")
          or (
            ($path | endswith(".ogg"))
            and (
              ($path | startswith("minecraft/sounds/block/"))
              or ($path | startswith("minecraft/sounds/random/"))
              or ($path | startswith("minecraft/sounds/dig/"))
              or ($path | startswith("minecraft/sounds/step/"))
              or ($path | startswith("minecraft/sounds/mob/"))
              or ($path | startswith("minecraft/sounds/entity/"))
              or ($path | startswith("minecraft/sounds/item/"))
              or ($path | startswith("minecraft/sounds/damage/"))
              or ($path | startswith("minecraft/sounds/liquid/"))
              or ($path | startswith("minecraft/sounds/fire/"))
              or ($path | startswith("minecraft/sounds/portal/"))
              or ($path | startswith("minecraft/sounds/minecart/"))
              or ($path | startswith("minecraft/sounds/enchant/"))
              or ($path | startswith("minecraft/sounds/fireworks/"))
              or ($path | startswith("minecraft/sounds/event/"))
              or ($path | startswith("minecraft/sounds/note/"))
              or ($path | startswith("minecraft/sounds/tile/"))
            )
          )
      )
    | [.key, .value.hash]
    | @tsv
  ' "$asset_index" | tr -d '\r' >"$browser_sound_manifest"
  awk -F '\t' -v root="$generated_assets/" '
    {
      path = $1
      sub("/[^/]+$", "", path)
      print root path
    }
  ' "$browser_sound_manifest" | sort -u | xargs mkdir -p
  browser_sound_names=()
  while IFS=$'\t' read -r logical_path hash; do
    if [[ -z "$logical_path" || -z "$hash" ]]; then
      continue
    fi
    source="$work/assets/objects/${hash:0:2}/$hash"
    if [[ ! -f "$source" ]]; then
      echo "WARNING: missing browser sound asset object for $logical_path ($hash)" >&2
      continue
    fi
    target="$generated_assets/$logical_path"
    cp "$source" "$target"
    printf 'assets/%s\n' "$logical_path" >>"$resource_list"
    sound_name="${logical_path#minecraft/sounds/}"
    browser_sound_names+=("${sound_name%.ogg}")
    copied_sound_assets=$((copied_sound_assets + 1))
  done <"$browser_sound_manifest"

  # Mojang ships the Unicode fallback as indexed assets rather than client-jar
  # entries. Embed it so browser resource packs can safely override default.json.
  browser_font_manifest="$root/port/target/browser-font-assets.tsv"
  jq -r '
    .objects
    | to_entries[]
    | select(
        .key == "minecraft/font/include/unifont.json"
        or .key == "minecraft/font/include/unifont_pua.json"
        or .key == "minecraft/font/unifont.zip"
        or .key == "minecraft/font/unifont_jp.zip"
        or .key == "minecraft/font/unifont_pua.zip"
      )
    | [.key, .value.hash]
    | @tsv
  ' "$asset_index" | tr -d '\r' >"$browser_font_manifest"
  while IFS=$'\t' read -r logical_path hash; do
    if [[ -z "$logical_path" || -z "$hash" ]]; then
      continue
    fi
    source="$work/assets/objects/${hash:0:2}/$hash"
    if [[ ! -f "$source" ]] || [[ "$(shasum -a 1 "$source" | awk '{print $1}' | tr -d '\r\n')" != "$hash" ]]; then
      mkdir -p "$(dirname "$source")"
      temporary="$source.part"
      curl -fsSL --http1.1 --retry 5 --retry-delay 1 \
        -o "$temporary" "https://resources.download.minecraft.net/${hash:0:2}/$hash"
      if [[ "$(shasum -a 1 "$temporary" | awk '{print $1}' | tr -d '\r\n')" != "$hash" ]]; then
        rm -f "$temporary"
        echo "SHA-1 mismatch for browser Unicode font asset $logical_path" >&2
        exit 1
      fi
      mv "$temporary" "$source"
    fi
    target="$generated_assets/$logical_path"
    mkdir -p "$(dirname "$target")"
    cp "$source" "$target"
    printf 'assets/%s\n' "$logical_path" >>"$resource_list"
    copied_font_assets=$((copied_font_assets + 1))
  done <"$browser_font_manifest"

  # TeaVM resolves duplicate resources from the client overlay JAR before the
  # generated-resource directory. Replace the empty JAR stub so the embedded
  # runtime definition actually reaches the browser instead of merely appearing
  # in minecraft-resources.txt.
  client_overlay="$root/port/work/overlays/client-named-$version-gaius.jar"
  for font_definition in \
    assets/minecraft/font/include/unifont.json \
    assets/minecraft/font/include/unifont_pua.json; do
    zip -q -d "$client_overlay" "$font_definition" >/dev/null 2>&1 || true
    (cd "$(dirname "$generated_assets")" && jar uf "$client_overlay" "$font_definition")
  done

  sounds_json_hash="$(jq -r '.objects["minecraft/sounds.json"].hash // ""' "$asset_index" | tr -d '\r\n')"
  if [[ -n "$sounds_json_hash" ]]; then
    sounds_json_source="$work/assets/objects/${sounds_json_hash:0:2}/$sounds_json_hash"
    if [[ -f "$sounds_json_source" ]]; then
      sounds_json_target="$generated_assets/minecraft/sounds.json"
      allowed_sounds_json="$root/port/target/browser-sound-names.json"
      printf '%s\n' "${browser_sound_names[@]}" | jq -R . | jq -s . >"$allowed_sounds_json"
      mkdir -p "$(dirname "$sounds_json_target")"
      jq --slurpfile allowed "$allowed_sounds_json" '
        def sound_name:
          (if type == "string" then . else (.name // "") end)
          | sub("^minecraft:"; "")
          | sub("^sounds/"; "")
          | sub("\\.ogg$"; "");
        def playable_file:
          . as $entry
          | ((if ($entry | type) == "object" then ($entry.type // "file") else "file" end) == "file")
          and (($allowed[0] | index($entry | sound_name)) != null);
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
awk '
  index($0, "assets/") != 1 && index($0, "data/") != 1 && $0 != "pack.png" { print; next }
  $0 == "assets/minecraft/lang/deprecated.json" { print; next }
  $0 == "assets/minecraft/lang/en_us.json" { print; next }
  $0 == "assets/minecraft/font/include/unifont.json" { print; next }
  $0 == "assets/minecraft/font/unifont.zip" { print; next }
  $0 == "assets/minecraft/sounds/random/eat1.ogg" { print; next }
' "$resource_list" | sort -u >"$embedded_resource_list"
"$root/port/scripts/run-python.sh" \
  "$root/port/scripts/build-vanilla-assets-pack.py" \
  "$resource_list" \
  "$root/port/work/overlays/client-named-$version-gaius.jar" \
  "$generated_resources" \
  "$vanilla_asset_pack"
echo "Generated browser resource list: $(wc -l <"$resource_list" | tr -d ' ') entries"
echo "Embedded TeaVM resource subset: $(wc -l <"$embedded_resource_list" | tr -d ' ') entries"
echo "Mapped browser sound assets: $copied_sound_assets"
echo "Mapped browser Unicode font assets: $copied_font_assets"
pom="$("$root/port/scripts/generate-pom.sh")"
log="$root/port/target/teavm-build.log"

echo "Compiling the official Minecraft $version client with TeaVM"
echo "POM: $pom"
echo "Log: $log"

set +e
MAVEN_OPTS="${MAVEN_OPTS:--Xms2g -Xmx14g -XX:+UseG1GC -XX:MaxGCPauseMillis=500}" \
  "$root/port/mvnw" \
  --batch-mode \
  --errors \
  --file "$pom" \
  package >"$log" 2>&1
build_status="$?"
set -e

tail -n 160 "$log" || true

set +e
"$root/port/scripts/run-python.sh" \
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
  target_js="$target_directory/${GAIUS_TARGET_FILE:-classes.js}"
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/postprocess-teavm-js.py" "$target_js"
  "$root/port/scripts/run-python.sh" \
    "$root/port/scripts/postprocess-index-html.py" \
    "$target_directory/index.html" \
    "$target_js"
  if [[ "$target_directory" == "$root/port/web/dist" \
        && "${GAIUS_SKIP_COMPRESSION:-false}" != "true" ]]; then
    "$root/port/scripts/compress-dist.sh" >/dev/null
  fi
fi

exit "$build_status"
