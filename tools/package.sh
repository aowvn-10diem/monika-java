#!/usr/bin/env bash
# Dựng bản phát hành và nén thành zip chỉ chứa Monika.jar (không có JAD).
# URL gốc API nằm trong MANIFEST của JAR nên cài một file .jar là chạy được.
#   MONIKA_BASE_URL  gốc API (mặc định http://api.justmonika.top)
#   OUT              thư mục xuất (mặc định dist/)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export OUT="${OUT:-$ROOT/dist}"
export MONIKA_BASE_URL="${MONIKA_BASE_URL:-http://api.justmonika.top}"
"$ROOT/tools/build.sh"
VERSION="$(grep -o 'VERSION = "[^"]*"' "$ROOT/client/src/vn/aowvn/monika/MonikaMidlet.java" | cut -d'"' -f2)"
ZIP="$OUT/Monika-$VERSION.zip"
rm -f "$ZIP"
(cd "$OUT" && zip -q -j "$ZIP" Monika.jar)
echo "Zip: $ZIP"
unzip -l "$ZIP"
