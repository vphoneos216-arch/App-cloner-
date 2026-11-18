#!/usr/bin/env bash
#
# One-shot device snapshot, for attaching to a bug report. Read-only.
#
# Works without root for the device-wide parts and says so where root is needed.
#
# Usage: tools/collect-diagnostics.sh [--package PKG] [--serial SERIAL] [--out DIR]
set -euo pipefail

PACKAGE=""; SERIAL=""; OUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --package) PACKAGE="$2"; shift 2 ;;
    --serial)  SERIAL="$2"; shift 2 ;;
    --out)     OUT="$2"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

ADB=(adb); [[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")
"${ADB[@]}" wait-for-device
[[ -n "$OUT" ]] || OUT="diagnostics-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"

HAS_ROOT=0
if [[ "$("${ADB[@]}" shell su -c 'id -u' 2>/dev/null | tr -d '\r')" == "0" ]]; then
  HAS_ROOT=1
fi
echo "root_available=$HAS_ROOT" > "$OUT/summary.txt"

for prop in ro.product.model ro.product.manufacturer ro.build.version.release \
            ro.build.version.sdk ro.product.cpu.abilist ro.soc.model ro.build.fingerprint; do
  echo "$prop=$("${ADB[@]}" shell getprop "$prop" | tr -d '\r')" >> "$OUT/summary.txt"
done

"${ADB[@]}" shell cat /proc/meminfo > "$OUT/meminfo.txt" 2>/dev/null || true
"${ADB[@]}" shell cat /proc/stat > "$OUT/stat.txt" 2>/dev/null || true
"${ADB[@]}" shell 'for z in /sys/class/thermal/thermal_zone*; do
    printf "%s %s %s\n" "$z" "$(cat $z/type 2>/dev/null)" "$(cat $z/temp 2>/dev/null)"; done' \
  > "$OUT/thermal.txt" 2>/dev/null || true
"${ADB[@]}" shell pm list features > "$OUT/features.txt" 2>/dev/null || true

if [[ "$HAS_ROOT" == "1" ]]; then
  "${ADB[@]}" shell su -c "pm list users" > "$OUT/users.txt" 2>/dev/null || true
  "${ADB[@]}" shell su -c "pm get-max-users" >> "$OUT/users.txt" 2>/dev/null || true
  "${ADB[@]}" shell su -c "su -v" > "$OUT/root-version.txt" 2>/dev/null || true
else
  echo "root not available: per-user state, dumpsys and tombstones were not collected" \
    > "$OUT/root-version.txt"
fi

if [[ -n "$PACKAGE" ]]; then
  "${ADB[@]}" shell dumpsys package "$PACKAGE" > "$OUT/package.txt" 2>/dev/null || true
  "${ADB[@]}" shell pm path "$PACKAGE" > "$OUT/package-paths.txt" 2>/dev/null || true
  if [[ "$HAS_ROOT" == "1" ]]; then
    "${ADB[@]}" shell su -c "dumpsys activity exit-info $PACKAGE" \
      > "$OUT/exit-info.txt" 2>/dev/null || true
  fi
fi

echo "written to $OUT"
