#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
config="$root/port/config.json"
source "$root/port/scripts/version-profile.sh"
gaius_load_version_profile "$root"
gaius_select_java_home
version="$GAIUS_MINECRAFT_VERSION"
art_version="$(jq -er '.autoRenamingToolVersion' "$config")"
work="$root/port/work/$version"
tools="$root/port/work/tools"
art="$tools/AutoRenamingTool-$art_version-all.jar"
input="$work/client-original.jar"
mappings="$work/client-mappings.txt"
output="$work/client-named.jar"

if [[ ! -f "$input" ]]; then
  echo "Run ./port/scripts/fetch-version.sh first" >&2
  exit 1
fi

if [[ "$GAIUS_CLIENT_DISTRIBUTION" == "named" ]]; then
  cp "$input" "$output"
  zip -q -d "$output" 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.DSA' \
    >/dev/null 2>&1 || true
  echo "Using the official named Minecraft $version client without remapping"
else
  if [[ ! -f "$mappings" ]]; then
    echo "Official mappings are missing; run ./port/scripts/fetch-version.sh first" >&2
    exit 1
  fi

  mkdir -p "$tools"
  if [[ ! -f "$art" ]]; then
    curl -fL --retry 3 -o "$art.part" \
      "https://maven.neoforged.net/releases/net/neoforged/AutoRenamingTool/$art_version/AutoRenamingTool-$art_version-all.jar"
    mv "$art.part" "$art"
  fi

  library_arguments=()
  while IFS= read -r library; do
    library_arguments+=(--lib "$library")
  done < <(find "$work/libraries" -type f -name '*.jar' -print | sort)

  rm -f "$output"
  echo "Remapping official Minecraft $version client to Mojang names"
  java -Xmx3g -jar "$art" \
    --input "$input" \
    --output "$output" \
    --map "$mappings" \
    --reverse \
    --strip-sigs \
    --ann-fix \
    --record-fix \
    --src-fix JAVA \
    "${library_arguments[@]}"
fi

main_class="net/minecraft/client/main/Main.class"
# BusyBox unzip (used by the isolated Linux workers) does not implement the
# Info-ZIP -Z1 listing mode. Use the portable long-listing format and match
# the final member column exactly so a valid remap is not rejected falsely.
if ! unzip -l "$output" 2>/dev/null |
  awk -v expected="$main_class" '$NF == expected { found = 1 } END { exit(found ? 0 : 1) }'; then
  echo "Remapped client does not contain $main_class" >&2
  exit 1
fi

class_count="$(
  unzip -l "$output" |
    awk '$4 ~ /\.class$/ { count++ } END { print count + 0 }'
)"
echo "Named client ready:"
echo "  output:  $output"
echo "  classes: $class_count"
echo "  main:    $main_class"
