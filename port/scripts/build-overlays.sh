#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
source "$root/port/scripts/teavm-publication-gate.sh"
build_lock="$root/port/work/.build-overlays.lock"
build_lock_owner=""
build_lock_owned_here=false

release_build_lock() {
  local status="$?"
  trap - EXIT
  if [[ "$build_lock_owned_here" == true && -n "${build_lock_owner:-}" ]]; then
    gaius_teavm_lock_release "$build_lock" "$build_lock_owner" || true
  fi
  exit "$status"
}
trap release_build_lock EXIT

if [[ "${GAIUS_OVERLAY_LOCK_HELD:-false}" != "true" ]]; then
  gaius_teavm_lock_acquire "$build_lock"
  build_lock_owner="$GAIUS_TEA_LOCK_OWNER_TOKEN"
  build_lock_owned_here=true
else
  lock_pid="$(cat "$build_lock/pid" 2>/dev/null || true)"
  if [[ ! -d "$build_lock" || "$lock_pid" != "$PPID" ]]; then
    echo "GAIUS_OVERLAY_LOCK_HELD=true without a lock owned by the caller" >&2
    exit 1
  fi
fi

config="$root/port/config.json"
source "$root/port/scripts/version-profile.sh"
gaius_load_version_profile "$root"
gaius_select_java_home
# `gaius_select_java_home` stores a Windows-native path when `cygpath` is
# available.  Re-add its Unix view to PATH so Git Bash can resolve jar/java
# launchers consistently.
if command -v cygpath >/dev/null 2>&1; then
  GAIUS_JAVA_HOME_UNIX="$(cygpath -u "$GAIUS_JAVA_HOME")"
  PATH="$GAIUS_JAVA_HOME_UNIX/bin:$PATH"
  export PATH
fi
version="$GAIUS_MINECRAFT_VERSION"

# The client patcher chain is chosen by the profile's patchSet, and the
# profile's extraPatchers run in order at the tail of that chain.  Unknown
# values stop the build before any work is done.
patch_set="$GAIUS_PATCH_SET"
case "$patch_set" in
  modern|legacy-12111) ;;
  *)
    echo "Unknown patchSet '$patch_set' in $GAIUS_VERSION_PROFILE (expected modern or legacy-12111)" >&2
    exit 1
    ;;
esac
for extra_patcher in $GAIUS_EXTRA_PATCHERS; do
  if [[ ! "$extra_patcher" =~ ^[A-Z][A-Za-z0-9_]*$ ]]; then
    echo "Invalid extraPatchers entry '$extra_patcher' in $GAIUS_VERSION_PROFILE" >&2
    exit 1
  fi
done

# Bring-up mode (see gaius_bringup_active in version-profile.sh): with
# GAIUS_BRINGUP=1 an unfinished profile may skip the patches listed in
# port/tools/bringup/<profile>.txt.  Without it, a profile that still has a
# non-empty list fails here and prints what is unfinished.  Release profiles
# never enter bring-up mode.
bringup_list="$(gaius_bringup_list_path "$root")"
bringup_ids="$(gaius_bringup_ids "$root")"
bringup_active=false
bringup_skipped=0
if gaius_bringup_active; then
  bringup_active=true
  echo "Bring-up mode for Minecraft $version: $(grep -c . <<<"$bringup_ids" || true) listed patches may be skipped ($bringup_list)"
elif [[ "${GAIUS_BRINGUP:-}" == "1" ]]; then
  echo "GAIUS_BRINGUP=1 is ignored for release profile $version" >&2
fi
if [[ "$bringup_active" != true && -n "$bringup_ids" ]]; then
  if gaius_bringup_profile_allowed; then
    echo "Minecraft $version still has unfinished patches (${bringup_list#"$root/"}):" >&2
    tr -d '\r' <"$bringup_list" | sed -e 's/#.*$//' -e '/^[[:space:]]*$/d' -e 's/^/  /' >&2
    echo "Build with GAIUS_BRINGUP=1 to skip them for bring-up; release builds refuse bring-up mode." >&2
  else
    echo "Release profile $version must not have a bring-up list: $bringup_list" >&2
  fi
  exit 1
fi
if [[ "$bringup_active" == true ]]; then
  export GAIUS_BRINGUP=1
else
  export GAIUS_BRINGUP=0
fi
GAIUS_BRINGUP_LIST="$bringup_list"
if command -v cygpath >/dev/null 2>&1; then
  GAIUS_BRINGUP_LIST="$(cygpath -m "$bringup_list")"
fi
export GAIUS_BRINGUP_LIST

bringup_skip() {
  local id
  if [[ "$bringup_active" != true || -z "$bringup_ids" ]]; then
    return 1
  fi
  for id in "$@"; do
    if grep -Fqx -- "$id" <<<"$bringup_ids"; then
      echo "BRINGUP_SKIP $id"
      bringup_skipped=$((bringup_skipped + 1))
      return 0
    fi
  done
  return 1
}

teavm_version="$(jq -er '.teaVMVersion' "$config")"
work="$root/port/work/$version"
overlay_work="$(gaius_overlay_directory "$root")"
source_root="$root/port/overrides/classlib/src/main/java"
classes="$overlay_work/classlib-classes"
maven_repository="$(gaius_maven_repository "$root")"
maven_repository_for_java="$(gaius_maven_repository_for_java "$root")"
export GAIUS_MAVEN_REPOSITORY="$maven_repository"
asm_version="9.8"

# javac on Windows uses `;` as the classpath separator. Git Bash does not
# rewrite a colon-separated classpath whose first entry is already `C:/...`.
java_classpath_separator=":"
java_maven_repository="$maven_repository"
java_work_classpath="$(cat "$root/port/work/${GAIUS_MINECRAFT_VERSION}/classpath.txt" 2>/dev/null || true)"
java_client_jar="$root/port/work/${GAIUS_MINECRAFT_VERSION}/client-named.jar"
java_asm_jar="$maven_repository/org/ow2/asm/asm/$asm_version/asm-$asm_version.jar"
java_asm_tree_jar="$maven_repository/org/ow2/asm/asm-tree/$asm_version/asm-tree-$asm_version.jar"
if command -v cygpath >/dev/null 2>&1; then
  java_classpath_separator=";"
  java_maven_repository="$maven_repository_for_java"
  java_work_classpath="$(cygpath -mp "$java_work_classpath")"
  java_client_jar="$(cygpath -m "$java_client_jar")"
  java_asm_jar="$(cygpath -m "$maven_repository/org/ow2/asm/asm/$asm_version/asm-$asm_version.jar")"
  java_asm_tree_jar="$(cygpath -m "$maven_repository/org/ow2/asm/asm-tree/$asm_version/asm-tree-$asm_version.jar")"
fi

# Normalize tool-side `java -classpath A:B` calls for Windows.  Keeping this
# in one shim covers the many patcher invocations below.
if command -v cygpath >/dev/null 2>&1; then
  java() {
    if [[ "${1:-}" == "-classpath" && -n "${2:-}" ]]; then
      local native_classpath
      # The argument can contain a mixture of Git-Bash `/c/...` entries and
      # already-native `C:/...` entries.  `cygpath -mp` treats drive-letter
      # colons as separators, so normalize each entry before joining with `;`.
      native_classpath="$(python -c 'import re,sys; s=sys.argv[1]; print(";".join(re.sub(r"^/([A-Za-z])/", lambda m: m.group(1).upper()+":/", p) for p in re.split(r":(?=(?:/|[A-Za-z]:/))", s)))' "$2")"
      command java -classpath "$native_classpath" "${@:3}"
    else
      command java "$@"
    fi
  }
fi

# A fresh checkout has no generated POM yet, but the overlays must be built
# before that POM can be generated. Bootstrap the exact compile-time JARs
# directly so release/CI jobs do not depend on a pre-warmed ~/.m2 cache.
required_maven_artifacts=(
  "org.teavm:teavm-classlib:$teavm_version|org/teavm/teavm-classlib/$teavm_version/teavm-classlib-$teavm_version.jar"
  "org.teavm:teavm-interop:$teavm_version|org/teavm/teavm-interop/$teavm_version/teavm-interop-$teavm_version.jar"
  "org.teavm:teavm-jso:$teavm_version|org/teavm/teavm-jso/$teavm_version/teavm-jso-$teavm_version.jar"
  "org.teavm:teavm-jso-apis:$teavm_version|org/teavm/teavm-jso-apis/$teavm_version/teavm-jso-apis-$teavm_version.jar"
  "org.teavm:teavm-core:$teavm_version|org/teavm/teavm-core/$teavm_version/teavm-core-$teavm_version.jar"
  "org.teavm:teavm-platform:$teavm_version|org/teavm/teavm-platform/$teavm_version/teavm-platform-$teavm_version.jar"
  "com.jcraft:jzlib:1.1.3|com/jcraft/jzlib/1.1.3/jzlib-1.1.3.jar"
  "org.ow2.asm:asm:$asm_version|org/ow2/asm/asm/$asm_version/asm-$asm_version.jar"
  "org.ow2.asm:asm-tree:$asm_version|org/ow2/asm/asm-tree/$asm_version/asm-tree-$asm_version.jar"
)
for artifact_spec in "${required_maven_artifacts[@]}"; do
  IFS='|' read -r artifact_coordinate artifact_relative_path <<<"$artifact_spec"
  artifact_path="$maven_repository/$artifact_relative_path"
  if [[ ! -f "$artifact_path" ]]; then
    echo "Bootstrapping Maven artifact $artifact_coordinate"
    "$root/port/mvnw" --batch-mode --no-transfer-progress \
      "-Dmaven.repo.local=$maven_repository_for_java" \
      org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get \
      "-Dartifact=$artifact_coordinate" \
      -Dtransitive=false
  fi
  if [[ ! -f "$artifact_path" ]]; then
    echo "Maven artifact bootstrap did not produce $artifact_path" >&2
    exit 1
  fi
done

# javac_source_argfile FILE SOURCE...: writes the source files to the javac
# @argfile FILE and prints the argument that passes it ("@<path>").  Windows
# limits a command line to 32767 characters, and a profile classpath plus a
# source list of absolute paths in a deep worktree exceeds that (26.3 does).
# Git Bash only rewrites /c/... paths that appear on a command line, so the
# file gets native paths.  cygpath writes a path of 260 or more characters in
# the Win32 long-path form //?/C:/..., which javac rejects as a file name;
# Java opens long C:/... paths itself, so that prefix is removed.
javac_source_argfile() {
  local argfile="$1"
  shift
  local native_argfile
  if command -v cygpath >/dev/null 2>&1; then
    printf '%s\n' "$@" | cygpath -m -f - | strip_long_path_prefix \
      | sed -e 's/^/"/' -e 's/$/"/' >"$argfile" || return 1
    native_argfile="$(cygpath -m "$argfile" | strip_long_path_prefix)" || return 1
    printf '@%s\n' "$native_argfile"
  else
    printf '"%s"\n' "$@" >"$argfile" || return 1
    printf '@%s\n' "$argfile"
  fi
}

strip_long_path_prefix() {
  sed -e 's#^//[?]/UNC/#//#' -e 's#^//[?]/##'
}

# javac_with_source_argfile FILE JAVAC_OPTION... -- SOURCE...: runs javac with
# the sources passed through the @argfile FILE (see javac_source_argfile) and
# deletes FILE afterwards, also when javac fails.
javac_with_source_argfile() {
  local argfile="$1"
  shift
  local options=()
  while [[ "$#" -gt 0 && "$1" != "--" ]]; do
    options+=("$1")
    shift
  done
  if [[ "$#" -eq 0 ]]; then
    echo "javac_with_source_argfile: missing -- before the source list" >&2
    return 1
  fi
  shift
  local argument
  local status=0
  argument="$(javac_source_argfile "$argfile" "$@")" || status=$?
  if [[ "$status" -eq 0 ]]; then
    javac "${options[@]}" "$argument" || status=$?
  fi
  rm -f "$argfile"
  return "$status"
}

upstream="$maven_repository/org/teavm/teavm-classlib/$teavm_version/teavm-classlib-$teavm_version.jar"
output="$overlay_work/teavm-classlib-$teavm_version-gaius.jar"

mkdir -p "$classes" "$overlay_work"

# Bring-up marker: a bring-up build writes $overlay_work/BRINGUP (the profile
# and the listed patch ids) before it changes any overlay, and only a
# complete build outside bring-up mode removes it.  build-teavm-release.sh
# refuses overlays that carry the marker, and gaius_build_identity.py hashes
# it into the overlay identity.
bringup_marker="$overlay_work/BRINGUP"
if [[ "$bringup_active" == true ]]; then
  {
    echo "# Overlays built in bring-up mode for Minecraft $version: the patches listed in"
    echo "# ${bringup_list#"$root/"} may have been skipped.  Release builds refuse these"
    echo "# overlays; rebuild them without GAIUS_BRINGUP=1."
    printf '%s\n' "$bringup_ids"
  } >"$bringup_marker"
fi

find "$classes" -type f -delete

sources=()
while IFS= read -r source; do
  sources+=("$source")
done < <(find "$source_root" -type f -name '*.java' -print | sort)
for source in \
  "$root/port/src/main/java/org/teavm/classlib/java/net/TAuthenticator.java" \
  "$root/port/src/main/java/org/teavm/classlib/java/net/TIDN.java" \
  "$root/port/src/main/java/org/teavm/classlib/java/net/TInet4Address.java" \
  "$root/port/src/main/java/org/teavm/classlib/java/net/TInet6Address.java" \
  "$root/port/src/main/java/org/teavm/classlib/java/net/TInetAddress.java" \
  "$root/port/src/main/java/org/teavm/classlib/java/net/TInetSocketAddress.java" \
  "$root/port/src/main/java/org/teavm/classlib/java/net/TNetworkInterface.java" \
  "$root/port/src/main/java/org/teavm/classlib/java/net/TPasswordAuthentication.java" \
  "$root/port/src/main/java/org/teavm/classlib/java/net/TProxy.java" \
  "$root/port/src/main/java/org/teavm/classlib/java/net/TSocketAddress.java" \
  "$root/port/src/main/java/org/teavm/classlib/java/net/TUnknownHostException.java" \
  "$root/port/src/main/java/org/teavm/classlib/java/nio/channels/TChannels.java" \
  "$root/port/src/main/java/org/teavm/classlib/java/nio/channels/TFileChannel.java" \
  "$root/port/src/main/java/org/teavm/classlib/java/nio/channels/TFileLock.java" \
  "$root/port/src/main/java/org/teavm/classlib/java/util/concurrent/locks/TLockSupport.java"; do
  sources+=("$source")
done
if [[ "${#sources[@]}" -eq 0 ]]; then
  echo "No classlib overrides found" >&2
  exit 1
fi

classpath="$(if command -v cygpath >/dev/null 2>&1; then cygpath -m "$upstream"; else printf '%s' "$upstream"; fi)"
for artifact in teavm-interop teavm-jso teavm-jso-apis teavm-core teavm-platform; do
  classpath="$classpath${java_classpath_separator}${java_maven_repository}/org/teavm/$artifact/$teavm_version/$artifact-$teavm_version.jar"
done
classpath="$classpath${java_classpath_separator}${java_maven_repository}/com/jcraft/jzlib/1.1.3/jzlib-1.1.3.jar"
classpath="$classpath${java_classpath_separator}$java_work_classpath"

javac_with_source_argfile "$overlay_work/classlib-sources.argfile" \
  --release 21 -proc:none -classpath "$classpath" -d "$classes" \
  -- "${sources[@]}"
cp "$upstream" "$output"
jar --update --file "$output" -C "$classes" .

# Library overlays merge two source roots, with the same rules as the client
# overrides: port/overrides/libraries/<name>/src/main/java is shared by every
# profile, and port/overrides/libraries/<name>/src/versions/<artifact-version>/
# {java,excludes.txt} applies only to that library version (the Maven version
# directory of the library jar, for example 3.4.3 or 10.0.77).  A version file
# replaces the shared file with the same relative path; excludes.txt lists
# shared relative paths to leave out (format: gaius_version_excludes in
# version-profile.sh).  Without any Java source the vanilla jar is copied
# unmodified, so the output jar always exists.
build_library_overlay() {
  local name="$1"
  local source_jar="$2"
  local source_dir="$3"
  local output_jar="$4"
  local artifact_version
  artifact_version="$(basename "$(dirname "$source_jar")")"
  local version_dir
  version_dir="$(dirname "$(dirname "$source_dir")")/versions/$artifact_version"
  local version_source_dir="$version_dir/java"
  local version_excludes="$version_dir/excludes.txt"
  if [[ ! -d "$source_dir" && ! -d "$version_source_dir" ]]; then
    echo "Using unmodified $name base: no source overrides at $source_dir"
    mkdir -p "$(dirname "$output_jar")"
    cp "$source_jar" "$output_jar"
    return 0
  fi
  local output_classes="$overlay_work/library-classes/$name"
  local java_source_jar="$(if command -v cygpath >/dev/null 2>&1; then cygpath -m "$source_jar"; else printf '%s' "$source_jar"; fi)"
  local compile_classpath="$java_source_jar${java_classpath_separator}$java_client_jar${java_classpath_separator}$java_work_classpath"
  for artifact in teavm-interop teavm-jso teavm-jso-apis teavm-platform; do
    compile_classpath="$compile_classpath${java_classpath_separator}${java_maven_repository}/org/teavm/$artifact/$teavm_version/$artifact-$teavm_version.jar"
  done
  local library_sources=()
  local relative_source
  local library_excludes
  library_excludes="$(gaius_version_excludes "$version_excludes" "$source_dir")"

  if [[ -d "$source_dir" ]]; then
    while IFS= read -r source; do
      relative_source="${source#"$source_dir/"}"
      if [[ -f "$version_source_dir/$relative_source" ]]; then
        continue
      fi
      if [[ -n "$library_excludes" ]] \
          && grep -Fqx -e "$relative_source" <<<"$library_excludes"; then
        continue
      fi
      library_sources+=("$source")
    done < <(find "$source_dir" -type f -name '*.java' -print | sort)
  fi
  if [[ -d "$version_source_dir" ]]; then
    echo "Adding $name $artifact_version overrides from $version_source_dir"
    while IFS= read -r source; do
      library_sources+=("$source")
    done < <(find "$version_source_dir" -type f -name '*.java' -print | sort)
  fi

  if [[ "${#library_sources[@]}" -eq 0 ]]; then
    echo "Skipping $name overlay: no Java sources at $source_dir; using the unmodified base"
    mkdir -p "$(dirname "$output_jar")"
    cp "$source_jar" "$output_jar"
    return 0
  fi

  mkdir -p "$output_classes" "$(dirname "$output_jar")"
  find "$output_classes" -type f -delete
  javac_with_source_argfile "$output_classes.argfile" \
    --release 21 -proc:none -classpath "$compile_classpath" -d "$output_classes" \
    -- "${library_sources[@]}"
  cp "$source_jar" "$output_jar"
  jar --update --file "$output_jar" -C "$output_classes" .
}

jtracy_path="$(gaius_library_path "com.mojang:jtracy")"
build_library_overlay \
  jtracy \
  "$work/libraries/$jtracy_path" \
  "$root/port/overrides/libraries/jtracy/src/main/java" \
  "$overlay_work/libraries/$jtracy_path"

oshi_path="$(gaius_library_path "com.github.oshi:oshi-core")"
build_library_overlay \
  oshi \
  "$work/libraries/$oshi_path" \
  "$root/port/overrides/libraries/oshi/src/main/java" \
  "$overlay_work/libraries/$oshi_path"

slf4j_path="$(gaius_library_path "org.slf4j:slf4j-api")"
build_library_overlay \
  slf4j \
  "$work/libraries/$slf4j_path" \
  "$root/port/overrides/libraries/slf4j/src/main/java" \
  "$overlay_work/libraries/$slf4j_path"

gson_path="$(gaius_library_path "com.google.code.gson:gson")"
build_library_overlay \
  gson \
  "$work/libraries/$gson_path" \
  "$root/port/overrides/libraries/gson/src/main/java" \
  "$overlay_work/libraries/$gson_path"

mojang_logging_path="$(gaius_library_path "com.mojang:logging")"
build_library_overlay \
  mojang-logging \
  "$work/libraries/$mojang_logging_path" \
  "$root/port/overrides/libraries/mojang-logging/src/main/java" \
  "$overlay_work/libraries/$mojang_logging_path"

joml_path="$(gaius_library_path "org.joml:joml")"
build_library_overlay \
  joml \
  "$work/libraries/$joml_path" \
  "$root/port/overrides/libraries/joml/src/main/java" \
  "$overlay_work/libraries/$joml_path"

jopt_simple_path="$(gaius_library_path "net.sf.jopt-simple:jopt-simple")"
build_library_overlay \
  jopt-simple \
  "$work/libraries/$jopt_simple_path" \
  "$root/port/overrides/libraries/jopt-simple/src/main/java" \
  "$overlay_work/libraries/$jopt_simple_path"

lwjgl_path="$(gaius_library_path "org.lwjgl:lwjgl" "unsafe")"
build_library_overlay \
  lwjgl \
  "$work/libraries/$lwjgl_path" \
  "$root/port/overrides/libraries/lwjgl/src/main/java" \
  "$overlay_work/libraries/$lwjgl_path"

text2speech_path="$(gaius_library_path "com.mojang:text2speech")"
text2speech_output="$overlay_work/libraries/$text2speech_path"

tool_classes="$overlay_work/tool-classes"
asm_jar="$maven_repository/org/ow2/asm/asm/$asm_version/asm-$asm_version.jar"
asm_tree_jar="$maven_repository/org/ow2/asm/asm-tree/$asm_version/asm-tree-$asm_version.jar"
mkdir -p "$tool_classes"
find "$tool_classes" -type f -delete
# -sourcepath lets javac pull in the patchers' sub-packages (for example
# dev.gaius.tools.m263, called by Minecraft263BrowserPatcher).
javac --release 21 -proc:none \
  -classpath "$java_asm_jar${java_classpath_separator}$java_asm_tree_jar" \
  -sourcepath "$root/port/tools/src/main/java" \
  -d "$tool_classes" \
  "$root/port/tools/src/main/java/dev/gaius/tools/"*.java

# Client patchers record every patch through dev.gaius.tools.PatchRegistry.
# They receive the profile id and the bring-up state both as system
# properties and through the exported GAIUS_MINECRAFT_VERSION, GAIUS_BRINGUP
# (1 only when bring-up mode is active) and GAIUS_BRINGUP_LIST variables.
# gaius.authlib.jar names the profile's vanilla authlib jar, so a
# ModernSymbols probe of the patched client jar (which has no classpath.txt
# next to it) cross-checks the authlib flavour against that jar.
authlib_path="$(gaius_library_path "com.mojang:authlib")"
java_authlib_jar="$work/libraries/$authlib_path"
if command -v cygpath >/dev/null 2>&1; then
  java_authlib_jar="$(cygpath -m "$java_authlib_jar")"
fi
patch_registry_properties=(
  "-Dgaius.profile=$version"
  "-Dgaius.bringup=$bringup_active"
  "-Dgaius.bringup.list=$GAIUS_BRINGUP_LIST"
  "-Dgaius.authlib.jar=$java_authlib_jar"
)

tool_class_exists() {
  [[ -f "$tool_classes/dev/gaius/tools/$1.class" ]]
}

require_tool_class() {
  local class="$1"
  local step_id="$2"
  if ! tool_class_exists "$class"; then
    echo "Patcher dev.gaius.tools.$class is not implemented ($step_id)." >&2
    echo "Implement it, or list $step_id in port/tools/bringup/$version.txt and build with GAIUS_BRINGUP=1." >&2
    exit 1
  fi
}

patch_lwjgl_callback_descriptors() {
  local module_output="$1"
  local module_patches="$2"
  find "$module_patches" -type f -delete
  java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
    dev.gaius.tools.LwjglCallbackDescriptorPatcher \
    "$module_output" \
    "$module_patches"
  jar --update --file "$module_output" -C "$module_patches" .
}
teavm_core="$maven_repository/org/teavm/teavm-core/$teavm_version/teavm-core-$teavm_version.jar"
teavm_core_output="$overlay_work/teavm-core-$teavm_version-gaius.jar"
teavm_core_patches="$overlay_work/teavm-core-patches"
mkdir -p "$teavm_core_patches"
find "$teavm_core_patches" -type f -delete
cp "$teavm_core" "$teavm_core_output"
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar:$teavm_core" \
  dev.gaius.tools.TeaVMCoreBrowserPatcher \
  "$teavm_core_output" \
  "$teavm_core_patches/org/teavm/backend/javascript/intrinsics/reflection/ClassInfoGenerator.class"
jar --update --file "$teavm_core_output" -C "$teavm_core_patches" .

jopt_simple_patches="$overlay_work/library-patches/jopt-simple"
mkdir -p "$jopt_simple_patches/joptsimple/internal"
find "$jopt_simple_patches" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.JoptSimpleBrowserPatcher \
  "$overlay_work/libraries/$jopt_simple_path" \
  "$jopt_simple_patches/joptsimple/internal/Columns.class"
jar --update \
  --file "$overlay_work/libraries/$jopt_simple_path" \
  -C "$jopt_simple_patches" .

text2speech_patch_classes="$overlay_work/library-patches/text2speech"
mkdir -p "$(dirname "$text2speech_output")" "$text2speech_patch_classes"
find "$text2speech_patch_classes" -type f -delete
cp "$work/libraries/$text2speech_path" "$text2speech_output"
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.Text2SpeechBrowserPatcher \
  "$text2speech_output" \
  "$text2speech_patch_classes/com/mojang/text2speech/Narrator.class"
jar --update \
  --file "$text2speech_output" \
  -C "$text2speech_patch_classes" com/mojang/text2speech/Narrator.class

authlib_output="$overlay_work/libraries/$authlib_path"
authlib_patch_classes="$overlay_work/library-patches/authlib"
mkdir -p "$(dirname "$authlib_output")" "$authlib_patch_classes"
find "$authlib_patch_classes" -type f -delete
cp "$work/libraries/$authlib_path" "$authlib_output"
# The patcher decides which classes it writes (authlib 9 yggdrasil or authlib
# 10 services names); the directory is emptied above, so it holds only this
# run's output.
if ! bringup_skip "step:AuthlibBrowserPatcher"; then
  java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
    dev.gaius.tools.AuthlibBrowserPatcher \
    "$authlib_output" \
    "$authlib_patch_classes/com/mojang/authlib/minecraft/client/MinecraftClient.class"
  jar --update \
    --file "$authlib_output" \
    -C "$authlib_patch_classes" .
fi

patchy_path="$(gaius_library_path "com.mojang:patchy")"
patchy_output="$overlay_work/libraries/$patchy_path"
patchy_patch_classes="$overlay_work/library-patches/patchy"
mkdir -p "$(dirname "$patchy_output")" "$patchy_patch_classes"
find "$patchy_patch_classes" -type f -delete
cp "$work/libraries/$patchy_path" "$patchy_output"
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.PatchyBrowserPatcher \
  "$patchy_output" \
  "$patchy_patch_classes/com/mojang/patchy/MojangBlockListSupplier.class"
jar --update \
  --file "$patchy_output" \
  -C "$patchy_patch_classes" com/mojang/patchy/MojangBlockListSupplier.class

classlib_patch_classes="$overlay_work/classlib-patches"
mkdir -p "$classlib_patch_classes"
find "$classlib_patch_classes" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.TeaVMClasslibPatcher \
  "$output" \
  "$classlib_patch_classes"
jar --update --file "$output" -C "$classlib_patch_classes" .
joml_patch_classes="$overlay_work/library-patches/joml"
mkdir -p "$joml_patch_classes"
find "$joml_patch_classes" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.JomlMemUtilPatcher \
  "$overlay_work/libraries/$joml_path" \
  "$joml_patch_classes/org/joml/MemUtil.class"
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.JomlMathPatcher \
  "$overlay_work/libraries/$joml_path" \
  "$joml_patch_classes/org/joml/Math.class"
jar --update \
  --file "$overlay_work/libraries/$joml_path" \
  -C "$joml_patch_classes" org/joml/MemUtil.class \
  -C "$joml_patch_classes" org/joml/Math.class

guava_path="$(gaius_library_path "com.google.guava:guava")"
guava_output="$overlay_work/libraries/$guava_path"
guava_patch_classes="$overlay_work/library-patches/guava"
mkdir -p "$(dirname "$guava_output")" "$guava_patch_classes"
find "$guava_patch_classes" -type f -delete
build_library_overlay \
  guava \
  "$work/libraries/$guava_path" \
  "$root/port/overrides/libraries/guava/src/main/java" \
  "$guava_output"
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.GuavaFutureStatePatcher \
  "$guava_output" \
  "$guava_patch_classes/com/google/common/util/concurrent/AbstractFutureState.class"
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.AbstractSpliteratorBrowserPatcher \
  "$guava_output" \
  "$guava_patch_classes"
jar --update \
  --file "$guava_output" \
  -C "$guava_patch_classes" .

netty_common_path="$(gaius_library_path "io.netty:netty-common")"
netty_common_output="$overlay_work/libraries/$netty_common_path"
netty_patch_classes="$overlay_work/library-patches/netty-common"
mkdir -p "$(dirname "$netty_common_output")" "$netty_patch_classes"
find "$netty_patch_classes" -type f -delete
build_library_overlay \
  netty-common \
  "$work/libraries/$netty_common_path" \
  "$root/port/overrides/libraries/netty-common/src/main/java" \
  "$netty_common_output"
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.NettyLoggerPatcher \
  "$netty_common_output" \
  "$netty_patch_classes/io/netty/util/internal/logging/InternalLoggerFactory.class"
jar --update \
  --file "$netty_common_output" \
  -C "$netty_patch_classes" \
  io/netty/util/internal/logging/InternalLoggerFactory.class

netty_buffer_path="$(gaius_library_path "io.netty:netty-buffer")"
netty_buffer_output="$overlay_work/libraries/$netty_buffer_path"
netty_buffer_patch_classes="$overlay_work/library-patches/netty-buffer"
mkdir -p "$(dirname "$netty_buffer_output")" "$netty_buffer_patch_classes"
find "$netty_buffer_patch_classes" -type f -delete
build_library_overlay \
  netty-buffer \
  "$work/libraries/$netty_buffer_path" \
  "$root/port/overrides/libraries/netty-buffer/src/main/java" \
  "$netty_buffer_output"
netty_transport_path="$(gaius_library_path "io.netty:netty-transport")"
netty_transport_output="$overlay_work/libraries/$netty_transport_path"
netty_transport_patch_classes="$overlay_work/library-patches/netty-transport"
mkdir -p "$(dirname "$netty_transport_output")" "$netty_transport_patch_classes"
find "$netty_transport_patch_classes" -type f -delete
build_library_overlay \
  netty-transport \
  "$work/libraries/$netty_transport_path" \
  "$root/port/overrides/libraries/netty-transport/src/main/java" \
  "$netty_transport_output"
netty_codec_http_path="$(gaius_library_path "io.netty:netty-codec-http")"
netty_codec_http_output="$overlay_work/libraries/$netty_codec_http_path"
build_library_overlay \
  netty-codec-http \
  "$work/libraries/$netty_codec_http_path" \
  "$root/port/overrides/libraries/netty-codec-http/src/main/java" \
  "$netty_codec_http_output"
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.NettyBrowserPatcher \
  "$netty_common_output" \
  "$netty_buffer_output" \
  "$netty_transport_output" \
  "$netty_patch_classes" \
  "$netty_buffer_patch_classes" \
  "$netty_transport_patch_classes"
jar --update \
  --file "$netty_common_output" \
  -C "$netty_patch_classes" .
jar --update \
  --file "$netty_buffer_output" \
  -C "$netty_buffer_patch_classes" .
jar --update \
  --file "$netty_transport_output" \
  -C "$netty_transport_patch_classes" .

# Netty 4.2 guards its heap accessors with PlatformDependent.hasVarHandle(),
# but TeaVM still analyzes the signature-polymorphic branch and cannot resolve
# return-type-only VarHandle.get overloads. Fail here, before a long TeaVM
# compile, unless the overlay contains only the portable byte-array branch.
netty_heap_buffer_dump="$(
  javap -classpath "$netty_buffer_output" -p -c io.netty.buffer.HeapByteBufUtil
)"
if grep -Fq 'io/netty/buffer/VarHandleByteBufferAccess' \
    <<<"$netty_heap_buffer_dump"; then
  echo "Netty heap-buffer overlay still reaches VarHandleByteBufferAccess" >&2
  exit 1
fi
if grep -Fq 'io/netty/util/internal/PlatformDependent.hasVarHandle' \
    <<<"$netty_heap_buffer_dump"; then
  echo "Netty heap-buffer overlay still contains a runtime VarHandle guard" >&2
  exit 1
fi
for portable_helper in getInt0 getLong0 setInt0 setLong0; do
  if ! grep -Fq "$portable_helper" <<<"$netty_heap_buffer_dump"; then
    echo "Netty heap-buffer overlay lost portable helper $portable_helper" >&2
    exit 1
  fi
done
netty_teavm_common_patches="$overlay_work/library-patches/netty-teavm-common"
netty_teavm_http_patches="$overlay_work/library-patches/netty-teavm-codec-http"
mkdir -p "$netty_teavm_common_patches" "$netty_teavm_http_patches"
find "$netty_teavm_common_patches" "$netty_teavm_http_patches" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.NettyTeaVMCompatibilityPatcher \
  "$netty_common_output" \
  "$netty_codec_http_output" \
  "$netty_teavm_common_patches" \
  "$netty_teavm_http_patches"
jar --update \
  --file "$netty_common_output" \
  -C "$netty_teavm_common_patches" .
jar --update \
  --file "$netty_codec_http_output" \
  -C "$netty_teavm_http_patches" .

commons_io_path="$(gaius_library_path "commons-io:commons-io")"
commons_compress_path="$(gaius_library_path "org.apache.commons:commons-compress")"
commons_io_output="$overlay_work/libraries/$commons_io_path"
commons_compress_output="$overlay_work/libraries/$commons_compress_path"
commons_io_patches="$overlay_work/library-patches/commons-io"
commons_compress_patches="$overlay_work/library-patches/commons-compress"
mkdir -p \
  "$(dirname "$commons_io_output")" \
  "$(dirname "$commons_compress_output")" \
  "$commons_io_patches" \
  "$commons_compress_patches"
find "$commons_io_patches" "$commons_compress_patches" -type f -delete
cp "$work/libraries/$commons_io_path" "$commons_io_output"
cp "$work/libraries/$commons_compress_path" "$commons_compress_output"
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.CommonsBrowserPatcher \
  "$commons_io_output" \
  "$commons_compress_output" \
  "$commons_io_patches" \
  "$commons_compress_patches"
jar --update --file "$commons_io_output" -C "$commons_io_patches" .
jar --update --file "$commons_compress_output" -C "$commons_compress_patches" .

icu_path="$(gaius_library_path "com.ibm.icu:icu4j")"
icu_output="$overlay_work/libraries/$icu_path"
icu_patch_classes="$overlay_work/library-patches/icu"
mkdir -p "$(dirname "$icu_output")" "$icu_patch_classes"
find "$icu_patch_classes" -type f -delete
cp "$work/libraries/$icu_path" "$icu_output"
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.IcuBrowserPatcher \
  "$icu_output" \
  "$icu_patch_classes/com/ibm/icu/impl/ICUBinary.class"
jar --update \
  --file "$icu_output" \
  -C "$icu_patch_classes" com/ibm/icu/impl/ICUBinary.class

# LWJGL module steps.  run_lwjgl_steps applies one step list to one module jar:
#   patcher:<Class>      java dev.gaius.tools.<Class> <jar> <patch-dir>, then jar --update
#   unsupported:<Class>  the same with the module name as a third argument
#   callbacks            LwjglCallbackDescriptorPatcher
#   guard:<Name>         a verification of the jar written so far
# Every step can be skipped in bring-up mode as "step:<Class>@<module>" or, for
# every module at once, "step:<Class>".  A patcher class that does not exist
# yet fails the build unless bring-up mode skips its step.
lwjgl_guard_LwjglArchitectureGuard() {
  local module_output="$1"
  if ! javap -classpath "$module_output" -c -p \
      'org.lwjgl.system.Platform$Architecture' | grep -Fq 'String wasm64'; then
    echo "LWJGL browser architecture patch is missing" >&2
    exit 1
  fi
  echo "Verified LWJGL browser architecture identity"
}

# The browser Platform must report Linux through the replaced os.name lookup.
# Any other System.getProperty result replaced by "Linux" (LWJGL 3.4.3 reads
# java.version first) makes Platform.<clinit> fail at runtime.
lwjgl_guard_LwjglPlatformGuard() {
  local module_output="$1"
  local platform_dump
  platform_dump="$(javap -classpath "$module_output" -c -p org.lwjgl.system.Platform)"
  if ! awk '
    /static \{\};/ { clinit = 1; next }
    !clinit { next }
    state == 1 { state = ($2 == "pop") ? 2 : 0; next }
    state == 2 { if ($0 ~ /\/\/ String Linux$/) replaced[property] = 1; state = 0 }
    / ldc/ && /\/\/ String / { value = $0; sub(/.*\/\/ String /, "", value); last = value; next }
    /java\/lang\/System\.getProperty/ { property = last; state = 1; next }
    END {
      if (!("os.name" in replaced)) exit 1
      for (key in replaced) if (key != "os.name") exit 1
    }
  ' <<<"$platform_dump"; then
    echo "LWJGL Platform does not replace exactly the os.name lookup with Linux" >&2
    exit 1
  fi
  echo "Verified LWJGL browser Platform os.name replacement"
}

run_lwjgl_steps() {
  local module="$1"
  local module_output="$2"
  local module_patches="$3"
  shift 3
  local step
  local class
  mkdir -p "$module_patches"
  for step in "$@"; do
    case "$step" in
      callbacks) class=LwjglCallbackDescriptorPatcher ;;
      patcher:*|unsupported:*|guard:*) class="${step#*:}" ;;
      *)
        echo "Unknown LWJGL step '$step' for $module" >&2
        exit 1
        ;;
    esac
    if bringup_skip "step:$class@$module" "step:$class"; then
      continue
    fi
    case "$step" in
      patcher:*|unsupported:*)
        require_tool_class "$class" "step:$class@$module"
        find "$module_patches" -type f -delete
        if [[ "$step" == unsupported:* ]]; then
          java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
            "dev.gaius.tools.$class" \
            "$module_output" \
            "$module_patches" \
            "$module"
        else
          java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
            "dev.gaius.tools.$class" \
            "$module_output" \
            "$module_patches"
        fi
        jar --update --file "$module_output" -C "$module_patches" .
        ;;
      callbacks)
        patch_lwjgl_callback_descriptors "$module_output" "$module_patches"
        ;;
      guard:*)
        "lwjgl_guard_$class" "$module_output"
        ;;
    esac
  done
}

run_lwjgl_steps \
  lwjgl \
  "$overlay_work/libraries/$lwjgl_path" \
  "$overlay_work/library-patches/lwjgl" \
  patcher:LwjglMemoryPatcher \
  guard:LwjglArchitectureGuard \
  guard:LwjglPlatformGuard \
  patcher:LwjglUnsafeAccessPatcher \
  patcher:NativeMethodFallbackPatcher \
  patcher:LwjglAPIUtilBrowserPatcher \
  callbacks

# The remaining LWJGL modules, one row each:
#   <module>|<presence>|<overlay>|<condition>|<steps>
# presence required: every profile ships the module, and a profile whose
# version.json lacks it fails the build.  optional: the module is not part of
# every profile (glfw and tinyfd are absent from 26.3, sdl exists only in
# 26.3, vma/vulkan/shaderc/spvc are absent from 1.21.11); an absent optional
# module prints "Skipping LWJGL module <module>: ...", which
# check-build-log-skips.mjs (G4) reports unless the caller allows exactly
# the modules that profile is expected to lack.
# overlay yes compiles port/overrides/libraries/<module> into the jar first
# (the vanilla jar is copied while that directory has no sources for the
# module's version); no copies the vanilla jar.
# condition renderpearl additionally requires the renderpearl render API in
# the client jar: its OpenGL path compiles shaders through shaderc and spvc,
# while 26.2 reaches them only from the unused Vulkan backend and keeps those
# two jars unmodified (they are skipped, like an absent module).
lwjgl_module_steps=(
  "lwjgl-glfw|optional|yes|always|patcher:LwjglGlfwBrowserPatcher patcher:LwjglUnsafeAccessPatcher callbacks"
  "lwjgl-sdl|optional|yes|always|patcher:LwjglSdlBrowserPatcher patcher:LwjglUnsafeAccessPatcher patcher:NativeMethodFallbackPatcher callbacks"
  "lwjgl-opengl|required|yes|always|patcher:LwjglOpenGLBrowserPatcher patcher:LwjglUnsafeAccessPatcher patcher:NativeMethodFallbackPatcher callbacks"
  "lwjgl-freetype|required|no|always|patcher:LwjglUnsafeAccessPatcher patcher:NativeMethodFallbackPatcher callbacks"
  "lwjgl-stb|required|yes|always|patcher:LwjglUnsafeAccessPatcher patcher:NativeMethodFallbackPatcher callbacks"
  "lwjgl-openal|required|yes|always|patcher:LwjglOpenALBrowserPatcher patcher:NativeMethodFallbackPatcher callbacks"
  "lwjgl-tinyfd|optional|no|always|patcher:NativeMethodFallbackPatcher callbacks"
  # Minecraft ships a Vulkan fallback. The browser runtime always selects
  # WebGL/OpenGL, but these modules must still be link-safe if TeaVM sees a
  # stale reference while analysing the desktop backend.
  "lwjgl-vma|optional|no|always|patcher:LwjglUnsafeAccessPatcher unsupported:LwjglUnsupportedNativePatcher callbacks"
  "lwjgl-vulkan|optional|no|always|patcher:LwjglUnsafeAccessPatcher unsupported:LwjglUnsupportedNativePatcher callbacks"
  # D5/D9: the browser shims BrowserShaderc/BrowserSpvc live in
  # port/overrides/libraries/{lwjgl-shaderc,lwjgl-spvc} (work package P4).
  "lwjgl-shaderc|optional|yes|renderpearl|patcher:LwjglShadercBrowserPatcher callbacks"
  "lwjgl-spvc|optional|yes|renderpearl|patcher:LwjglSpvcBrowserPatcher callbacks"
)

# The renderpearl condition reads the vanilla client jar.  unzip exits 11 when
# no entry matches; any other failure stops the build instead of being taken
# for "no renderpearl".
client_uses_renderpearl=false
if command -v unzip >/dev/null 2>&1; then
  renderpearl_status=0
  unzip -Z1 "$work/client-named.jar" 'com/mojang/renderpearl/*' >/dev/null 2>&1 \
    || renderpearl_status="$?"
  case "$renderpearl_status" in
    0) client_uses_renderpearl=true ;;
    11) ;;
    *)
      echo "Cannot list $work/client-named.jar (unzip exit $renderpearl_status)" >&2
      exit 1
      ;;
  esac
else
  client_listing="$(jar --list --file "$work/client-named.jar")"
  if grep -q '^com/mojang/renderpearl/' <<<"$client_listing"; then
    client_uses_renderpearl=true
  fi
fi
echo "LWJGL module steps for Minecraft $version (renderpearl client: $client_uses_renderpearl)"

for module_row in "${lwjgl_module_steps[@]}"; do
  IFS='|' read -r lwjgl_module module_presence module_overlay module_condition module_steps \
    <<<"$module_row"
  if [[ ! "$module_presence" =~ ^(required|optional)$ || ! "$module_overlay" =~ ^(yes|no)$ \
      || ! "$module_condition" =~ ^(always|renderpearl)$ || -z "$module_steps" ]]; then
    echo "Invalid LWJGL step table row: $module_row" >&2
    exit 1
  fi
  module_path="$(gaius_library_path_optional "org.lwjgl:$lwjgl_module")"
  if [[ -z "$module_path" ]]; then
    if [[ "$module_presence" == required ]]; then
      echo "Required LWJGL module $lwjgl_module is missing from $GAIUS_VERSION_METADATA" >&2
      exit 1
    fi
    echo "Skipping LWJGL module $lwjgl_module: not in the Minecraft $version libraries"
    continue
  fi
  if [[ "$module_condition" == renderpearl && "$client_uses_renderpearl" != true ]]; then
    echo "Skipping LWJGL module $lwjgl_module: the Minecraft $version client has no renderpearl OpenGL path"
    continue
  fi
  module_output="$overlay_work/libraries/$module_path"
  module_patches="$overlay_work/library-patches/$lwjgl_module"
  if [[ "$module_overlay" == yes ]]; then
    build_library_overlay \
      "$lwjgl_module" \
      "$work/libraries/$module_path" \
      "$root/port/overrides/libraries/$lwjgl_module/src/main/java" \
      "$module_output"
  else
    mkdir -p "$(dirname "$module_output")"
    cp "$work/libraries/$module_path" "$module_output"
  fi
  # shellcheck disable=SC2086 # the step list is a space-separated word list
  run_lwjgl_steps "$lwjgl_module" "$module_output" "$module_patches" $module_steps
done

jtracy_native_patches="$overlay_work/library-patches/jtracy-native"
mkdir -p "$jtracy_native_patches"
find "$jtracy_native_patches" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.NativeMethodFallbackPatcher \
  "$overlay_work/libraries/$jtracy_path" \
  "$jtracy_native_patches"
jar --update \
  --file "$overlay_work/libraries/$jtracy_path" \
  -C "$jtracy_native_patches" .

jtracy_browser_patches="$overlay_work/library-patches/jtracy-browser"
mkdir -p "$jtracy_browser_patches"
find "$jtracy_browser_patches" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.JtracyBrowserPatcher \
  "$overlay_work/libraries/$jtracy_path" \
  "$jtracy_browser_patches"
jar --update \
  --file "$overlay_work/libraries/$jtracy_path" \
  -C "$jtracy_browser_patches" .

client_output="$overlay_work/client-named-$version-gaius.jar"
client_patch_classes="$overlay_work/client-patches"
client_override_classes="$overlay_work/client-override-classes"
client_override_root="$root/port/overrides/client/src/main/java"
client_version_override_root="$root/port/overrides/client/src/versions/$version/java"
client_version_excludes="$root/port/overrides/client/src/versions/$version/excludes.txt"
mkdir -p "$client_patch_classes"
find "$client_patch_classes" -type f -delete
cp "$work/client-named.jar" "$client_output"
# TeaVM resolves the client overlay before generated browser resources. Keep the
# complete vanilla asset set, but remove only the two generated Unicode font
# definitions so build-teavm.sh can replace their stale JAR stubs without
# dropping core files such as en_us.json from the browser resource table.
zip -q -d "$client_output" \
  assets/minecraft/font/include/unifont.json \
  assets/minecraft/font/include/unifont_pua.json >/dev/null 2>&1 || true
mkdir -p "$client_override_classes"
find "$client_override_classes" -type f -delete
client_override_sources=()
client_excludes="$(gaius_version_excludes "$client_version_excludes" "$client_override_root")"
while IFS= read -r source; do
  relative_source="${source#"$client_override_root/"}"
  if [[ -f "$client_version_override_root/$relative_source" ]]; then
    continue
  fi
  if [[ -n "$client_excludes" ]] \
      && grep -Fqx -e "$relative_source" <<<"$client_excludes"; then
    continue
  fi
  client_override_sources+=("$source")
done < <(find "$client_override_root" -type f -name '*.java' -print | sort)
if [[ -d "$client_version_override_root" ]]; then
  while IFS= read -r source; do
    client_override_sources+=("$source")
  done < <(find "$client_version_override_root" -type f -name '*.java' -print | sort)
fi
echo "Compiling ${#client_override_sources[@]} Minecraft $version browser overrides"
client_override_classpath="$java_client_jar${java_classpath_separator}$java_work_classpath"
for artifact in teavm-interop teavm-jso teavm-jso-apis; do
  client_override_classpath="$client_override_classpath${java_classpath_separator}${java_maven_repository}/org/teavm/$artifact/$teavm_version/$artifact-$teavm_version.jar"
done
javac_with_source_argfile "$overlay_work/client-override-sources.argfile" \
  --release 21 -proc:none \
  -classpath "$client_override_classpath" \
  -d "$client_override_classes" \
  -- "${client_override_sources[@]}"
jar --update --file "$client_output" -C "$client_override_classes" .

# One client patcher step: run dev.gaius.tools.<Class> with the PatchRegistry
# properties, then fold everything written so far into the client jar.  Each
# patcher reads the jar written by the previous one.  In bring-up mode a whole
# step can be skipped as "step:<Class>".
run_client_patcher() {
  local class="$1"
  shift
  if bringup_skip "step:$class"; then
    return 0
  fi
  require_tool_class "$class" "step:$class"
  java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
    "${patch_registry_properties[@]}" \
    "dev.gaius.tools.$class" \
    "$@"
  jar --update \
    --file "$client_output" \
    -C "$client_patch_classes" .
}

java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.AbstractSpliteratorBrowserPatcher \
  "$client_output" \
  "$client_patch_classes"
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  "${patch_registry_properties[@]}" \
  dev.gaius.tools.MinecraftClientPatcher \
  "$client_output" \
  "$client_patch_classes" \
  "$version"
jar --update \
  --file "$client_output" \
  -C "$client_patch_classes" .
case "$patch_set" in
  modern)
    # Named profiles. Diagnostic hook: map prepared chunk sections to successful draws.
    # Opt-in at runtime via globalThis.__gaiusChunkDrawTelemetryEnabled; this
    # does not touch terrain selection, upload budgets, textures, or mipmaps.
    run_client_patcher MinecraftChunkDrawTelemetryPatcher \
      "$version" \
      "$client_output" \
      "$client_patch_classes"
    run_client_patcher Minecraft262BrowserPatcher \
      "$client_output" \
      "$client_patch_classes" \
      "$version"
    run_client_patcher MinecraftServerWorkerPatcher \
      "$client_output" \
      "$client_patch_classes"
    ;;
  legacy-12111)
    # 1.21.11 keeps deep worldgen synchronous.  Its checkpoint-only contract
    # is implemented by the task-layer holder cursor, not by adding scheduler
    # pulse/checkpoint calls to ChunkGenerationTask or any deep hot class.
    run_client_patcher Minecraft12111BrowserPatcher \
      "$client_output" \
      "$client_patch_classes"
    run_client_patcher MinecraftServerWorkerPatcher \
      "$client_output" \
      "$client_patch_classes"
    ;;
esac
# Profile-specific patchers (for example Minecraft263BrowserPatcher) run last,
# in the order the profile lists them.
for extra_patcher in $GAIUS_EXTRA_PATCHERS; do
  run_client_patcher "$extra_patcher" \
    "$client_output" \
    "$client_patch_classes" \
    "$version"
done

if [[ "$bringup_active" == true ]]; then
  echo "BRINGUP_STEPS skipped=$bringup_skipped"
else
  rm -f "$bringup_marker"
fi
echo "$output"
