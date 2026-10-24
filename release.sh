#!/usr/bin/env bash
# Build the signed Stupify APK and publish it to the hub (in-app updates + download link).
# Usage: ./release.sh "changelog text"   (bump versionCode/versionName in app/build.gradle first)
set -euo pipefail
cd "$(dirname "$0")"
OUT=${STUPIFY_RELEASES:-$HOME/appdata/stupify-hub/app}
[ -f "$HOME/.stupify_app_keystore.properties" ] || { echo "release keystore missing" >&2; exit 1; }

block=$(sed -n '/^        stupify {/,/^        }/p' app/build.gradle)
code=$(grep -oP 'versionCode \K\d+' <<<"$block")
name=$(grep -oP 'versionName "\K[^"]+' <<<"$block")
if [ -f "$OUT/latest.json" ] && [ "$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["versionCode"])' "$OUT/latest.json")" -ge "$code" ]; then
    echo "versionCode $code is not newer than the published release — bump it in app/build.gradle" >&2; exit 1
fi

ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk} ./gradlew --no-daemon -q assembleStupifyRelease
apk=app/build/outputs/apk/stupify/release/app-stupify-release.apk
"${ANDROID_HOME:-/opt/android-sdk}"/build-tools/36.1.0/apksigner verify "$apk"

mkdir -p "$OUT"
cp "$apk" "$OUT/stupify-$name.apk"
python3 - "$OUT" "$code" "$name" "${1:-}" <<'PY'
import json, os, sys
out, code, name, log = sys.argv[1:]
tmp = os.path.join(out, "latest.json.tmp")
json.dump({"versionCode": int(code), "versionName": name, "changelog": log,
           "file": f"stupify-{name}.apk", "minSupported": 1}, open(tmp, "w"), indent=1)
os.replace(tmp, os.path.join(out, "latest.json"))
PY
ls -t "$OUT"/stupify-*.apk | tail -n +4 | xargs -r rm --   # keep the last 3
echo "published Stupify $name ($code)"
