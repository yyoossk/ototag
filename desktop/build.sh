#!/bin/bash
# Builds OtoTag-Windows.zip: OtoTag.exe (Go launcher) + OtoTag.jar
# (core + Swing UI + libraries merged) + bundled Temurin JRE 17.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(dirname "$ROOT")"
TC="$HOME/workspace/toolchain"
export JAVA_HOME="$TC/android/jdk"
export PATH="$JAVA_HOME/bin:$TC/go/bin:$PATH"
export LC_ALL=C.UTF-8 LANG=C.UTF-8
KOTLINC="$TC/kotlinc24/kotlinc/bin/kotlinc"
BUILD="$ROOT/build"
STAGE="$ROOT/stage"
OUT="$ROOT/OtoTag-Windows.zip"

STAMP="${OTOTAG_STAMP:-$(TZ=Asia/Tokyo date +%Y%m%d-%H%M)}"
echo "== desktop build $STAMP"

LIBS="$PROJ/spike/libs/jaudiotagger-kt-jvm-1.0.1.jar:$PROJ/spike/libs/kotlinx-io-core-jvm-0.9.1.jar:$PROJ/spike/libs/kotlinx-io-bytestring-jvm-0.9.1.jar:$PROJ/spike/libs/kotlin-stdlib-2.4.20.jar"

rm -rf "$BUILD" "$STAGE"
mkdir -p "$BUILD/classes" "$STAGE"

echo "-- Kotlin (core + desktop UI)"
"$KOTLINC" -cp "$LIBS" -d "$BUILD/classes" \
    $(find "$PROJ/core/src" -name '*.kt') \
    $(find "$ROOT/src" -name '*.kt')

printf '%s' "$STAMP" > "$BUILD/classes/ototag-version.txt"

echo "-- fat jar"
(cd "$BUILD/classes"
 for j in $PROJ/spike/libs/jaudiotagger-kt-jvm-1.0.1.jar \
          $PROJ/spike/libs/kotlinx-io-core-jvm-0.9.1.jar \
          $PROJ/spike/libs/kotlinx-io-bytestring-jvm-0.9.1.jar \
          $PROJ/spike/libs/kotlin-stdlib-2.4.20.jar; do
     "$JAVA_HOME/bin/jar" xf "$j"
 done
 rm -f META-INF/*.SF META-INF/*.RSA META-INF/*.DSA
 printf 'Main-Class: com.yyoo.ototag.desktop.MainKt\n' > "$BUILD/manifest.mf"
 "$JAVA_HOME/bin/jar" cfm "$STAGE/OtoTag.jar" "$BUILD/manifest.mf" .)

echo "-- launcher exe"
(cd "$ROOT" && GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build \
    -ldflags "-H windowsgui" -o "$STAGE/OtoTag.exe" launcher.go)

echo "-- JRE"
if [ ! -d "$ROOT/jre-cache" ]; then
    curl -sL -o "$ROOT/jre.zip" \
        "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse"
    mkdir -p "$ROOT/jre-cache"
    (cd "$ROOT/jre-cache" && unzip -q -o "$ROOT/jre.zip" && mv jdk-17*-jre jre)
fi
cp -r "$ROOT/jre-cache/jre" "$STAGE/jre"

echo "-- fpcalc (AcoustID fingerprint tool for Windows)"
if [ ! -f "$ROOT/fpcalc-cache/fpcalc.exe" ]; then
    mkdir -p "$ROOT/fpcalc-cache"
    FPZIP="$TC/acoustid/fpcalc-win.zip"
    if [ ! -f "$FPZIP" ]; then
        curl -sL -o "$FPZIP" \
            "https://github.com/acoustid/chromaprint/releases/download/v1.6.2/chromaprint-fpcalc-1.6.2-windows-x86_64.zip"
    fi
    (cd "$ROOT/fpcalc-cache" && unzip -q -o "$FPZIP" \
        "chromaprint-fpcalc-1.6.2-windows-x86_64/fpcalc.exe" &&
        mv chromaprint-fpcalc-1.6.2-windows-x86_64/fpcalc.exe . &&
        rmdir chromaprint-fpcalc-1.6.2-windows-x86_64)
fi
cp "$ROOT/fpcalc-cache/fpcalc.exe" "$STAGE/fpcalc.exe"

echo "-- zip"
rm -f "$OUT"
(cd "$STAGE" && zip -qr "$OUT" .)
ls -la "$OUT"
sha256sum "$OUT"
