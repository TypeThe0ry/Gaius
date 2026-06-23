#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
config="$root/port/config.json"
version="$(jq -er '.minecraftVersion' "$config")"
art_version="$(jq -er '.autoRenamingToolVersion' "$config")"
work="$root/port/work/$version"
tools="$root/port/work/tools"
art="$tools/AutoRenamingTool-$art_version-all.jar"
input="$work/client-obfuscated.jar"
mappings="$work/client-mappings.txt"
output="$work/client-named.jar"

if [[ ! -f "$input" || ! -f "$mappings" ]]; then
  echo "Run ./port/scripts/fetch-version.sh first" >&2
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

main_class="net/minecraft/client/main/Main.class"
if [[ "$(unzip -Z1 "$output" "$main_class" 2>/dev/null || true)" != "$main_class" ]]; then
  echo "Remapped client does not contain $main_class" >&2
  exit 1
fi

class_count="$(
  unzip -l "$output" |
    awk '$4 ~ /\.class$/ { count++ } END { print count + 0 }'
)"
echo "Remap complete:"
echo "  output:  $output"
echo "  classes: $class_count"
echo "  main:    $main_class"
