#!/usr/bin/env bash
#
# Runs one leg of the gaming test matrix over adb and writes raw evidence to disk.
#
# What this does automate: installing a package for a given Android user (optionally with the
# ABI pinned), launching it, and sampling real frame timestamps, memory, thermal state and the
# platform's own process-exit history while it runs.
#
# What it cannot automate, and does not pretend to: signing in, matchmaking, and judging whether
# a match felt smooth. Those are the legs a person has to run. The point of this script is that
# the numbers next to those human observations are measured rather than remembered.
#
# Requirements: adb, a connected device, and root on the device (su) for the per-user install
# and for dumpsys. Without root it stops rather than reporting partial results as a pass.
#
# Usage:
#   tools/test-matrix.sh --package com.example.game [options]
#
# Options:
#   --package PKG       package to test (required)
#   --user ID           Android user to run in (default 0, the original install)
#   --abi ABI           pin the ABI for a fresh install into --user (e.g. armeabi-v7a)
#   --duration SEC      sampling duration in seconds (default 120)
#   --interval SEC      seconds between samples (default 5)
#   --out DIR           output directory (default results/<package>-<user>-<timestamp>)
#   --serial SERIAL     adb device serial, when more than one is attached
#   --no-launch         assume the game is already running
#
set -euo pipefail

PACKAGE=""
USER_ID=0
ABI=""
DURATION=120
INTERVAL=5
OUT=""
SERIAL=""
LAUNCH=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --package)  PACKAGE="$2"; shift 2 ;;
    --user)     USER_ID="$2"; shift 2 ;;
    --abi)      ABI="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    --out)      OUT="$2"; shift 2 ;;
    --serial)   SERIAL="$2"; shift 2 ;;
    --no-launch) LAUNCH=0; shift ;;
    -h|--help)  sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

[[ -n "$PACKAGE" ]] || { echo "--package is required" >&2; exit 2; }

ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

sh_cmd() { "${ADB[@]}" shell "$@"; }
su_cmd() { "${ADB[@]}" shell su -c "$1"; }

# --- preconditions, checked rather than assumed ------------------------------------------------
command -v adb >/dev/null || { echo "adb not on PATH" >&2; exit 1; }
"${ADB[@]}" wait-for-device

if [[ "$(su_cmd 'id -u' | tr -d '\r')" != "0" ]]; then
  echo "root is required: per-user install and dumpsys are privileged." >&2
  echo "Grant this shell root in your root manager, then retry." >&2
  exit 1
fi

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
[[ -n "$OUT" ]] || OUT="results/${PACKAGE}-u${USER_ID}-${TIMESTAMP}"
mkdir -p "$OUT/raw"

SDK="$(sh_cmd getprop ro.build.version.sdk | tr -d '\r')"
RELEASE="$(sh_cmd getprop ro.build.version.release | tr -d '\r')"
MODEL="$(sh_cmd getprop ro.product.model | tr -d '\r')"
ABILIST="$(sh_cmd getprop ro.product.cpu.abilist | tr -d '\r')"

{
  echo "package=$PACKAGE"
  echo "user=$USER_ID"
  echo "pinned_abi=${ABI:-none}"
  echo "device_model=$MODEL"
  echo "android_release=$RELEASE"
  echo "android_sdk=$SDK"
  echo "device_abilist=$ABILIST"
  echo "duration_seconds=$DURATION"
  echo "sample_interval_seconds=$INTERVAL"
  echo "started_at=$(date -Iseconds)"
} > "$OUT/leg.properties"

echo "== leg: $PACKAGE, user $USER_ID, abi ${ABI:-platform default} =="

# --- install into the target user --------------------------------------------------------------
if [[ "$USER_ID" != "0" ]]; then
  if ! su_cmd "pm list users" | grep -q "UserInfo{$USER_ID:"; then
    echo "user $USER_ID does not exist. Create it first, or run the app's clone flow." >&2
    exit 1
  fi
  if su_cmd "pm list packages --user $USER_ID" | grep -q "package:$PACKAGE\b"; then
    echo "-- already installed for user $USER_ID"
  elif [[ -n "$ABI" ]]; then
    echo "-- staged install with --abi $ABI into user $USER_ID"
    SESSION="$(su_cmd "pm install-create --user $USER_ID --abi $ABI -r -i $PACKAGE" \
      | tr -d '\r' | sed -n 's/.*\[\([0-9]\+\)\].*/\1/p')"
    [[ -n "$SESSION" ]] || { echo "install-create gave no session id" >&2; exit 1; }
    while read -r apk; do
      apk="${apk#package:}"; apk="$(echo "$apk" | tr -d '\r')"
      [[ -n "$apk" ]] || continue
      SIZE="$(su_cmd "stat -c %s '$apk'" | tr -d '\r')"
      su_cmd "pm install-write -S $SIZE $SESSION $(basename "$apk") '$apk'" \
        | tee -a "$OUT/raw/install.log"
    done < <(su_cmd "pm path --user 0 $PACKAGE")
    su_cmd "pm install-commit $SESSION" | tee -a "$OUT/raw/install.log"
  else
    echo "-- install-existing into user $USER_ID"
    su_cmd "pm install-existing --user $USER_ID $PACKAGE" | tee -a "$OUT/raw/install.log"
  fi
fi

# Record the ABI the install actually ended up with, rather than the one that was asked for.
su_cmd "pm dump $PACKAGE" > "$OUT/raw/pm-dump.txt" 2>/dev/null || true
grep -E "primaryCpuAbi|secondaryCpuAbi|legacyNativeLibraryDir|versionCode" \
  "$OUT/raw/pm-dump.txt" | head -20 > "$OUT/resolved-abi.txt" || true

# --- launch ------------------------------------------------------------------------------------
if [[ "$LAUNCH" == "1" ]]; then
  if [[ "$USER_ID" != "0" ]]; then
    su_cmd "am start-user $USER_ID" >/dev/null || true
    echo "-- switching to user $USER_ID (the original instance stops being interactive)"
    su_cmd "am switch-user $USER_ID" >/dev/null || true
    sleep 5
  fi
  su_cmd "monkey --pct-syskeys 0 -p $PACKAGE --user $USER_ID 1" >/dev/null 2>&1 || true
fi

echo "-- waiting for the process (up to 90s)"
PID=""
for _ in $(seq 1 45); do
  PID="$(su_cmd "pidof $PACKAGE" | tr -d '\r' | awk '{print $1}')"
  [[ -n "$PID" ]] && break
  sleep 2
done
if [[ -z "$PID" ]]; then
  echo "process never appeared. Recording exit history and stopping." >&2
  su_cmd "dumpsys activity exit-info $PACKAGE" > "$OUT/raw/exit-info.txt" 2>/dev/null || true
  exit 1
fi
echo "-- pid $PID"

LAYER="$(su_cmd "dumpsys SurfaceFlinger --list" | tr -d '\r' | grep -F "$PACKAGE" \
  | grep -i SurfaceView | head -1 || true)"
[[ -n "$LAYER" ]] || LAYER="$(su_cmd "dumpsys SurfaceFlinger --list" | tr -d '\r' \
  | grep -F "$PACKAGE" | head -1 || true)"
if [[ -z "$LAYER" ]]; then
  echo "-- no SurfaceFlinger layer matched; frame data will be absent for this leg"
else
  echo "-- layer: $LAYER"
  echo "$LAYER" > "$OUT/layer.txt"
fi

# --- sample ------------------------------------------------------------------------------------
CSV="$OUT/samples.csv"
echo "elapsed_s,mem_available_kb,thermal_status,proc_rss_kb,proc_utime,proc_stime,threads,frames_sampled" > "$CSV"

ELAPSED=0
SAMPLE=0
while [[ "$ELAPSED" -lt "$DURATION" ]]; do
  SAMPLE=$((SAMPLE + 1))
  MEM_AVAIL="$(sh_cmd "grep MemAvailable /proc/meminfo" | tr -d '\r' | awk '{print $2}')"
  THERMAL="$(su_cmd "dumpsys thermalservice" 2>/dev/null | tr -d '\r' \
    | grep -m1 -i 'Thermal Status' | awk -F: '{gsub(/ /,"",$2); print $2}')"
  PROC_STAT="$(su_cmd "cat /proc/$PID/stat" 2>/dev/null | tr -d '\r' || true)"
  if [[ -n "$PROC_STAT" ]]; then
    # Longest match, mirroring the parser's lastIndexOf(')'): only comm can hold ')'.
    REST="${PROC_STAT##*) }"
    UTIME="$(echo "$REST" | awk '{print $12}')"
    STIME="$(echo "$REST" | awk '{print $13}')"
    THREADS="$(echo "$REST" | awk '{print $18}')"
  else
    UTIME=""; STIME=""; THREADS=""
    echo "-- process gone at ${ELAPSED}s" | tee -a "$OUT/raw/notes.txt"
  fi
  RSS="$(su_cmd "grep VmRSS /proc/$PID/status" 2>/dev/null | tr -d '\r' | awk '{print $2}' || true)"

  FRAMES=0
  if [[ -n "$LAYER" ]]; then
    LATENCY_FILE="$OUT/raw/latency-$(printf '%03d' "$SAMPLE").txt"
    su_cmd "dumpsys SurfaceFlinger --latency '$LAYER'" | tr -d '\r' > "$LATENCY_FILE" || true
    FRAMES="$(awk 'NR>1 && $2 > 0 && $2 != 9223372036854775807' "$LATENCY_FILE" | wc -l | tr -d ' ')"
  fi

  echo "$ELAPSED,${MEM_AVAIL:-},${THERMAL:-},${RSS:-},${UTIME:-},${STIME:-},${THREADS:-},$FRAMES" >> "$CSV"
  [[ -z "$PROC_STAT" ]] && break
  sleep "$INTERVAL"
  ELAPSED=$((ELAPSED + INTERVAL))
done

# --- after ------------------------------------------------------------------------------------
su_cmd "dumpsys activity exit-info $PACKAGE" > "$OUT/raw/exit-info.txt" 2>/dev/null || true
su_cmd "logcat -d -t 2000 --pid=$PID" > "$OUT/raw/logcat.txt" 2>/dev/null || true
su_cmd "ls -t /data/tombstones" > "$OUT/raw/tombstones.txt" 2>/dev/null || true
echo "finished_at=$(date -Iseconds)" >> "$OUT/leg.properties"

echo
echo "== leg written to $OUT =="
echo "   samples.csv       measured series"
echo "   raw/latency-*.txt frame timestamps, for tools/frame-report.py"
echo "   raw/exit-info.txt why the process died, if it did"
echo
echo "Still to record by hand for this leg: did it sign in, did assets finish downloading,"
echo "did a full match complete, and how it felt. The numbers above do not answer those."
