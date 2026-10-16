#!/usr/bin/env bash
# Shared installer helpers for Mike local admin repos.
set -eu
(set -o pipefail) 2>/dev/null && set -o pipefail || true

mike_color_enabled() {
  if [[ -n "${NO_COLOR:-}" ]]; then
    return 1
  fi
  [[ -t 1 ]]
}

mike_color() {
  local code="$1"
  shift
  if mike_color_enabled; then
    printf '\033[%sm%s\033[0m' "$code" "$*"
  else
    printf '%s' "$*"
  fi
}

mike_info() { printf '%s %s\n' "$(mike_color '1;34' '==>')" "$*"; }
mike_warn() { printf '%s %s\n' "$(mike_color '1;33' '!')" "$*" >&2; }
mike_error() { printf '%s %s\n' "$(mike_color '1;31' 'x')" "$*" >&2; }

mike_detect_platform() {
  if [[ -f /system/build.prop || -n "${ANDROID_ROOT:-}" ]]; then
    echo "android"
  else
    echo "linux"
  fi
}

mike_resolve_python() {
  local platform="${1:-auto}"
  local requested="${2:-}"
  local android_use_usr="${3:-0}"
  if [[ -n "$requested" ]]; then
    echo "$requested"
    return 0
  fi
  if [[ "$platform" == "android" && "$android_use_usr" == "1" && -x /usr/bin/python3 ]]; then
    echo "/usr/bin/python3"
    return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    command -v python3
    return 0
  fi
  if command -v python >/dev/null 2>&1; then
    command -v python
    return 0
  fi
  mike_error "No python interpreter found"
  return 1
}

mike_install_usage() {
  local extra="${1:-}"
  cat <<EOF
Usage: ./install.sh [options]

Common options:
  --platform linux|android|auto
  --python PATH
  --verbose
  --android-use-usr-bin-python3
  -h, --help
${extra}
EOF
}

mike_parse_common_args() {
  MIKE_PLATFORM="auto"
  MIKE_PYTHON_BIN=""
  MIKE_VERBOSE="0"
  MIKE_ANDROID_USE_USR_BIN_PYTHON3="0"
  MIKE_WITH_GNOME_EXTENSION="0"
  MIKE_GNOME_EXTENSION_ONLY="0"
  MIKE_REMAINING_ARGS=()

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --platform) MIKE_PLATFORM="${2:?missing platform}"; shift 2 ;;
      --python) MIKE_PYTHON_BIN="${2:?missing python path}"; shift 2 ;;
      --android-use-usr-bin-python3) MIKE_ANDROID_USE_USR_BIN_PYTHON3="1"; shift ;;
      --with-gnome-extension) MIKE_WITH_GNOME_EXTENSION="1"; shift ;;
      --gnome-extension-only) MIKE_GNOME_EXTENSION_ONLY="1"; shift ;;
      --verbose) MIKE_VERBOSE="1"; shift ;;
      -h|--help) MIKE_SHOW_HELP="1"; shift ;;
      *) MIKE_REMAINING_ARGS+=("$1"); shift ;;
    esac
  done

  if [[ "${MIKE_PLATFORM}" == "auto" ]]; then
    MIKE_PLATFORM="$(mike_detect_platform)"
  fi
  MIKE_PYTHON_BIN="$(mike_resolve_python "${MIKE_PLATFORM}" "${MIKE_PYTHON_BIN}" "${MIKE_ANDROID_USE_USR_BIN_PYTHON3}")"
}
