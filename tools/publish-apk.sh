#!/usr/bin/env bash
# 把编译好的 APK 发布到 NAS，让电视/手机在应用内直接升级。
#
#   ./tools/publish-apk.sh [apk路径]
#
# 默认取 release 产物；写完 data/apps/tv.apk 与 tv.json，
# App 下次启动会读到新的 versionCode 并提示更新。
set -euo pipefail

cd "$(dirname "$0")/.."

KIND="${KIND:-${1:-tv}}"
case "$KIND" in
  tv|phone) ;;
  *) echo "用法: ./tools/publish-apk.sh [tv|phone]  （默认 tv）"; exit 1 ;;
esac
case "$KIND" in
  tv) MODULE="app-tv" ;;
  phone) MODULE="app-phone" ;;
esac
APK="native/$MODULE/build/outputs/apk/release/$MODULE-release.apk"
META="native/$MODULE/build/outputs/apk/release/output-metadata.json"
OUT="data/apps"

if [ ! -f "$APK" ]; then
  echo "找不到 APK：$APK"
  echo "先构建：cd native && ./gradlew :$MODULE:assembleRelease"
  exit 1
fi

mkdir -p "$OUT"
cp "$APK" "$OUT/$KIND.apk"

python3 - "$META" "$OUT/$KIND.apk" "$OUT/$KIND.json" "${NOTES:-}" <<'PY'
import hashlib, json, os, sys, time

meta_path, apk_path, out_path, notes = sys.argv[1:5]
code, name = 0, "0.0.0"
if os.path.exists(meta_path):
    m = json.load(open(meta_path))
    el = (m.get("elements") or [{}])[0]
    code = el.get("versionCode", 0)
    name = el.get("versionName", name)

data = open(apk_path, "rb").read()
info = {
    "versionCode": int(code),
    "versionName": name,
    "size": len(data),
    "sha256": hashlib.sha256(data).hexdigest(),
    "notes": notes or ("%s 版本更新" % name),
    "at": int(time.time()),
}
json.dump(info, open(out_path, "w"), ensure_ascii=False, indent=2)
print("已发布 %s → %s" % (name, apk_path))
print("  大小 %.1f MB  sha256 %s…" % (len(data) / 1048576, info["sha256"][:12]))
PY
