#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
config="$root/port/config.json"
version="$(jq -er '.minecraftVersion' "$config")"
work="$root/port/work/$version"
metadata="$work/version.json"
libraries="$work/libraries"
assets="$work/assets"

mkdir -p "$work" "$libraries"

manifest_url="https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
version_url="$(
  curl -fsSL "$manifest_url" |
    jq -er --arg version "$version" \
      '.versions[] | select(.id == $version) | .url'
)"
curl -fsSL -o "$metadata" "$version_url"

download_from_metadata() {
  local key="$1"
  local output="$2"
  local url sha1
  url="$(jq -er --arg key "$key" '.downloads[$key].url' "$metadata" | tr -d '\r\n')"
  sha1="$(jq -er --arg key "$key" '.downloads[$key].sha1' "$metadata" | tr -d '\r\n')"
  download_verified "$url" "$sha1" "$output"
}

download_verified() {
  local url="$1"
  local expected_sha1="$2"
  local output="$3"
  expected_sha1="$(printf '%s' "$expected_sha1" | tr -d '\r\n')"
  if [[ -f "$output" ]] &&
    [[ "$(shasum -a 1 "$output" | awk '{print $1}' | tr -d '\r\n')" == "$expected_sha1" ]]; then
    return
  fi

  mkdir -p "$(dirname "$output")"
  local temporary="$output.part"
  if ! curl -fsSL --http1.1 --retry 5 --retry-delay 1 -o "$temporary" "$url"; then
    rm -f "$temporary"
    return 1
  fi
  local actual_sha1
  # Git Bash on Windows can emit CRLF while jq/curl output is LF-only. Normalize
  # both sides before comparing so an identical digest is not rejected.
  actual_sha1="$(shasum -a 1 "$temporary" | awk '{print $1}' | tr -d '\r\n')"
  if [[ "$actual_sha1" != "$expected_sha1" ]]; then
    echo "SHA-1 mismatch for $url" >&2
    echo "expected: $expected_sha1" >&2
    echo "actual:   $actual_sha1" >&2
    exit 1
  fi
  mv "$temporary" "$output"
}

echo "Fetching Minecraft $version client and official mappings"
download_from_metadata client "$work/client-obfuscated.jar"
download_from_metadata client_mappings "$work/client-mappings.txt"

echo "Fetching Java libraries"
jq -r '
  .libraries[]
  | select(.downloads.artifact != null)
  | [.downloads.artifact.path, .downloads.artifact.url, .downloads.artifact.sha1]
  | @tsv
' "$metadata" |
  while IFS=$'\t' read -r path url sha1; do
    download_verified "$url" "$sha1" "$libraries/$path"
  done

find "$libraries" -type f -name '*.jar' -print | sort |
  paste -sd ':' - >"$work/classpath.txt"

unzip -p "$work/client-obfuscated.jar" version.json >"$work/client-version.json"

asset_index_id="$(jq -er '.assetIndex.id // .assets' "$metadata" | tr -d '\r\n')"
asset_index_url="$(jq -er '.assetIndex.url' "$metadata" | tr -d '\r\n')"
asset_index_sha1="$(jq -er '.assetIndex.sha1' "$metadata" | tr -d '\r\n')"
asset_index="$assets/indexes/$asset_index_id.json"

echo "Fetching browser sound asset index $asset_index_id"
download_verified "$asset_index_url" "$asset_index_sha1" "$asset_index"

echo "Fetching browser gameplay sound assets"
browser_sound_metadata_assets=(
  "minecraft/sounds.json"
)
browser_font_assets=(
  "minecraft/font/include/unifont.json"
  "minecraft/font/include/unifont_pua.json"
  "minecraft/font/unifont.zip"
  "minecraft/font/unifont_jp.zip"
  "minecraft/font/unifont_pua.zip"
)
browser_sound_assets=(
  "minecraft/sounds/ui/toast/in.ogg"
  "minecraft/sounds/ui/toast/out.ogg"
  "minecraft/sounds/ui/toast/challenge_complete.ogg"
  "minecraft/sounds/ui/stonecutter/cut1.ogg"
  "minecraft/sounds/ui/stonecutter/cut2.ogg"
)
while IFS= read -r logical_path; do
  browser_sound_assets+=("$logical_path")
done < <(
  jq -r '
    .objects
    | keys[]
    | select(
        endswith(".ogg")
        and (
          startswith("minecraft/sounds/block/")
          or startswith("minecraft/sounds/random/")
          or startswith("minecraft/sounds/dig/")
          or startswith("minecraft/sounds/step/")
          or startswith("minecraft/sounds/mob/")
          or startswith("minecraft/sounds/entity/")
          or startswith("minecraft/sounds/item/")
          or startswith("minecraft/sounds/damage/")
          or startswith("minecraft/sounds/liquid/")
          or startswith("minecraft/sounds/fire/")
          or startswith("minecraft/sounds/portal/")
          or startswith("minecraft/sounds/minecart/")
          or startswith("minecraft/sounds/enchant/")
          or startswith("minecraft/sounds/fireworks/")
          or startswith("minecraft/sounds/event/")
          or startswith("minecraft/sounds/note/")
          or startswith("minecraft/sounds/tile/")
        )
      )
  ' "$asset_index" | tr -d '\r'
)
download_browser_asset() {
  local logical_path="$1"
  local hash
  hash="$(jq -er --arg path "$logical_path" '.objects[$path].hash' "$asset_index" | tr -d '\r\n')"
  download_verified \
    "https://resources.download.minecraft.net/${hash:0:2}/$hash" \
    "$hash" \
    "$assets/objects/${hash:0:2}/$hash"
}
export asset_index assets
export -f download_verified download_browser_asset
printf '%s\n' "${browser_sound_metadata_assets[@]}" "${browser_sound_assets[@]}" "${browser_font_assets[@]}" |
  xargs -n 1 -P "${GAIUS_FETCH_PARALLEL:-16}" bash -c 'download_browser_asset "$0"'

echo "Fetched and verified:"
echo "  client:   $work/client-obfuscated.jar"
echo "  mappings: $work/client-mappings.txt"
echo "  libraries: $(find "$libraries" -type f -name '*.jar' | wc -l | tr -d ' ')"
echo "  browser sound assets: ${#browser_sound_assets[@]} playable, ${#browser_sound_metadata_assets[@]} metadata"
echo "  browser Unicode font assets: ${#browser_font_assets[@]}"
jq '{id, protocol_version, world_version, java_version, pack_version}' \
  "$work/client-version.json"
