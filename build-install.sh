#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"

VARIANT="debug"
BUILD_ROOT="${FRIGATE_VIEWER_BUILD_ROOT:-$HOME/frigate-viewer-build}"
declare -a TARGET_SERIALS=()
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  TARGET_SERIALS+=("$ANDROID_SERIAL")
fi

usage() {
  cat <<EOF
Usage: ./build-install.sh [--build-root <path>] [--release] [--serial <serial|all>]...

Options:
  -b, --build-root <path>   Gradle output directory (default: \$HOME/frigate-viewer-build)
  -r, --release             Build the release variant (default: debug)
  -s, --serial <serial>     adb device serial to install on. Repeatable.
                            Pass 'all' to install on every attached device.
                            Default: \$ANDROID_SERIAL, or the single attached device.
  -h, --help                Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -b|--build-root)
      [[ $# -lt 2 ]] && { echo "Missing value for $1"; exit 1; }
      BUILD_ROOT="$2"
      shift 2
      ;;
    --build-root=*)
      BUILD_ROOT="${1#*=}"
      shift
      ;;
    -r|--release)
      VARIANT="release"
      shift
      ;;
    -s|--serial)
      [[ $# -lt 2 ]] && { echo "Missing value for $1"; exit 1; }
      TARGET_SERIALS+=("$2")
      shift 2
      ;;
    --serial=*)
      TARGET_SERIALS+=("${1#*=}")
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1"
      usage
      exit 1
      ;;
  esac
done

VERSION=$(grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
# Default to the `github` flavor for local installs — the fdroid flavor is only
# exercised by F-Droid's build server (no in-app updater, stripped permission).
FLAVOR="github"
# `variant.name` in Gradle concatenates as camelCase, e.g. "githubDebug", "fdroidRelease".
# outputFileName bakes this into the APK name, so we match here.
VARIANT_CAMEL="${FLAVOR}$(tr '[:lower:]' '[:upper:]' <<< "${VARIANT:0:1}")${VARIANT:1}"
APK="$BUILD_ROOT/app/outputs/apk/${FLAVOR}/${VARIANT}/phylax-${VERSION}-${VARIANT_CAMEL}.apk"
if [[ "$VARIANT" == "debug" ]]; then
  GRADLE_TASK="assembleGithubDebug"
else
  GRADLE_TASK="assembleGithubRelease"
fi

declare -a ADB_CANDIDATES=()

is_wsl() {
  [[ -n "${WSL_DISTRO_NAME:-}" ]] && return 0
  grep -qiE "(microsoft|wsl)" /proc/version 2>/dev/null
}

add_adb_candidate() {
  local candidate="$1"
  [[ -z "$candidate" ]] && return
  [[ ! -f "$candidate" ]] && return

  local existing
  for existing in "${ADB_CANDIDATES[@]}"; do
    [[ "$existing" == "$candidate" ]] && return
  done
  ADB_CANDIDATES+=("$candidate")
}

adb_has_device() {
  local adb_bin="$1"
  "$adb_bin" devices 2>/dev/null | tr -d '\r' | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'
}

adb_device_count() {
  local adb_bin="$1"
  "$adb_bin" devices 2>/dev/null | tr -d '\r' | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }'
}

adb_list_serials() {
  local adb_bin="$1"
  "$adb_bin" devices 2>/dev/null | tr -d '\r' | awk 'NR > 1 && $2 == "device" { print $1 }'
}

if [[ -n "${ADB:-}" ]]; then
  add_adb_candidate "$ADB"
fi

if is_wsl; then
  shopt -s nullglob
  for candidate in /mnt/c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe; do
    add_adb_candidate "$candidate"
  done
  shopt -u nullglob
fi

add_adb_candidate "${ANDROID_SDK_ROOT:-}/platform-tools/adb"
add_adb_candidate "${ANDROID_HOME:-}/platform-tools/adb"

if command -v adb >/dev/null 2>&1; then
  add_adb_candidate "$(command -v adb)"
fi

if [[ ${#ADB_CANDIDATES[@]} -eq 0 ]]; then
  echo "adb not found. Set ADB, ANDROID_SDK_ROOT, or ANDROID_HOME."
  exit 1
fi

ADB_BIN=""
for candidate in "${ADB_CANDIDATES[@]}"; do
  if adb_has_device "$candidate"; then
    ADB_BIN="$candidate"
    break
  fi
done

if [[ -z "$ADB_BIN" ]]; then
  ADB_BIN="${ADB_CANDIDATES[0]}"
fi

echo "=== Build ($VARIANT) ==="
echo "Build root: $BUILD_ROOT"
set -o pipefail

# Run detekt first so style/code-quality issues surface without blocking the build
# (ignoreFailures = true in build.gradle.kts, so non-zero doesn't kill install).
echo "=== Detekt ==="
bash gradlew -PfrigateViewerBuildRoot="$BUILD_ROOT" detekt --quiet 2>&1 | \
  grep -E "^(> Task|[A-Za-z0-9/_.-]+\.kt:[0-9]+|BUILD|FAILURE|detekt)" | \
  tee /dev/stderr > /dev/null || true

if ! bash gradlew -PfrigateViewerBuildRoot="$BUILD_ROOT" "$GRADLE_TASK" 2>&1 | tee /dev/stderr > /dev/null; then
  echo ""
  echo "=== Compilation errors ==="
  DAEMON_LOG=$(ls -t ~/.gradle/daemon/*/daemon-*.out.log 2>/dev/null | head -1)
  if [[ -n "$DAEMON_LOG" ]]; then
    grep "file:///" "$DAEMON_LOG" | tail -20
  fi
  exit 1
fi

if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK"
  exit 1
fi

echo "=== Install ==="
echo "Using adb: $ADB_BIN"
echo "APK: $APK"

DEVICE_COUNT=$(adb_device_count "$ADB_BIN")
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
  echo "No devices attached — use 'adb devices' to check." >&2
  exit 1
fi

# Expand 'all' to every currently attached serial; preserve order, dedupe.
declare -a RESOLVED_SERIALS=()
for entry in "${TARGET_SERIALS[@]}"; do
  if [[ "$entry" == "all" ]]; then
    while read -r s; do
      [[ -z "$s" ]] && continue
      RESOLVED_SERIALS+=("$s")
    done < <(adb_list_serials "$ADB_BIN")
  else
    RESOLVED_SERIALS+=("$entry")
  fi
done

declare -a INSTALL_SERIALS=()
declare -A seen=()
for s in "${RESOLVED_SERIALS[@]}"; do
  [[ -n "${seen[$s]:-}" ]] && continue
  seen[$s]=1
  INSTALL_SERIALS+=("$s")
done

if [[ ${#INSTALL_SERIALS[@]} -eq 0 ]]; then
  if [[ "$DEVICE_COUNT" -gt 1 ]]; then
    echo "Multiple devices attached. Pass --serial <serial> (repeatable, or 'all') or set ANDROID_SERIAL:" >&2
    adb_list_serials "$ADB_BIN" | sed 's/^/  - /' >&2
    exit 1
  fi
  # Single device: let adb pick it.
  INSTALL_SERIALS+=("")
fi

INSTALL_FAILED=0
for serial in "${INSTALL_SERIALS[@]}"; do
  if [[ -n "$serial" ]]; then
    echo "--- Target device: $serial ---"
    if ! "$ADB_BIN" -s "$serial" install -r "$APK"; then
      INSTALL_FAILED=1
    fi
  else
    if ! "$ADB_BIN" install -r "$APK"; then
      INSTALL_FAILED=1
    fi
  fi
done

if [[ "$INSTALL_FAILED" -ne 0 ]]; then
  exit 1
fi

echo "=== Done ==="
