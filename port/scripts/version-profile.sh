#!/usr/bin/env bash

gaius_load_version_profile() {
  local root="$1"
  local config="$root/port/config.json"
  local relative_profile

  relative_profile="$(jq -er '.versionProfile' "$config")"
  case "$relative_profile" in
    versions/*.json) ;;
    *)
      echo "port/config.json versionProfile must point inside port/versions" >&2
      return 1
      ;;
  esac

  GAIUS_VERSION_PROFILE="$root/port/$relative_profile"
  if [[ ! -f "$GAIUS_VERSION_PROFILE" ]]; then
    echo "Version profile is missing: $GAIUS_VERSION_PROFILE" >&2
    return 1
  fi

  GAIUS_MINECRAFT_VERSION="$(jq -er '.id' "$GAIUS_VERSION_PROFILE")"
  GAIUS_PROTOCOL_VERSION="$(jq -er '.protocolVersion' "$GAIUS_VERSION_PROFILE")"
  GAIUS_WORLD_VERSION="$(jq -er '.worldVersion' "$GAIUS_VERSION_PROFILE")"
  GAIUS_JAVA_VERSION="$(jq -er '.javaVersion' "$GAIUS_VERSION_PROFILE")"
  GAIUS_CLASS_FILE_VERSION="$(jq -er '.classFileVersion' "$GAIUS_VERSION_PROFILE")"
  GAIUS_CLIENT_DISTRIBUTION="$(jq -er '.clientDistribution' "$GAIUS_VERSION_PROFILE")"
  GAIUS_VERSION_METADATA="$root/port/work/$GAIUS_MINECRAFT_VERSION/version.json"

  export GAIUS_VERSION_PROFILE GAIUS_MINECRAFT_VERSION GAIUS_PROTOCOL_VERSION
  export GAIUS_WORLD_VERSION GAIUS_JAVA_VERSION GAIUS_CLASS_FILE_VERSION
  export GAIUS_CLIENT_DISTRIBUTION GAIUS_VERSION_METADATA
}

gaius_library_path() {
  local coordinate="$1"
  local fallback_classifier="${2:-}"
  if [[ ! -f "$GAIUS_VERSION_METADATA" ]]; then
    echo "Version metadata is missing: $GAIUS_VERSION_METADATA" >&2
    return 1
  fi

  jq -er --arg coordinate "$coordinate" --arg fallback "$fallback_classifier" '
    [
      .libraries[]
      | select(.name | startswith($coordinate + ":"))
      | select(.downloads.artifact.path != null)
      | {parts: (.name | split(":")), path: .downloads.artifact.path}
    ] as $matches
    | (
        first($matches[] | select(.parts | length == 3))
        // first($matches[] | select($fallback != "" and .parts[3] == $fallback))
      ).path
  ' "$GAIUS_VERSION_METADATA"
}

gaius_select_java_home() {
  local requested_version="$GAIUS_JAVA_VERSION"
  local candidates=()
  local candidate
  local detected_version

  if [[ -n "${GAIUS_JAVA_HOME:-}" ]]; then
    candidates+=("$GAIUS_JAVA_HOME")
  fi
  if [[ -n "${JAVA_HOME:-}" ]]; then
    candidates+=("$JAVA_HOME")
  fi
  candidates+=(
    "/opt/homebrew/opt/openjdk@$requested_version/libexec/openjdk.jdk/Contents/Home"
    "/usr/local/opt/openjdk@$requested_version/libexec/openjdk.jdk/Contents/Home"
  )
  if [[ "$(uname -s)" == "Darwin" ]]; then
    candidate="$(/usr/libexec/java_home -v "$requested_version" 2>/dev/null || true)"
    if [[ -n "$candidate" ]]; then
      candidates+=("$candidate")
    fi
  fi
  if command -v javac >/dev/null 2>&1; then
    candidate="$(cd "$(dirname "$(command -v javac)")/.." 2>/dev/null && pwd || true)"
    if [[ -n "$candidate" ]]; then
      candidates+=("$candidate")
    fi
  fi

  for candidate in "${candidates[@]}"; do
    if [[ ! -x "$candidate/bin/javac" || ! -x "$candidate/bin/java" ]]; then
      continue
    fi
    detected_version="$("$candidate/bin/javac" -version 2>&1 | awk '{print $2}' | cut -d. -f1)"
    if [[ "$detected_version" =~ ^[0-9]+$ ]] &&
        [[ "$detected_version" -ge "$requested_version" ]]; then
      GAIUS_JAVA_HOME="$candidate"
      JAVA_HOME="$candidate"
      PATH="$candidate/bin:$PATH"
      export GAIUS_JAVA_HOME JAVA_HOME PATH
      return 0
    fi
  done

  echo "Minecraft $GAIUS_MINECRAFT_VERSION requires JDK $requested_version or newer" >&2
  echo "Set GAIUS_JAVA_HOME to a compatible JDK installation" >&2
  return 1
}
