#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
config="$root/port/config.json"
version="$(jq -er '.minecraftVersion' "$config")"
teavm_version="$(jq -er '.teaVMVersion' "$config")"
work="$root/port/work/$version"
metadata="$work/version.json"
client="$root/port/work/overlays/client-named-$version-gaius.jar"
output="${GAIUS_POM:-$root/port/target/generated-pom.xml}"
main_class="${GAIUS_MAIN_CLASS:-net.minecraft.client.main.Main}"
target_directory="${GAIUS_TARGET_DIRECTORY:-$root/port/web/dist}"
target_file="${GAIUS_TARGET_FILE:-classes.js}"
patched_classlib="$root/port/work/overlays/teavm-classlib-$teavm_version-gaius.jar"
optimization_level="${GAIUS_TEA_OPTIMIZATION_LEVEL:-SIMPLE}"
source_maps_generated="${GAIUS_SOURCE_MAPS:-true}"
debug_information_generated="${GAIUS_DEBUG_INFO:-true}"
minifying="${GAIUS_MINIFYING:-false}"
short_file_names="${GAIUS_SHORT_FILE_NAMES:-false}"
assertions_removed="${GAIUS_ASSERTIONS_REMOVED:-false}"

case "$optimization_level" in
  SIMPLE|ADVANCED|FULL) ;;
  *)
    echo "Invalid GAIUS_TEA_OPTIMIZATION_LEVEL: $optimization_level (expected SIMPLE, ADVANCED, or FULL)" >&2
    exit 1
    ;;
esac

for boolean_name in source_maps_generated debug_information_generated minifying short_file_names assertions_removed; do
  boolean_value="${!boolean_name}"
  case "$boolean_value" in
    true|false) ;;
    *)
      echo "Invalid boolean value for $boolean_name: $boolean_value (expected true or false)" >&2
      exit 1
      ;;
  esac
done

if [[ ! -f "$metadata" || ! -f "$client" || ! -f "$patched_classlib" ]]; then
  echo "Run fetch-version.sh and remap-client.sh first" >&2
  echo "Then run build-overlays.sh" >&2
  exit 1
fi

mkdir -p "$(dirname "$output")" "$root/port/src/main/java" \
  "$root/port/web/dist"

{
  cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.gaius</groupId>
  <artifactId>minecraft-1.21.11-browser</artifactId>
  <version>0.1.0-SNAPSHOT</version>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>21</maven.compiler.release>
    <teavm.version>$teavm_version</teavm.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.teavm</groupId>
      <artifactId>teavm-interop</artifactId>
      <version>\${teavm.version}</version>
    </dependency>
    <dependency>
      <groupId>org.teavm</groupId>
      <artifactId>teavm-jso</artifactId>
      <version>\${teavm.version}</version>
    </dependency>
    <dependency>
      <groupId>org.teavm</groupId>
      <artifactId>teavm-jso-apis</artifactId>
      <version>\${teavm.version}</version>
    </dependency>
    <dependency>
      <groupId>dev.gaius.teavm</groupId>
      <artifactId>teavm-classlib-overlay</artifactId>
      <version>\${teavm.version}</version>
      <scope>system</scope>
      <systemPath>$patched_classlib</systemPath>
    </dependency>
    <dependency>
      <groupId>org.teavm</groupId>
      <artifactId>teavm-core</artifactId>
      <version>\${teavm.version}</version>
    </dependency>
    <dependency>
      <groupId>org.teavm</groupId>
      <artifactId>teavm-platform</artifactId>
      <version>\${teavm.version}</version>
    </dependency>
    <dependency>
      <groupId>org.teavm</groupId>
      <artifactId>teavm-jso-impl</artifactId>
      <version>\${teavm.version}</version>
    </dependency>
    <dependency>
      <groupId>org.teavm</groupId>
      <artifactId>teavm-metaprogramming-impl</artifactId>
      <version>\${teavm.version}</version>
    </dependency>
    <dependency>
      <groupId>com.jcraft</groupId>
      <artifactId>jzlib</artifactId>
      <version>1.1.3</version>
    </dependency>
    <dependency>
      <groupId>joda-time</groupId>
      <artifactId>joda-time</artifactId>
      <version>2.12.2</version>
    </dependency>
    <dependency>
      <groupId>dev.gaius.minecraft</groupId>
      <artifactId>client-named</artifactId>
      <version>$version</version>
      <scope>system</scope>
      <systemPath>$client</systemPath>
    </dependency>
EOF

  index=0
  while IFS= read -r library; do
    printf '    <dependency>\n'
    printf '      <groupId>dev.gaius.minecraft.library</groupId>\n'
    printf '      <artifactId>library-%03d</artifactId>\n' "$index"
    printf '      <version>1</version>\n'
    printf '      <scope>system</scope>\n'
    printf '      <systemPath>%s</systemPath>\n' "$library"
    printf '    </dependency>\n'
    index=$((index + 1))
  done < <(
    jq -r '
      .libraries[]
      | select((.name | split(":") | length) == 3)
      | .downloads.artifact.path
    ' "$metadata" |
      while IFS= read -r path; do
        if [[ "$path" == ca/weblite/java-objc-bridge/* ||
              "$path" == net/java/dev/jna/jna/* ||
              "$path" == net/java/dev/jna/jna-platform/* ]]; then
          continue
        fi
        patched="$root/port/work/overlays/libraries/$path"
        if [[ -f "$patched" ]]; then
          printf '%s\n' "$patched"
        else
          printf '%s\n' "$work/libraries/$path"
        fi
      done
  )

  cat <<EOF
  </dependencies>

  <build>
    <sourceDirectory>$root/port/src/main/java</sourceDirectory>
    <resources>
      <resource>
        <directory>$root/port/src/main/resources</directory>
      </resource>
      <resource>
        <directory>$root/port/target/generated-resources</directory>
      </resource>
    </resources>
    <directory>$root/port/target/maven</directory>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.14.1</version>
        <configuration>
          <release>21</release>
          <proc>none</proc>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.teavm</groupId>
        <artifactId>teavm-maven-plugin</artifactId>
        <version>\${teavm.version}</version>
        <dependencies>
          <dependency>
            <groupId>dev.gaius.teavm</groupId>
            <artifactId>teavm-core-browser-patch</artifactId>
            <version>0.1.0</version>
            <scope>system</scope>
            <systemPath>$root/port/work/overlays/teavm-core-$teavm_version-gaius.jar</systemPath>
          </dependency>
        </dependencies>
        <executions>
          <execution>
            <id>compile-minecraft-client</id>
            <phase>package</phase>
            <goals>
              <goal>compile</goal>
            </goals>
            <configuration>
              <mainClass>$main_class</mainClass>
              <targetDirectory>$target_directory</targetDirectory>
              <targetFileName>$target_file</targetFileName>
              <optimizationLevel>$optimization_level</optimizationLevel>
              <sourceMapsGenerated>$source_maps_generated</sourceMapsGenerated>
              <debugInformationGenerated>$debug_information_generated</debugInformationGenerated>
              <minifying>$minifying</minifying>
              <shortFileNames>$short_file_names</shortFileNames>
              <assertionsRemoved>$assertions_removed</assertionsRemoved>
              <stopOnErrors>true</stopOnErrors>
              <maxTopLevelNames>10000</maxTopLevelNames>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
EOF
} >"$output"

echo "$output"
