#!/usr/bin/env bash
# Dựng Monika.jar + Monika.jad (MIDP 2.0 / CLDC 1.1) từ client/src.
#
# Cần: JDK 8 trở lên (đã thử với 21), curl, unzip. Thư viện build tải từ Maven Central
# về .build/lib và kiểm SHA-256 theo tools/libs.sha256.
#
# Biến môi trường tùy chọn:
#   OUT                thư mục xuất (mặc định dist/)
#   MIDLET_JAR_URL     giá trị MIDlet-Jar-URL; để cài qua mạng phải là URL tuyệt đối
#   MONIKA_BASE_URL    ghi vào JAD thành Monika-Base-URL (gốc máy chủ catalog)
#   MONIKA_NET_SUFFIX  ghi vào JAD thành Monika-Net-Suffix (ví dụ ;deviceside=true)
#   MONIKA_FULLSCREEN  1 = ghi Monika-Fullscreen: 1 vào JAD
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/.build"
LIB="$BUILD/lib"
OUT="${OUT:-$ROOT/dist}"
CENTRAL="https://repo1.maven.org/maven2"

VERSION="$(grep -o 'VERSION = "[^"]*"' "$ROOT/client/src/vn/aowvn/monika/MonikaMidlet.java" | cut -d'"' -f2)"
[ -n "$VERSION" ] || { echo "Không đọc được VERSION" >&2; exit 1; }

# nhóm:artifact:phiên bản
LIBS="
org.microemu:cldcapi11:2.0.4
org.microemu:midpapi20:2.0.4
com.guardsquare:proguard-base:7.6.1
com.guardsquare:proguard-core:9.1.7
org.jetbrains.kotlin:kotlin-stdlib:2.1.0
com.google.code.gson:gson:2.11.0
org.apache.logging.log4j:log4j-api:2.24.2
org.apache.logging.log4j:log4j-core:2.24.2
org.json:json:20231013
org.eclipse.jdt.core.compiler:ecj:4.6.1
"

fetch_libs() {
  mkdir -p "$LIB"
  for coord in $LIBS; do
    IFS=: read -r g a v <<<"$coord"
    f="$LIB/$a-$v.jar"
    if [ ! -f "$f" ]; then
      url="$CENTRAL/$(echo "$g" | tr . /)/$a/$v/$a-$v.jar"
      echo "Tải $a-$v.jar"
      curl -fsSL --retry 6 --retry-delay 3 --retry-all-errors -o "$f" "$url"
    fi
    want="$(grep " $a-$v.jar\$" "$ROOT/tools/libs.sha256" | cut -d' ' -f1)"
    got="$(sha256sum "$f" | cut -d' ' -f1)"
    if [ -z "$want" ] || [ "$want" != "$got" ]; then
      rm -f "$f"
      echo "SHA-256 không khớp cho $a-$v.jar (tools/libs.sha256)" >&2
      exit 1
    fi
  done
}

fetch_libs
CLDC="$LIB/cldcapi11-2.0.4.jar"
MIDP="$LIB/midpapi20-2.0.4.jar"
PG_CP="$LIB/proguard-base-7.6.1.jar:$LIB/proguard-core-9.1.7.jar:$LIB/kotlin-stdlib-2.1.0.jar:$LIB/gson-2.11.0.jar:$LIB/log4j-api-2.24.2.jar:$LIB/log4j-core-2.24.2.jar:$LIB/json-20231013.jar"

rm -rf "$BUILD/classes" "$BUILD/res" "$BUILD/stage.jar" "$BUILD/proguard.jar"
mkdir -p "$BUILD/classes" "$BUILD/res" "$OUT"

echo "== Sinh tài nguyên (icon, catalog mẫu)"
java "$ROOT/tools/GenAssets.java" "$BUILD/res" "$ROOT/catalog/sample.tsv"
[ -d "$ROOT/client/res" ] && cp -R "$ROOT/client/res/." "$BUILD/res/" || true

echo "== Biên dịch (ECJ, nguồn/đích Java 1.3, API chỉ lấy từ CLDC 1.1 + MIDP 2.0)"
# Dùng ECJ thay javac vì javac mới luôn sinh StringBuilder cho phép nối chuỗi (CLDC chỉ có StringBuffer)
# và không còn đích dưới Java 8. ECJ 4.6.1 là bản cuối còn hỗ trợ -source 1.3.
find "$ROOT/client/src" -name '*.java' > "$BUILD/sources.txt"
java -jar "$LIB/ecj-4.6.1.jar" -encoding UTF-8 -source 1.3 -target 1.3 -g:none -nowarn \
  -bootclasspath "$CLDC:$MIDP" -d "$BUILD/classes" @"$BUILD/sources.txt"
[ -f "$BUILD/classes/vn/aowvn/monika/MonikaMidlet.class" ] || { echo "Biên dịch lỗi" >&2; exit 1; }

echo "== Manifest"
cat > "$BUILD/MANIFEST.MF" <<MF
Manifest-Version: 1.0
MIDlet-Name: Monika
MIDlet-Vendor: AowVN
MIDlet-Version: $VERSION
MIDlet-1: Monika, /icon.png, vn.aowvn.monika.MonikaMidlet
MIDlet-Description: Cua hang ung dung Java S60 by AOW.VN
MicroEdition-Profile: MIDP-2.0
MicroEdition-Configuration: CLDC-1.1
MF

jar cfm "$BUILD/stage.jar" "$BUILD/MANIFEST.MF" -C "$BUILD/classes" . -C "$BUILD/res" .

echo "== ProGuard: thu nhỏ + làm rối + preverify"
java -cp "$PG_CP" proguard.ProGuard @"$ROOT/tools/proguard.pro" \
  -injars "$BUILD/stage.jar" -outjars "$BUILD/proguard.jar" \
  -libraryjars "$CLDC" -libraryjars "$MIDP" 

cp "$BUILD/proguard.jar" "$OUT/Monika.jar"
SIZE="$(wc -c < "$OUT/Monika.jar" | tr -d ' ')"

echo "== JAD"
{
  echo "MIDlet-Name: Monika"
  echo "MIDlet-Vendor: AowVN"
  echo "MIDlet-Version: $VERSION"
  echo "MIDlet-1: Monika, /icon.png, vn.aowvn.monika.MonikaMidlet"
  echo "MIDlet-Description: Cua hang ung dung Java S60 by AOW.VN"
  echo "MicroEdition-Profile: MIDP-2.0"
  echo "MicroEdition-Configuration: CLDC-1.1"
  echo "MIDlet-Jar-URL: ${MIDLET_JAR_URL:-Monika.jar}"
  echo "MIDlet-Jar-Size: $SIZE"
  echo "Monika-Base-URL: ${MONIKA_BASE_URL:-}"
  echo "Monika-Net-Suffix: ${MONIKA_NET_SUFFIX:-}"
  echo "Monika-Fullscreen: ${MONIKA_FULLSCREEN:-0}"
} > "$OUT/Monika.jad"

echo "== Kiểm tra"
major="$(cd "$BUILD" && rm -rf chk && mkdir chk && cd chk && unzip -q -o "$OUT/Monika.jar" && f="$(find . -name '*.class' | head -1)" && od -An -tu1 -j7 -N1 "$f" | tr -d ' ')"
echo "Class file major version: $major (45 = Java 1.1, 47 = Java 1.3)"
unzip -p "$OUT/Monika.jar" META-INF/MANIFEST.MF | tr -d '\r' | grep -E '^(MIDlet-(Name|Vendor|Version|1)|MicroEdition)'
unzip -l "$OUT/Monika.jar" | tail -n +4 | head -n -2
echo "Monika.jar: $SIZE byte ($((SIZE / 1024)) KB), JAR tối đa đề xuất 150 KB"
[ "$SIZE" -le 153600 ] || { echo "Vượt ngân sách 150 KB" >&2; exit 1; }
echo "Xong: $OUT/Monika.jar, $OUT/Monika.jad"
