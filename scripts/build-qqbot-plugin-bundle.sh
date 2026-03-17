#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_OUTPUT_ROOT="$ROOT_DIR/build/openclaw-bundles"
DEFAULT_PACKAGE_NAME="@sliverp/qqbot"
TMP_DIR=""

usage() {
  cat <<EOF
Usage:
  $SCRIPT_NAME <qqbot-version> [output-dir]
  $SCRIPT_NAME @sliverp/qqbot@<version> [output-dir]

Examples:
  $SCRIPT_NAME 1.5.4
  $SCRIPT_NAME @sliverp/qqbot@1.5.4 /tmp/qqbot-dir

Description:
  Downloads the requested QQ Bot plugin package, extracts it to a package/
  directory, installs production dependencies into that directory, and emits a
  layout that can be passed directly to BOTDROP_QQBOT_PLUGIN_DIR.

Environment:
  BOTDROP_BUNDLE_NPM_OS        Override npm os target. Default: android
  BOTDROP_BUNDLE_NPM_CPU       Override npm cpu target. Default: arm64
  BOTDROP_BUNDLE_NPM_PLATFORM  Deprecated alias for BOTDROP_BUNDLE_NPM_OS
  BOTDROP_BUNDLE_NPM_ARCH      Deprecated alias for BOTDROP_BUNDLE_NPM_CPU
EOF
}

cleanup() {
  if [[ -n "$TMP_DIR" ]]; then
    rm -rf "$TMP_DIR"
  fi
}

fail() {
  echo "$SCRIPT_NAME: $*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

normalize_package_spec() {
  local version=$1
  if [[ "$version" == @sliverp/qqbot@* ]]; then
    printf '%s\n' "$version"
  else
    printf '%s@%s\n' "$DEFAULT_PACKAGE_NAME" "$version"
  fi
}

resolve_version_from_package_json() {
  local package_json=$1
  sed -n 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$package_json" | head -n 1
}

collect_missing_dependency_state() {
  local package_json=$1
  local package_dir=$2
  local output_json=$3

  node - "$package_json" "$package_dir" "$output_json" <<'EOF'
const fs = require('fs');
const path = require('path');

const [packageJsonPath, packageDir, outputPath] = process.argv.slice(2);
const pkg = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8'));
const dependencies = pkg.dependencies || {};
const bundled = [
  ...new Set([...(pkg.bundleDependencies || []), ...(pkg.bundledDependencies || [])]),
];
const missing = {};

for (const [name, version] of Object.entries(dependencies)) {
  const depPackageJson = path.join(packageDir, 'node_modules', ...name.split('/'), 'package.json');
  if (!fs.existsSync(depPackageJson)) {
    missing[name] = version;
  }
}

fs.writeFileSync(
  outputPath,
  JSON.stringify({
    dependencies,
    bundled,
    missing,
  })
);
EOF
}

count_missing_dependencies() {
  local state_json=$1

  node - "$state_json" <<'EOF'
const fs = require('fs');
const state = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
process.stdout.write(String(Object.keys(state.missing || {}).length));
EOF
}

count_bundled_dependencies() {
  local state_json=$1

  node - "$state_json" <<'EOF'
const fs = require('fs');
const state = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
process.stdout.write(String((state.bundled || []).length));
EOF
}

write_missing_dependency_package_json() {
  local state_json=$1
  local output_json=$2

  node - "$state_json" "$output_json" <<'EOF'
const fs = require('fs');
const [statePath, outputPath] = process.argv.slice(2);
const state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
const pkg = {
  name: 'botdrop-qqbot-missing-deps',
  private: true,
  dependencies: state.missing || {},
};
fs.writeFileSync(outputPath, `${JSON.stringify(pkg, null, 2)}\n`);
EOF
}

main() {
  trap cleanup EXIT

  [[ $# -ge 1 && $# -le 2 ]] || {
    usage >&2
    exit 1
  }

  local requested_version=$1
  [[ -n "$requested_version" ]] || fail "qqbot version is required"

  require_cmd npm
  require_cmd node
  require_cmd tar
  require_cmd sed
  require_cmd mktemp

  local package_spec
  package_spec="$(normalize_package_spec "$requested_version")"

  TMP_DIR="$(mktemp -d)"
  local pack_dir="$TMP_DIR/pack"
  mkdir -p "$pack_dir"

  echo "Packing $package_spec..."
  local archive_name
  archive_name="$(
    cd "$pack_dir"
    npm pack "$package_spec" --silent | tail -n 1
  )"
  [[ -n "$archive_name" ]] || fail "npm pack did not return an archive name"

  local archive_path="$pack_dir/$archive_name"
  [[ -f "$archive_path" ]] || fail "npm pack archive not found: $archive_path"

  local extract_root="${2:-$DEFAULT_OUTPUT_ROOT/qqbot-$(basename "$archive_name" .tgz)}"
  if [[ -f "$extract_root" ]]; then
    fail "output path is a file, expected a directory: $extract_root"
  fi
  mkdir -p "$extract_root"

  local staged_root="$TMP_DIR/output"
  mkdir -p "$staged_root"
  rm -rf "$staged_root/package"

  echo "Extracting package to staged directory..."
  tar -xzf "$archive_path" -C "$staged_root"
  [[ -d "$staged_root/package" ]] || fail "archive did not contain a package/ directory"

  local npm_os="${BOTDROP_BUNDLE_NPM_OS:-${BOTDROP_BUNDLE_NPM_PLATFORM:-android}}"
  local npm_cpu="${BOTDROP_BUNDLE_NPM_CPU:-${BOTDROP_BUNDLE_NPM_ARCH:-arm64}}"

  local package_json="$staged_root/package/package.json"
  [[ -f "$package_json" ]] || fail "missing extracted package.json"
  [[ -f "$staged_root/package/openclaw.plugin.json" ]] || fail "missing openclaw.plugin.json"

  local dependency_state_json="$TMP_DIR/qqbot-dependency-state.json"
  collect_missing_dependency_state "$package_json" "$staged_root/package" "$dependency_state_json"

  local bundled_count
  bundled_count="$(count_bundled_dependencies "$dependency_state_json")"
  local missing_count
  missing_count="$(count_missing_dependencies "$dependency_state_json")"

  if [[ "$missing_count" -eq 0 ]]; then
    if [[ "$bundled_count" -gt 0 ]]; then
      echo "Using bundled production dependencies from package tarball."
    else
      echo "No production dependencies to install."
    fi
  else
    echo "Installing $missing_count missing production dependencies into staged package for ${npm_os}/${npm_cpu}..."
    local install_root="$TMP_DIR/missing-deps"
    mkdir -p "$install_root"
    write_missing_dependency_package_json "$dependency_state_json" "$install_root/package.json"
    npm install \
      --prefix "$install_root" \
      --no-package-lock \
      --ignore-scripts \
      --omit=dev \
      --omit=peer \
      --no-audit \
      --no-fund \
      --os "$npm_os" \
      --cpu "$npm_cpu"
    mkdir -p "$staged_root/package/node_modules"
    cp -R "$install_root/node_modules/." "$staged_root/package/node_modules/"
  fi

  local resolved_version
  resolved_version="$(resolve_version_from_package_json "$package_json")"
  [[ -n "$resolved_version" ]] || fail "failed to resolve installed QQ Bot version from $package_json"

  rm -rf "$extract_root/package"
  mv "$staged_root/package" "$extract_root/package"

  echo
  echo "Resolved QQ Bot version: $resolved_version"
  echo "Plugin dir: $extract_root/package"
  echo "Gradle env:"
  echo "  BOTDROP_QQBOT_PLUGIN_DIR=$extract_root/package"
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

main "$@"
