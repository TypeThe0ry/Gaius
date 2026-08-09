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
  if [[ ! -f "$GAIUS_VERSION_METADATA" ]]; then
    echo "Version metadata is missing: $GAIUS_VERSION_METADATA" >&2
    return 1
  fi

  jq -er --arg coordinate "$coordinate" '
    first(
      .libraries[]
      | select(.name == $coordinate or startswith($coordinate + ":"))
      | select(.downloads.artifact.path != null)
      | .downloads.artifact.path
    )
  ' "$GAIUS_VERSION_METADATA"
}
