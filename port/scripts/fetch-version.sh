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
  url="$(jq -er --arg key "$key" '.downloads[$key].url' "$metadata")"
  sha1="$(jq -er --arg key "$key" '.downloads[$key].sha1' "$metadata")"
  download_verified "$url" "$sha1" "$output"
}

download_verified() {
  local url="$1"
  local expected_sha1="$2"
  local output="$3"
  if [[ -f "$output" ]] &&
    [[ "$(shasum -a 1 "$output" | awk '{print $1}')" == "$expected_sha1" ]]; then
    return
  fi

  mkdir -p "$(dirname "$output")"
  local temporary="$output.part"
  curl -fL --retry 3 --retry-delay 1 -o "$temporary" "$url"
  local actual_sha1
  actual_sha1="$(shasum -a 1 "$temporary" | awk '{print $1}')"
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

asset_index_id="$(jq -er '.assetIndex.id // .assets' "$metadata")"
asset_index_url="$(jq -er '.assetIndex.url' "$metadata")"
asset_index_sha1="$(jq -er '.assetIndex.sha1' "$metadata")"
asset_index="$assets/indexes/$asset_index_id.json"

echo "Fetching browser sound asset index $asset_index_id"
download_verified "$asset_index_url" "$asset_index_sha1" "$asset_index"

echo "Fetching browser smoke/UI sound assets"
browser_sound_metadata_assets=(
  "minecraft/sounds.json"
)
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
for logical_path in "${browser_sound_metadata_assets[@]}" "${browser_sound_assets[@]}"; do
  hash="$(jq -er --arg path "$logical_path" '.objects[$path].hash' "$asset_index")"
  download_verified \
    "https://resources.download.minecraft.net/${hash:0:2}/$hash" \
    "$hash" \
    "$assets/objects/${hash:0:2}/$hash"
done

echo "Fetched and verified:"
echo "  client:   $work/client-obfuscated.jar"
echo "  mappings: $work/client-mappings.txt"
echo "  libraries: $(find "$libraries" -type f -name '*.jar' | wc -l | tr -d ' ')"
echo "  browser sound assets: ${#browser_sound_assets[@]} playable, ${#browser_sound_metadata_assets[@]} metadata"
jq '{id, protocol_version, world_version, java_version, pack_version}' \
  "$work/client-version.json"
