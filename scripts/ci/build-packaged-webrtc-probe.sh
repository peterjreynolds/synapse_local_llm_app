#!/usr/bin/env bash
set -euo pipefail

# Builds an independent probe without changing the installed distribution APK.
: "${ANDROID_HOME:?ANDROID_HOME is required}"
: "${JAVA_HOME:?JAVA_HOME is required}"
export PATH="$JAVA_HOME/bin:$PATH"
repository_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
probe_directory="$(mktemp -d -t synapse-packaged-webrtc-probe.XXXXXX)"
build_tools_directory="$ANDROID_HOME/build-tools/36.0.0"
android_platform_jar="$ANDROID_HOME/platforms/android-36/android.jar"
mkdir -p "$probe_directory/classes" "$probe_directory/dex"
"$JAVA_HOME/bin/javac" --release 8 -Xlint:-options -classpath "$android_platform_jar" \
  -d "$probe_directory/classes" \
  "$repository_directory/privatechat/src/androidTest/java/app/synapse/privatechat/data/chat/PackagedWebRtcProbe.java"
"$JAVA_HOME/bin/jar" cf "$probe_directory/probe.jar" -C "$probe_directory/classes" .
"$build_tools_directory/d8" --min-api 25 --lib "$android_platform_jar" \
  --output "$probe_directory/dex" "$probe_directory/probe.jar"
"$build_tools_directory/aapt" package -f \
  -M "$repository_directory/scripts/ci/packaged-webrtc-probe/AndroidManifest.xml" \
  -I "$android_platform_jar" -F "$probe_directory/probe-unsigned.apk"
(cd "$probe_directory/dex" && zip -q "$probe_directory/probe-unsigned.apk" classes.dex)
"$build_tools_directory/zipalign" -f 4 "$probe_directory/probe-unsigned.apk" "$probe_directory/probe-aligned.apk"
"$build_tools_directory/apksigner" sign --ks "$HOME/.android/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
  --out "$probe_directory/probe.apk" "$probe_directory/probe-aligned.apk"
printf '%s\n' "$probe_directory/probe.apk"
