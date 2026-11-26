#!/usr/bin/env bash
# Host-side smoke checks for the built APK.
# This does NOT replace an on-device functional smoke test: it verifies packaging,
# manifest, dex class presence and the log provider contract.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="${BUILD_TOOLS:-$SDK/build-tools/37.0.0}"
APK="${1:-$ROOT/dist/native-window-0.6.0-selftest.apk}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0
ok() { printf 'PASS  %s\n' "$1"; pass=$((pass+1)); }
ng() { printf 'FAIL  %s\n' "$1"; fail=$((fail+1)); }

[ -f "$APK" ] && ok "apk exists: $(basename "$APK")" || { ng "apk missing"; exit 1; }

"$BT/apksigner" verify "$APK" >/dev/null 2>&1 && ok "apk signature" || ng "apk signature"

BADGE="$("$BT/aapt2" dump badging "$APK")"
echo "$BADGE" | grep -q "package: name='io.github.wumisc.nativewindow'" \
  && ok "package id" || ng "package id"
echo "$BADGE" | grep -q "versionName='0.6.0-selftest'" \
  && ok "versionName 0.6.0-selftest" || ng "versionName"
echo "$BADGE" | grep -q "targetSdkVersion:'37'" \
  && ok "targetSdk 37" || ng "targetSdk"

unzip -p "$APK" assets/xposed_init > "$TMP/xposed_init"
grep -q "io.github.wumisc.nativewindow.ModuleEntry" "$TMP/xposed_init" \
  && ok "xposed_init -> ModuleEntry" || ng "xposed_init"

MANIFEST="$("$BT/aapt2" dump xmltree "$APK" --file AndroidManifest.xml)"
echo "$MANIFEST" | grep -q '\.log\.LogProvider' \
  && ok "LogProvider declared" || ng "LogProvider missing"
echo "$MANIFEST" | grep -q 'io.github.wumisc.nativewindow.logs' \
  && ok "LogProvider authority" || ng "LogProvider authority"
echo "$MANIFEST" | grep -q '\.MainActivity' \
  && ok "MainActivity declared" || ng "MainActivity missing"
echo "$MANIFEST" | grep -q '\.selftest\.SelfTestActivity' \
  && ok "SelfTestActivity declared" || ng "SelfTestActivity missing"
echo "$MANIFEST" | grep -q '\.OverlayService' \
  && ok "OverlayService declared" || ng "OverlayService missing"
echo "$MANIFEST" | grep -q 'xposedmodule' \
  && ok "xposedmodule meta" || ng "xposedmodule meta"

unzip -p "$APK" classes.dex > "$TMP/classes.dex"
"$BT/dexdump" -d "$TMP/classes.dex" > "$TMP/dexdump.txt"
for cls in \
  io/github/wumisc/nativewindow/ModuleEntry \
  io/github/wumisc/nativewindow/hook/SystemServerHook \
  io/github/wumisc/nativewindow/hook/SystemUiHook \
  io/github/wumisc/nativewindow/hook/LauncherHook \
  io/github/wumisc/nativewindow/hook/NativeCaptionDecorator \
  io/github/wumisc/nativewindow/hook/NativeCapsuleView \
  io/github/wumisc/nativewindow/selftest/SelfTestActivity \
  io/github/wumisc/nativewindow/log/LogCollector \
  io/github/wumisc/nativewindow/log/LogProvider \
  io/github/wumisc/nativewindow/Palette
 do
  grep -q "L$cls;" "$TMP/dexdump.txt" && ok "dex: $cls" || ng "dex missing: $cls"
 done

! grep -q "Lio/github/wumisc/nativewindow/chrome/TaskChromeService;" "$TMP/dexdump.txt" \
  && ok "old overlay chrome removed" || ng "old overlay chrome still in dex"
! grep -q "Lio/github/wumisc/nativewindow/hook/OpenReceiver;" "$TMP/dexdump.txt" \
  && ok "old OpenReceiver removed" || ng "old OpenReceiver still in dex"

[ -f "$ROOT/app/src/main/res/values-night/styles.xml" ] \
  && ok "values-night theme present" || ng "values-night theme missing"
[ -f "$ROOT/app/src/main/res/values/styles.xml" ] \
  && grep -q "Theme.Material.Light" "$ROOT/app/src/main/res/values/styles.xml" \
  && ok "light AppTheme" || ng "light AppTheme"

unzip -l "$APK" | grep -q '\.apk$' \
  && ng "nested APK found" || ok "no nested APK"
unzip -t "$APK" >/dev/null && ok "zip integrity" || ng "zip integrity"

printf '\nSMOKE RESULT: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
