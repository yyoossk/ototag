#!/bin/bash
# Builds OtoTag-Android.apk: Kotlin core (shared with the Windows build)
# + Kotlin Android UI, compiled with the standalone Kotlin compiler,
# dexed with d8, packaged with aapt2, signed with the local sideload key.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(dirname "$ROOT")"
TC="$HOME/workspace/toolchain"
export JAVA_HOME="$TC/android/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
export LC_ALL=C.UTF-8 LANG=C.UTF-8
BT="$TC/android/sdk/build-tools/34.0.0"
ANDROID_JAR="$TC/android/sdk/platforms/android-34/android.jar"
KOTLINC="$TC/kotlinc24/kotlinc/bin/kotlinc"
BUILD="$ROOT/build"
OUT="$ROOT/OtoTag-Android.apk"

STAMP="${OTOTAG_STAMP:-$(TZ=Asia/Tokyo date +%Y%m%d-%H%M)}"
VERSION_CODE=$(( ( $(date +%s) - 1735689600 ) / 60 ))
echo "== build $STAMP (versionCode $VERSION_CODE)"

LIBS="$PROJ/libs-android/aar/classes.jar:$PROJ/spike/libs/kotlinx-io-core-jvm-0.9.1.jar:$PROJ/spike/libs/kotlinx-io-bytestring-jvm-0.9.1.jar:$PROJ/spike/libs/kotlin-stdlib-2.4.20.jar"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex"

echo "-- Kotlin (core + android UI)"
"$KOTLINC" -cp "$ANDROID_JAR:$LIBS" -d "$BUILD/classes" \
    $(find "$PROJ/core/src" -name '*.kt') \
    $(find "$ROOT/app/src" -name '*.kt')

echo "-- dex"
"$BT/d8" --lib "$ANDROID_JAR" --output "$BUILD/dex" \
    $(find "$BUILD/classes" -name '*.class') \
    "$PROJ/libs-android/aar/classes.jar" \
    "$PROJ/spike/libs/kotlinx-io-core-jvm-0.9.1.jar" \
    "$PROJ/spike/libs/kotlinx-io-bytestring-jvm-0.9.1.jar" \
    "$PROJ/spike/libs/kotlin-stdlib-2.4.20.jar"

echo "-- resources + package"
"$BT/aapt2" compile --dir "$ROOT/app/res" -o "$BUILD/res.zip"
"$BT/aapt2" link -o "$BUILD/app-raw.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/app/AndroidManifest.xml" \
    --version-code "$VERSION_CODE" \
    --version-name "$STAMP" \
    "$BUILD/res.zip"
(cd "$BUILD/dex" && zip -q ../app-raw.apk classes.dex)
"$BT/zipalign" -p -f 4 "$BUILD/app-raw.apk" "$BUILD/app-aligned.apk"

echo "-- sign"
KS="$ROOT/keystore.jks"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias ototag -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass ototag-android -keypass ototag-android \
        -dname "CN=OtoTag, O=yyoossk" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:ototag-android \
    --key-pass pass:ototag-android --out "$OUT" "$BUILD/app-aligned.apk"
"$BT/apksigner" verify "$OUT"
# Guard: a missing <uses-sdk> makes targetSdk default to <4, which modern
# Android refuses to install. Fail the build instead of shipping that again.
"$BT/aapt2" dump badging "$OUT" | grep -q "targetSdkVersion:'34'" || {
    echo "ERROR: targetSdkVersion is not 34 in the built APK"; exit 1; }
"$BT/aapt2" dump badging "$OUT" | grep -q "sdkVersion:'26'" || {
    echo "ERROR: minSdkVersion is not 26 in the built APK"; exit 1; }
ls -la "$OUT"
sha256sum "$OUT"
