#!/usr/bin/env bash

# macOS ships `shasum`, while GNU/Linux and Git for Windows normally ship the
# coreutils `sha1sum`/`sha256sum` pair.  Keep artifact verification portable
# instead of assuming the macOS command exists on every migration host.
gaius_hash_file() {
  local algorithm="$1"
  local file="$2"
  local command_name="${algorithm}sum"

  if command -v "$command_name" >/dev/null 2>&1; then
    "$command_name" "$file" | awk '{print $1}' | tr '[:upper:]' '[:lower:]' | tr -d '\r\n'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a "${algorithm#sha}" "$file" | awk '{print $1}' | tr '[:upper:]' '[:lower:]' | tr -d '\r\n'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst "-$algorithm" "$file" | awk '{print $NF}' | tr '[:upper:]' '[:lower:]' | tr -d '\r\n'
  else
    echo "No $algorithm checksum tool is available (tried $command_name, shasum, and openssl)" >&2
    return 1
  fi
}

gaius_sha1_file() {
  gaius_hash_file sha1 "$1"
}

gaius_sha256_file() {
  gaius_hash_file sha256 "$1"
}

gaius_load_version_profile() {
  local root="$1"
  local config="$root/port/config.json"
  local relative_profile

  relative_profile="${GAIUS_VERSION_PROFILE_PATH:-$(jq -er '.versionProfile' "$config")}"
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
  GAIUS_STORAGE_SCHEMA="$(jq -er '.storage.schema' "$GAIUS_VERSION_PROFILE")"
  GAIUS_STORAGE_DATABASE_NAME="$(jq -er '.storage.databaseName' "$GAIUS_VERSION_PROFILE")"
  GAIUS_STORAGE_PREFIX="$(jq -er '.storage.prefix' "$GAIUS_VERSION_PROFILE")"
  GAIUS_STORAGE_OPFS_DIRECTORY="$(jq -er '.storage.opfsDirectory' "$GAIUS_VERSION_PROFILE")"
  if [[ "$GAIUS_STORAGE_SCHEMA" != "2" ]]; then
    echo "Version profile storage.schema must be exactly 2: $GAIUS_VERSION_PROFILE (got $GAIUS_STORAGE_SCHEMA)" >&2
    return 1
  fi
  local expected_database_name="gaius-fs-v2-$GAIUS_MINECRAFT_VERSION"
  local expected_prefix="gaius.fs.v2:$GAIUS_MINECRAFT_VERSION:"
  local expected_opfs_directory="regions-v2-$GAIUS_MINECRAFT_VERSION"
  if [[ "$GAIUS_STORAGE_DATABASE_NAME" != "$expected_database_name" ]]; then
    echo "Version profile storage.databaseName must be $expected_database_name: $GAIUS_VERSION_PROFILE" >&2
    return 1
  fi
  if [[ "$GAIUS_STORAGE_PREFIX" != "$expected_prefix" ]]; then
    echo "Version profile storage.prefix must be $expected_prefix: $GAIUS_VERSION_PROFILE" >&2
    return 1
  fi
  if [[ "$GAIUS_STORAGE_OPFS_DIRECTORY" != "$expected_opfs_directory" ]]; then
    echo "Version profile storage.opfsDirectory must be $expected_opfs_directory: $GAIUS_VERSION_PROFILE" >&2
    return 1
  fi
  GAIUS_VERSION_METADATA="$root/port/work/$GAIUS_MINECRAFT_VERSION/version.json"

  export GAIUS_VERSION_PROFILE GAIUS_MINECRAFT_VERSION GAIUS_PROTOCOL_VERSION
  export GAIUS_WORLD_VERSION GAIUS_JAVA_VERSION GAIUS_CLASS_FILE_VERSION
  export GAIUS_CLIENT_DISTRIBUTION GAIUS_VERSION_METADATA
  export GAIUS_STORAGE_SCHEMA GAIUS_STORAGE_DATABASE_NAME
  export GAIUS_STORAGE_PREFIX GAIUS_STORAGE_OPFS_DIRECTORY
}

# Build-state paths are version-scoped when GAIUS_BUILD_ROOT is supplied.  The
# legacy defaults intentionally remain unchanged so existing 26.2 commands
# continue to publish to port/target and port/web/dist.  Release automation can
# therefore build both profiles in separate invocations without changing
# port/config.json or clobbering another profile's generated files:
#
#   GAIUS_VERSION_PROFILE_PATH=versions/1.21.11.json \
#   GAIUS_BUILD_ROOT=port/target/1.21.11 \
#   GAIUS_OVERLAY_DIRECTORY=port/work/overlays/1.21.11 \
#   GAIUS_DIST_DIRECTORY=port/web/dist/1.21.11 \
#   port/scripts/build-teavm-release.sh
#
# Keep these as functions instead of exporting default values.  A child script
# must be able to distinguish the historical default from an explicitly
# isolated build root.
gaius_resolve_path() {
  local root="$1"
  local value="$2"
  case "$value" in
    /*|[A-Za-z]:/*|[A-Za-z]:\\*)
      printf '%s\n' "$value"
      ;;
    *)
      printf '%s/%s\n' "$root" "$value"
      ;;
  esac
}

# Keep the shell-visible Maven repository and Maven's Java-visible repository
# on the same path.  Java derives user.home from the OS account rather than the
# shell HOME variable, so isolated CI/cluster jobs must pass the repository
# explicitly instead of silently falling back to a shared ~/.m2 cache.
gaius_maven_repository() {
  local root="$1"
  if [[ -n "${GAIUS_MAVEN_REPOSITORY:-}" ]]; then
    gaius_resolve_path "$root" "$GAIUS_MAVEN_REPOSITORY"
  else
    printf '%s\n' "$HOME/.m2/repository"
  fi
}

gaius_maven_repository_for_java() {
  local repository
  repository="$(gaius_maven_repository "$1")"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$repository"
  else
    printf '%s\n' "$repository"
  fi
}

gaius_build_root() {
  local root="$1"
  if [[ -n "${GAIUS_BUILD_ROOT:-}" ]]; then
    gaius_resolve_path "$root" "$GAIUS_BUILD_ROOT"
  elif [[ -n "${GAIUS_VERSION_PROFILE_PATH:-}" ]]; then
    printf '%s\n' "$root/port/target/$GAIUS_MINECRAFT_VERSION"
  else
    printf '%s\n' "$root/port/target"
  fi
}

gaius_dist_directory() {
  local root="$1"
  if [[ -n "${GAIUS_DIST_DIRECTORY:-}" ]]; then
    gaius_resolve_path "$root" "$GAIUS_DIST_DIRECTORY"
  elif [[ -n "${GAIUS_BUILD_ROOT:-}" || -n "${GAIUS_VERSION_PROFILE_PATH:-}" ]]; then
    printf '%s\n' "$root/port/web/dist/$GAIUS_MINECRAFT_VERSION"
  else
    printf '%s\n' "$root/port/web/dist"
  fi
}

gaius_overlay_directory() {
  local root="$1"
  if [[ -n "${GAIUS_OVERLAY_DIRECTORY:-}" ]]; then
    gaius_resolve_path "$root" "$GAIUS_OVERLAY_DIRECTORY"
  elif [[ -n "${GAIUS_BUILD_ROOT:-}" || -n "${GAIUS_VERSION_PROFILE_PATH:-}" ]]; then
    printf '%s\n' "$root/port/work/overlays/$GAIUS_MINECRAFT_VERSION"
  else
    printf '%s\n' "$root/port/work/overlays"
  fi
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
