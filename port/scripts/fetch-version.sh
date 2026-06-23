#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
config="$root/port/config.json"
version="$(jq -er '.minecraftVersion' "$config")"
work="$root/port/work/$version"
metadata="$work/version.json"
libraries="$work/libraries"

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

echo "Fetched and verified:"
echo "  client:   $work/client-obfuscated.jar"
echo "  mappings: $work/client-mappings.txt"
echo "  libraries: $(find "$libraries" -type f -name '*.jar' | wc -l | tr -d ' ')"
jq '{id, protocol_version, world_version, java_version, pack_version}' \
  "$work/client-version.json"
