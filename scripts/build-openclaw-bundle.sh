#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_OUTPUT_DIR="$ROOT_DIR/build/openclaw-bundles"
TMP_DIR=""
KEEP_TMP=false

usage() {
  cat <<EOF
Usage:
  $SCRIPT_NAME <openclaw-version> [output.tar]
  $SCRIPT_NAME openclaw@<version> [output.tar]

Examples:
  $SCRIPT_NAME 2026.3.13
  $SCRIPT_NAME openclaw@2026.3.13 /tmp/openclaw-runtime.tar

Description:
  Installs the requested OpenClaw version into a temporary npm workspace and
  archives the resulting node_modules tree as a runtime bundle that BotDrop's
  offline APK packaging can consume via BOTDROP_OPENCLAW_BUNDLE_TGZ.

Environment:
  BOTDROP_BUNDLE_NPM_OS        Override npm os target. Default: android
  BOTDROP_BUNDLE_NPM_CPU       Override npm cpu target. Default: arm64
  BOTDROP_BUNDLE_NPM_PLATFORM  Deprecated alias for BOTDROP_BUNDLE_NPM_OS
  BOTDROP_BUNDLE_NPM_ARCH      Deprecated alias for BOTDROP_BUNDLE_NPM_CPU
EOF
}

cleanup() {
  if [[ "$KEEP_TMP" == true || -z "$TMP_DIR" ]]; then
    return
  fi
  rm -rf "$TMP_DIR"
}

fail() {
  echo "$SCRIPT_NAME: $*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

normalize_install_spec() {
  local version=$1
  if [[ "$version" == openclaw@* ]]; then
    printf '%s\n' "$version"
  else
    printf 'openclaw@%s\n' "$version"
  fi
}

resolve_version_from_package_json() {
  local package_json=$1
  sed -n 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$package_json" | head -n 1
}

warn_known_android_runtime_gaps() {
  local install_root=$1
  local node_pty_wrapper="$install_root/node_modules/@lydell/node-pty/package.json"

  if [[ -f "$node_pty_wrapper" ]]; then
    cat >&2 <<'EOF'
Warning: @lydell/node-pty is present in this bundle, but upstream does not ship an
android-arm64 prebuilt package. OpenClaw can fall back to non-PTY execution for
some commands, but interactive PTY-backed flows may not work on device.
EOF
  fi
}

main() {
  trap cleanup EXIT

  [[ $# -ge 1 && $# -le 2 ]] || {
    usage >&2
    exit 1
  }

  local requested_version=$1
  [[ -n "$requested_version" ]] || fail "openclaw version is required"

  require_cmd npm
  require_cmd tar
  require_cmd sed
  require_cmd mktemp

  local install_spec
  install_spec="$(normalize_install_spec "$requested_version")"

  TMP_DIR="$(mktemp -d)"
  local install_root="$TMP_DIR/runtime"
  mkdir -p "$install_root"

  cat > "$install_root/package.json" <<'EOF'
{
  "name": "botdrop-openclaw-bundle",
  "private": true
}
EOF

  local npm_os="${BOTDROP_BUNDLE_NPM_OS:-${BOTDROP_BUNDLE_NPM_PLATFORM:-android}}"
  local npm_cpu="${BOTDROP_BUNDLE_NPM_CPU:-${BOTDROP_BUNDLE_NPM_ARCH:-arm64}}"

  echo "Installing $install_spec for ${npm_os}/${npm_cpu}..."
  (
    cd "$install_root"
    npm install \
      --prefix "$install_root" \
      --no-package-lock \
      --ignore-scripts \
      --omit=dev \
      --no-audit \
      --no-fund \
      --os "$npm_os" \
      --cpu "$npm_cpu" \
      "$install_spec"
  )

  local package_json="$install_root/node_modules/openclaw/package.json"
  [[ -f "$package_json" ]] || fail "npm install did not produce node_modules/openclaw/package.json"

  local resolved_version
  resolved_version="$(resolve_version_from_package_json "$package_json")"
  [[ -n "$resolved_version" ]] || fail "failed to resolve installed OpenClaw version from $package_json"

  local output_path="${2:-$DEFAULT_OUTPUT_DIR/openclaw-runtime-${resolved_version}.tar}"
  local output_dir
  output_dir="$(dirname "$output_path")"
  mkdir -p "$output_dir"

  if [[ -d "$output_path" ]]; then
    fail "output path is a directory, expected a tar file: $output_path"
  fi

  warn_known_android_runtime_gaps "$install_root"

  echo "Creating runtime bundle at $output_path..."
  local tmp_output="$TMP_DIR/$(basename "$output_path").tmp"
  rm -f "$tmp_output" "$output_path"
  COPYFILE_DISABLE=1 tar -cf "$tmp_output" -C "$install_root" node_modules
  mv "$tmp_output" "$output_path"

  echo
  echo "Resolved OpenClaw version: $resolved_version"
  echo "Bundle: $output_path"
  echo "Gradle env:"
  echo "  BOTDROP_BUNDLED_OPENCLAW_VERSION=$resolved_version"
  echo "  BOTDROP_OPENCLAW_BUNDLE_TGZ=$output_path"
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

main "$@"
