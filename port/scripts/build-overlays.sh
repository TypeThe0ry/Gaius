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
version="$GAIUS_MINECRAFT_VERSION"
teavm_version="$(jq -er '.teaVMVersion' "$config")"
work="$root/port/work/$version"
overlay_work="$(gaius_overlay_directory "$root")"
source_root="$root/port/overrides/classlib/src/main/java"
classes="$overlay_work/classlib-classes"
maven_repository="$(gaius_maven_repository "$root")"
maven_repository_for_java="$(gaius_maven_repository_for_java "$root")"
export GAIUS_MAVEN_REPOSITORY="$maven_repository"
asm_version="9.8"

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

upstream="$maven_repository/org/teavm/teavm-classlib/$teavm_version/teavm-classlib-$teavm_version.jar"
output="$overlay_work/teavm-classlib-$teavm_version-gaius.jar"

mkdir -p "$classes" "$overlay_work"
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

classpath="$upstream"
for artifact in teavm-interop teavm-jso teavm-jso-apis teavm-core teavm-platform; do
  classpath="$classpath:$maven_repository/org/teavm/$artifact/$teavm_version/$artifact-$teavm_version.jar"
done
classpath="$classpath:$maven_repository/com/jcraft/jzlib/1.1.3/jzlib-1.1.3.jar"
classpath="$classpath:$(cat "$work/classpath.txt")"

javac --release 21 -proc:none -classpath "$classpath" -d "$classes" "${sources[@]}"
cp "$upstream" "$output"
jar --update --file "$output" -C "$classes" .

build_library_overlay() {
  local name="$1"
  local source_jar="$2"
  local source_dir="$3"
  local output_jar="$4"
  if [[ ! -d "$source_dir" ]]; then
    echo "Using unmodified $name base: no source overrides at $source_dir"
    mkdir -p "$(dirname "$output_jar")"
    cp "$source_jar" "$output_jar"
    return 0
  fi
  local output_classes="$overlay_work/library-classes/$name"
  local compile_classpath="$source_jar:$work/client-named.jar:$(cat "$work/classpath.txt")"
  for artifact in teavm-interop teavm-jso teavm-jso-apis teavm-platform; do
    compile_classpath="$compile_classpath:$maven_repository/org/teavm/$artifact/$teavm_version/$artifact-$teavm_version.jar"
  done
  local library_sources=()

  while IFS= read -r source; do
    library_sources+=("$source")
  done < <(find "$source_dir" -type f -name '*.java' -print | sort)

  if [[ "${#library_sources[@]}" -eq 0 ]]; then
    echo "Skipping $name overlay: no Java sources at $source_dir"
    return 0
  fi

  mkdir -p "$output_classes" "$(dirname "$output_jar")"
  find "$output_classes" -type f -delete
  javac --release 21 -proc:none -classpath "$compile_classpath" \
    -d "$output_classes" "${library_sources[@]}"
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
javac --release 21 -proc:none \
  -classpath "$asm_jar:$asm_tree_jar" \
  -d "$tool_classes" \
  "$root/port/tools/src/main/java/dev/gaius/tools/"*.java

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

authlib_path="$(gaius_library_path "com.mojang:authlib")"
authlib_output="$overlay_work/libraries/$authlib_path"
authlib_patch_classes="$overlay_work/library-patches/authlib"
mkdir -p "$(dirname "$authlib_output")" "$authlib_patch_classes"
find "$authlib_patch_classes" -type f -delete
cp "$work/libraries/$authlib_path" "$authlib_output"
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.AuthlibBrowserPatcher \
  "$authlib_output" \
  "$authlib_patch_classes/com/mojang/authlib/minecraft/client/MinecraftClient.class"
jar --update \
  --file "$authlib_output" \
  -C "$authlib_patch_classes" com/mojang/authlib/minecraft/client/MinecraftClient.class \
  -C "$authlib_patch_classes" com/mojang/authlib/minecraft/MinecraftProfileTexture.class \
  -C "$authlib_patch_classes" com/mojang/authlib/yggdrasil/response/MinecraftTexturesPayload.class \
  -C "$authlib_patch_classes" com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService.class

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

lwjgl_patch_classes="$overlay_work/library-patches/lwjgl"
mkdir -p "$lwjgl_patch_classes"
find "$lwjgl_patch_classes" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.LwjglMemoryPatcher \
  "$overlay_work/libraries/$lwjgl_path" \
  "$lwjgl_patch_classes"
jar --update \
  --file "$overlay_work/libraries/$lwjgl_path" \
  -C "$lwjgl_patch_classes" .
if ! javap -classpath "$overlay_work/libraries/$lwjgl_path" -c -p \
    'org.lwjgl.system.Platform$Architecture' | grep -Fq 'String wasm64'; then
  echo "LWJGL browser architecture patch is missing" >&2
  exit 1
fi
echo "Verified LWJGL browser architecture identity"
find "$lwjgl_patch_classes" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.LwjglUnsafeAccessPatcher \
  "$overlay_work/libraries/$lwjgl_path" \
  "$lwjgl_patch_classes"
jar --update \
  --file "$overlay_work/libraries/$lwjgl_path" \
  -C "$lwjgl_patch_classes" .
find "$lwjgl_patch_classes" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.NativeMethodFallbackPatcher \
  "$overlay_work/libraries/$lwjgl_path" \
  "$lwjgl_patch_classes"
jar --update \
  --file "$overlay_work/libraries/$lwjgl_path" \
  -C "$lwjgl_patch_classes" .
find "$lwjgl_patch_classes" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.LwjglAPIUtilBrowserPatcher \
  "$overlay_work/libraries/$lwjgl_path" \
  "$lwjgl_patch_classes"
jar --update \
  --file "$overlay_work/libraries/$lwjgl_path" \
  -C "$lwjgl_patch_classes" .
patch_lwjgl_callback_descriptors \
  "$overlay_work/libraries/$lwjgl_path" \
  "$lwjgl_patch_classes"

glfw_path="$(gaius_library_path "org.lwjgl:lwjgl-glfw")"
build_library_overlay \
  lwjgl-glfw \
  "$work/libraries/$glfw_path" \
  "$root/port/overrides/libraries/lwjgl-glfw/src/main/java" \
  "$overlay_work/libraries/$glfw_path"
glfw_patches="$overlay_work/library-patches/lwjgl-glfw"
mkdir -p "$glfw_patches"
find "$glfw_patches" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.LwjglGlfwBrowserPatcher \
  "$overlay_work/libraries/$glfw_path" \
  "$glfw_patches"
jar --update \
  --file "$overlay_work/libraries/$glfw_path" \
  -C "$glfw_patches" .
find "$glfw_patches" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.LwjglUnsafeAccessPatcher \
  "$overlay_work/libraries/$glfw_path" \
  "$glfw_patches"
jar --update \
  --file "$overlay_work/libraries/$glfw_path" \
  -C "$glfw_patches" .
patch_lwjgl_callback_descriptors \
  "$overlay_work/libraries/$glfw_path" \
  "$glfw_patches"

opengl_path="$(gaius_library_path "org.lwjgl:lwjgl-opengl")"
build_library_overlay \
  lwjgl-opengl \
  "$work/libraries/$opengl_path" \
  "$root/port/overrides/libraries/lwjgl-opengl/src/main/java" \
  "$overlay_work/libraries/$opengl_path"
opengl_patches="$overlay_work/library-patches/lwjgl-opengl"
mkdir -p "$opengl_patches"
find "$opengl_patches" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.LwjglOpenGLBrowserPatcher \
  "$overlay_work/libraries/$opengl_path" \
  "$opengl_patches"
jar --update \
  --file "$overlay_work/libraries/$opengl_path" \
  -C "$opengl_patches" .
find "$opengl_patches" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.LwjglUnsafeAccessPatcher \
  "$overlay_work/libraries/$opengl_path" \
  "$opengl_patches"
jar --update \
  --file "$overlay_work/libraries/$opengl_path" \
  -C "$opengl_patches" .
find "$opengl_patches" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.NativeMethodFallbackPatcher \
  "$overlay_work/libraries/$opengl_path" \
  "$opengl_patches"
jar --update \
  --file "$overlay_work/libraries/$opengl_path" \
  -C "$opengl_patches" .
patch_lwjgl_callback_descriptors \
  "$overlay_work/libraries/$opengl_path" \
  "$opengl_patches"

for lwjgl_module in lwjgl-freetype; do
  module_path="$(gaius_library_path "org.lwjgl:$lwjgl_module")"
  module_output="$overlay_work/libraries/$module_path"
  module_patches="$overlay_work/library-patches/$lwjgl_module"
  mkdir -p "$(dirname "$module_output")" "$module_patches"
  find "$module_patches" -type f -delete
  cp "$work/libraries/$module_path" "$module_output"
  java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
    dev.gaius.tools.LwjglUnsafeAccessPatcher \
    "$module_output" \
    "$module_patches"
  jar --update --file "$module_output" -C "$module_patches" .
  find "$module_patches" -type f -delete
  java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
    dev.gaius.tools.NativeMethodFallbackPatcher \
    "$module_output" \
    "$module_patches"
  jar --update --file "$module_output" -C "$module_patches" .
  patch_lwjgl_callback_descriptors "$module_output" "$module_patches"
done

stb_path="$(gaius_library_path "org.lwjgl:lwjgl-stb")"
build_library_overlay \
  lwjgl-stb \
  "$work/libraries/$stb_path" \
  "$root/port/overrides/libraries/lwjgl-stb/src/main/java" \
  "$overlay_work/libraries/$stb_path"
stb_patches="$overlay_work/library-patches/lwjgl-stb"
mkdir -p "$stb_patches"
find "$stb_patches" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.LwjglUnsafeAccessPatcher \
  "$overlay_work/libraries/$stb_path" \
  "$stb_patches"
jar --update --file "$overlay_work/libraries/$stb_path" -C "$stb_patches" .
find "$stb_patches" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.NativeMethodFallbackPatcher \
  "$overlay_work/libraries/$stb_path" \
  "$stb_patches"
jar --update --file "$overlay_work/libraries/$stb_path" -C "$stb_patches" .
patch_lwjgl_callback_descriptors \
  "$overlay_work/libraries/$stb_path" \
  "$stb_patches"

openal_path="$(gaius_library_path "org.lwjgl:lwjgl-openal")"
build_library_overlay \
  lwjgl-openal \
  "$work/libraries/$openal_path" \
  "$root/port/overrides/libraries/lwjgl-openal/src/main/java" \
  "$overlay_work/libraries/$openal_path"
openal_patches="$overlay_work/library-patches/lwjgl-openal"
mkdir -p "$openal_patches"
find "$openal_patches" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.LwjglOpenALBrowserPatcher \
  "$overlay_work/libraries/$openal_path" \
  "$openal_patches"
jar --update --file "$overlay_work/libraries/$openal_path" -C "$openal_patches" .
find "$openal_patches" -type f -delete
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.NativeMethodFallbackPatcher \
  "$overlay_work/libraries/$openal_path" \
  "$openal_patches"
jar --update --file "$overlay_work/libraries/$openal_path" -C "$openal_patches" .
patch_lwjgl_callback_descriptors \
  "$overlay_work/libraries/$openal_path" \
  "$openal_patches"

for lwjgl_module in lwjgl-tinyfd; do
  module_path="$(gaius_library_path "org.lwjgl:$lwjgl_module")"
  module_output="$overlay_work/libraries/$module_path"
  module_patches="$overlay_work/library-patches/$lwjgl_module"
  mkdir -p "$(dirname "$module_output")" "$module_patches"
  find "$module_patches" -type f -delete
  cp "$work/libraries/$module_path" "$module_output"
  java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
    dev.gaius.tools.NativeMethodFallbackPatcher \
    "$module_output" \
    "$module_patches"
  jar --update --file "$module_output" -C "$module_patches" .
  patch_lwjgl_callback_descriptors "$module_output" "$module_patches"
done

# Minecraft 26.2 ships a Vulkan fallback. The browser runtime always selects
# WebGL/OpenGL, but these modules must still be link-safe if TeaVM sees a stale
# reference while analysing the desktop backend.
for lwjgl_module in lwjgl-vma lwjgl-vulkan; do
  if ! module_path="$(gaius_library_path "org.lwjgl:$lwjgl_module" 2>/dev/null)"; then
    continue
  fi
  module_output="$overlay_work/libraries/$module_path"
  module_patches="$overlay_work/library-patches/$lwjgl_module"
  mkdir -p "$(dirname "$module_output")" "$module_patches"
  find "$module_patches" -type f -delete
  cp "$work/libraries/$module_path" "$module_output"
  java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
    dev.gaius.tools.LwjglUnsafeAccessPatcher \
    "$module_output" \
    "$module_patches"
  jar --update --file "$module_output" -C "$module_patches" .
  find "$module_patches" -type f -delete
  java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
    dev.gaius.tools.LwjglUnsupportedNativePatcher \
    "$module_output" \
    "$module_patches" \
    "$lwjgl_module"
  jar --update --file "$module_output" -C "$module_patches" .
  patch_lwjgl_callback_descriptors "$module_output" "$module_patches"
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
while IFS= read -r source; do
  relative_source="${source#"$client_override_root/"}"
  if [[ -f "$client_version_override_root/$relative_source" ]]; then
    continue
  fi
  if [[ -f "$client_version_excludes" ]] \
      && grep -Fqx "$relative_source" "$client_version_excludes"; then
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
client_override_classpath="$work/client-named.jar:$(cat "$work/classpath.txt")"
for artifact in teavm-interop teavm-jso teavm-jso-apis; do
  client_override_classpath="$client_override_classpath:$maven_repository/org/teavm/$artifact/$teavm_version/$artifact-$teavm_version.jar"
done
javac --release 21 -proc:none \
  -classpath "$client_override_classpath" \
  -d "$client_override_classes" \
  "${client_override_sources[@]}"
jar --update --file "$client_output" -C "$client_override_classes" .
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.AbstractSpliteratorBrowserPatcher \
  "$client_output" \
  "$client_patch_classes"
java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
  dev.gaius.tools.MinecraftClientPatcher \
  "$client_output" \
  "$client_patch_classes" \
  "$version"
jar --update \
  --file "$client_output" \
  -C "$client_patch_classes" .
if [[ "$version" == "26.2" ]]; then
  java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
    dev.gaius.tools.Minecraft262BrowserPatcher \
    "$client_output" \
    "$client_patch_classes"
  jar --update \
    --file "$client_output" \
    -C "$client_patch_classes" .
  java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
    dev.gaius.tools.MinecraftServerWorkerPatcher \
    "$client_output" \
    "$client_patch_classes"
  jar --update \
    --file "$client_output" \
    -C "$client_patch_classes" net/minecraft/server/jsonrpc/JsonRpc.class
elif [[ "$version" == "1.21.11" ]]; then
  # 1.21.11 keeps deep worldgen synchronous.  Its checkpoint-only contract
  # is implemented by the task-layer holder cursor, not by adding scheduler
  # pulse/checkpoint calls to ChunkGenerationTask or any deep hot class.
  java -classpath "$tool_classes:$asm_jar:$asm_tree_jar" \
    dev.gaius.tools.Minecraft12111BrowserPatcher \
    "$client_output" \
    "$client_patch_classes"
  jar --update \
    --file "$client_output" \
    -C "$client_patch_classes" .
fi

echo "$output"
