#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="${BUILD_TOOLS:-$SDK/build-tools/37.0.0}"
JAR="$SDK/platforms/android-37.0/android.jar"
JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk}"
JDK="$JAVA_HOME/bin"
XP="$ROOT/libs/api-82.jar"
OUT="$ROOT/build"
DIST="$ROOT/dist"

if [ ! -x "$BT/aapt2" ]; then
  echo "找不到 $BT/aapt2；请设置 ANDROID_HOME 或 BUILD_TOOLS" >&2
  exit 1
fi
if [ ! -f "$JAR" ]; then
  echo "找不到 $JAR；请安装 platforms;android-37.0" >&2
  exit 1
fi

rm -rf "$OUT" "$DIST"
mkdir -p "$OUT/classes" "$OUT/dex" "$DIST"

"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/resources.zip"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$JAR" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  -R "$OUT/resources.zip" \
  --auto-add-overlay \
  -A "$ROOT/app/src/main/assets" \
  --min-sdk-version 34 \
  --target-sdk-version 37

"$JDK/javac" -source 17 -target 17 -encoding UTF-8 \
  -cp "$JAR:$XP" \
  -d "$OUT/classes" \
  $(find "$ROOT/app/src/main/java" -name '*.java')

"$BT/d8" --lib "$JAR" --min-api 34 --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/withdex.apk"
(cd "$OUT" && zip -q -j withdex.apk dex/classes.dex)
"$BT/zipalign" -f 4 "$OUT/withdex.apk" "$OUT/aligned.apk"

KS="$OUT/debug.keystore"
if [ ! -f "$KS" ]; then
  "$JDK/keytool" -genkeypair -keystore "$KS" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$DIST/native-window-0.4.1-native-caption-alpha.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify "$DIST/native-window-0.4.1-native-caption-alpha.apk"
echo "OK: $DIST/native-window-0.4.1-native-caption-alpha.apk"
