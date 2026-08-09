#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
config="$root/port/config.json"
source "$root/port/scripts/version-profile.sh"
gaius_load_version_profile "$root"
version="$GAIUS_MINECRAFT_VERSION"
teavm_version="$(jq -er '.teaVMVersion' "$config")"
work="$root/port/work/$version"
overlay_work="$root/port/work/overlays"
source_root="$root/port/overrides/classlib/src/main/java"
classes="$overlay_work/classlib-classes"
upstream="$HOME/.m2/repository/org/teavm/teavm-classlib/$teavm_version/teavm-classlib-$teavm_version.jar"
output="$overlay_work/teavm-classlib-$teavm_version-gaius.jar"

if [[ ! -f "$upstream" ]]; then
  echo "TeaVM classlib is missing; run ./port/mvnw validate once first" >&2
  exit 1
fi

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
  classpath="$classpath:$HOME/.m2/repository/org/teavm/$artifact/$teavm_version/$artifact-$teavm_version.jar"
done
classpath="$classpath:$HOME/.m2/repository/com/jcraft/jzlib/1.1.3/jzlib-1.1.3.jar"
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
  for artifact in teavm-interop teavm-jso teavm-jso-apis; do
    compile_classpath="$compile_classpath:$HOME/.m2/repository/org/teavm/$artifact/$teavm_version/$artifact-$teavm_version.jar"
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

jtracy_path="com/mojang/jtracy/1.0.37/jtracy-1.0.37.jar"
build_library_overlay \
  jtracy \
  "$work/libraries/$jtracy_path" \
  "$root/port/overrides/libraries/jtracy/src/main/java" \
  "$overlay_work/libraries/$jtracy_path"

oshi_path="com/github/oshi/oshi-core/6.9.0/oshi-core-6.9.0.jar"
build_library_overlay \
  oshi \
  "$work/libraries/$oshi_path" \
  "$root/port/overrides/libraries/oshi/src/main/java" \
  "$overlay_work/libraries/$oshi_path"

slf4j_path="org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar"
build_library_overlay \
  slf4j \
  "$work/libraries/$slf4j_path" \
  "$root/port/overrides/libraries/slf4j/src/main/java" \
  "$overlay_work/libraries/$slf4j_path"

gson_path="com/google/code/gson/gson/2.13.2/gson-2.13.2.jar"
build_library_overlay \
  gson \
  "$work/libraries/$gson_path" \
  "$root/port/overrides/libraries/gson/src/main/java" \
  "$overlay_work/libraries/$gson_path"

mojang_logging_path="com/mojang/logging/1.6.11/logging-1.6.11.jar"
build_library_overlay \
  mojang-logging \
  "$work/libraries/$mojang_logging_path" \
  "$root/port/overrides/libraries/mojang-logging/src/main/java" \
  "$overlay_work/libraries/$mojang_logging_path"

joml_path="org/joml/joml/1.10.8/joml-1.10.8.jar"
build_library_overlay \
  joml \
  "$work/libraries/$joml_path" \
  "$root/port/overrides/libraries/joml/src/main/java" \
  "$overlay_work/libraries/$joml_path"

jopt_simple_path="net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar"
build_library_overlay \
  jopt-simple \
  "$work/libraries/$jopt_simple_path" \
  "$root/port/overrides/libraries/jopt-simple/src/main/java" \
  "$overlay_work/libraries/$jopt_simple_path"

lwjgl_path="org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar"
build_library_overlay \
  lwjgl \
  "$work/libraries/$lwjgl_path" \
  "$root/port/overrides/libraries/lwjgl/src/main/java" \
  "$overlay_work/libraries/$lwjgl_path"

text2speech_path="com/mojang/text2speech/1.18.11/text2speech-1.18.11.jar"
text2speech_output="$overlay_work/libraries/$text2speech_path"

tool_classes="$overlay_work/tool-classes"
asm_version="9.8"
asm_jar="$HOME/.m2/repository/org/ow2/asm/asm/$asm_version/asm-$asm_version.jar"
asm_tree_jar="$HOME/.m2/repository/org/ow2/asm/asm-tree/$asm_version/asm-tree-$asm_version.jar"
mkdir -p "$tool_classes"
find "$tool_classes" -type f -delete
javac --release 21 -proc:none \
  -classpath "$asm_jar:$asm_tree_jar" \
  -d "$tool_classes" \
  "$root/port/tools/src/main/java/dev/gaius/tools/"*.java
teavm_core="$HOME/.m2/repository/org/teavm/teavm-core/$teavm_version/teavm-core-$teavm_version.jar"
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

authlib_path="com/mojang/authlib/7.0.61/authlib-7.0.61.jar"
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

patchy_path="com/mojang/patchy/2.2.10/patchy-2.2.10.jar"
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

guava_path="com/google/guava/guava/33.5.0-jre/guava-33.5.0-jre.jar"
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

netty_common_path="io/netty/netty-common/4.2.7.Final/netty-common-4.2.7.Final.jar"
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

netty_buffer_path="io/netty/netty-buffer/4.2.7.Final/netty-buffer-4.2.7.Final.jar"
netty_buffer_output="$overlay_work/libraries/$netty_buffer_path"
netty_buffer_patch_classes="$overlay_work/library-patches/netty-buffer"
mkdir -p "$(dirname "$netty_buffer_output")" "$netty_buffer_patch_classes"
find "$netty_buffer_patch_classes" -type f -delete
build_library_overlay \
  netty-buffer \
  "$work/libraries/$netty_buffer_path" \
  "$root/port/overrides/libraries/netty-buffer/src/main/java" \
  "$netty_buffer_output"
netty_transport_path="io/netty/netty-transport/4.2.7.Final/netty-transport-4.2.7.Final.jar"
netty_transport_output="$overlay_work/libraries/$netty_transport_path"
netty_transport_patch_classes="$overlay_work/library-patches/netty-transport"
mkdir -p "$(dirname "$netty_transport_output")" "$netty_transport_patch_classes"
find "$netty_transport_patch_classes" -type f -delete
build_library_overlay \
  netty-transport \
  "$work/libraries/$netty_transport_path" \
  "$root/port/overrides/libraries/netty-transport/src/main/java" \
  "$netty_transport_output"
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

commons_io_path="commons-io/commons-io/2.20.0/commons-io-2.20.0.jar"
commons_compress_path="org/apache/commons/commons-compress/1.28.0/commons-compress-1.28.0.jar"
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

icu_path="com/ibm/icu/icu4j/77.1/icu4j-77.1.jar"
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

glfw_path="org/lwjgl/lwjgl-glfw/3.3.3/lwjgl-glfw-3.3.3.jar"
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

opengl_path="org/lwjgl/lwjgl-opengl/3.3.3/lwjgl-opengl-3.3.3.jar"
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

for lwjgl_module in lwjgl-freetype; do
  module_path="org/lwjgl/$lwjgl_module/3.3.3/$lwjgl_module-3.3.3.jar"
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
done

stb_path="org/lwjgl/lwjgl-stb/3.3.3/lwjgl-stb-3.3.3.jar"
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

openal_path="org/lwjgl/lwjgl-openal/3.3.3/lwjgl-openal-3.3.3.jar"
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

for lwjgl_module in lwjgl-tinyfd; do
  module_path="org/lwjgl/$lwjgl_module/3.3.3/$lwjgl_module-3.3.3.jar"
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
  client_override_sources+=("$source")
done < <(find "$root/port/overrides/client/src/main/java" -type f -name '*.java' -print | sort)
client_override_classpath="$work/client-named.jar:$(cat "$work/classpath.txt")"
for artifact in teavm-interop teavm-jso teavm-jso-apis; do
  client_override_classpath="$client_override_classpath:$HOME/.m2/repository/org/teavm/$artifact/$teavm_version/$artifact-$teavm_version.jar"
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
  "$client_patch_classes"
jar --update \
  --file "$client_output" \
  -C "$client_patch_classes" .

echo "$output"
