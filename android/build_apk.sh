#!/usr/bin/env bash
#
#  build_apk.sh — builds, aligns and signs the M-TRACKER Android app
#  WITHOUT Gradle/Android Studio (works offline, only needs the Android
#  build-tools + a JDK).
#
#  Usage:
#     ANDROID_SDK=/path/to/sdk JAVA_HOME=/path/to/jdk ./build_apk.sh
#
#  Defaults look for the toolchain in /tmp/sdk (this workspace) and fall
#  back to the usual $HOME/Android/Sdk + JAVA_HOME locations.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$ROOT/app"
BUILD="$ROOT/build"
OUT="$ROOT/out"
NAME="m-tracker"
VERSION_CODE=1
VERSION_NAME="1.0.0"
PKG="org.acf.mtracker"

# ── locate toolchain ──────────────────────────────────────────────────
guess_sdk() {
  for d in "${ANDROID_SDK:-}" "${ANDROID_HOME:-}" /tmp/sdk/android-sdk "$HOME/Android/Sdk" /usr/lib/android-sdk; do
    [ -n "$d" ] && [ -d "$d/build-tools" ] && { echo "$d"; return; }
  done
}
SDK="$(guess_sdk || true)"
[ -z "$SDK" ] && { echo "!! Android SDK not found (set ANDROID_SDK)"; exit 1; }

guess_jdk() {
  for d in "${JAVA_HOME:-}" /tmp/sdk/jdk /usr/lib/jvm/*; do
    [ -n "$d" ] && [ -x "$d/bin/javac" ] && { echo "$d"; return; }
  done
}
JDK="$(guess_jdk || true)"
[ -z "$JDK" ] && { echo "!! JDK not found (set JAVA_HOME)"; exit 1; }

BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/* 2>/dev/null | sort -V | tail -1)"
[ -z "$BT" ] && { echo "!! no build-tools in $SDK"; exit 1; }
[ -z "$PLATFORM" ] && { echo "!! no platform (android.jar) in $SDK"; exit 1; }
ANDROID_JAR="$PLATFORM/android.jar"

echo "── toolchain ─────────────────────────────────────────────"
echo "  SDK      : $SDK"
echo "  build-tools: $(basename "$BT")"
echo "  platform : $(basename "$PLATFORM")"
echo "  JDK      : $($JDK/bin/javac -version 2>&1)"

export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$BT:$PATH"
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"

# ── clean ─────────────────────────────────────────────────────────────
rm -rf "$BUILD" "$OUT"
mkdir -p "$BUILD/res" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$OUT"

# ── manifest (offline aapt2 needs the package inside the manifest) ────
python3 - "$APP/src/main/AndroidManifest.xml" "$BUILD/AndroidManifest.xml" "$PKG" "$VERSION_CODE" "$VERSION_NAME" << 'PY'
import sys, re
src, dst, pkg, vcode, vname = sys.argv[1:6]
s = open(src, encoding='utf-8').read()
s = s.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android"',
              '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="%s"' % pkg)
s = re.sub(r'android:versionCode="[^"]*"', 'android:versionCode="%s"' % vcode, s)
s = re.sub(r'android:versionName="[^"]*"', 'android:versionName="%s"' % vname, s)
open(dst, 'w', encoding='utf-8').write(s)
PY

# ── resources ─────────────────────────────────────────────────────────
echo "── aapt2 compile ─────────────────────────────────────────"
"$BT/aapt2" compile --dir "$APP/src/main/res" -o "$BUILD/res.zip"

echo "── aapt2 link ────────────────────────────────────────────"
"$BT/aapt2" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$BUILD/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --no-version-vectors \
  --auto-add-overlay \
  "$BUILD/res.zip"

# ── java ──────────────────────────────────────────────────────────────
echo "── javac ─────────────────────────────────────────────────"
find "$APP/src/main/java" -name '*.java' > "$BUILD/sources.txt"
find "$BUILD/gen" -name '*.java' >> "$BUILD/sources.txt"
if ! "$JDK/bin/javac" -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 \
      -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
      -d "$BUILD/classes" @"$BUILD/sources.txt" > "$BUILD/javac.log" 2>&1; then
  echo "!! compilation failed:"; cat "$BUILD/javac.log"; exit 1
fi
grep -v '^Note:\|bootstrap class path\|source value 8\|target value 8' "$BUILD/javac.log" || true

# ── dex ───────────────────────────────────────────────────────────────
echo "── d8 ────────────────────────────────────────────────────"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$BT/d8" --release --min-api 26 --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" @"$BUILD/classes.txt"

# ── package ───────────────────────────────────────────────────────────
echo "── package ───────────────────────────────────────────────"
python3 - "$BUILD/base.apk" "$BUILD/unsigned.apk" "$BUILD/dex/classes.dex" << 'PY'
import sys, zipfile, shutil
base, out, dex = sys.argv[1:4]
shutil.copyfile(base, out)
with zipfile.ZipFile(out, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PY

"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

# ── signing key ───────────────────────────────────────────────────────
KS_DIR="$ROOT/keystore"
KS="$KS_DIR/$NAME-release.jks"
PROPS="$KS_DIR/keystore.properties"
mkdir -p "$KS_DIR"
if [ ! -f "$KS" ]; then
  echo "── creating signing key (first build) ────────────────────"
  STOREPASS="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 18)"
  KEYPASS="$STOREPASS"
  "$JDK/bin/keytool" -genkeypair -v -keystore "$KS" -alias "$NAME" \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$STOREPASS" -keypass "$KEYPASS" \
    -dname "CN=M-TRACKER, OU=ACF Operations, O=ACF, L=Gaza, C=PS" > /dev/null 2>&1
  cat > "$PROPS" << EOF
# Signing key for the M-TRACKER Android app.
# KEEP THIS FILE AND THE .jks SAFE: without them you cannot ship an update
# that installs on top of an already installed version.
storeFile=$KS
storePassword=$STOREPASS
keyAlias=$NAME
keyPassword=$KEYPASS
EOF
  chmod 600 "$PROPS" "$KS"
  echo "  created $KS (password stored in keystore/keystore.properties)"
fi

# shellcheck disable=SC1090
. "$PROPS"

echo "── apksigner ─────────────────────────────────────────────"
APK="$OUT/$NAME-$VERSION_NAME.apk"
"$BT/apksigner" sign \
  --ks "$storeFile" --ks-key-alias "$keyAlias" \
  --ks-pass "pass:$storePassword" --key-pass "pass:$keyPassword" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$APK" "$BUILD/aligned.apk"

"$BT/apksigner" verify --print-certs "$APK" | head -5

echo
echo "✅ APK: $APK  ($(du -h "$APK" | cut -f1))"
