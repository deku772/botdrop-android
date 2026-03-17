#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_PATH="$ROOT_DIR/scripts/build-qqbot-plugin-bundle.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_file_exists() {
  local path=$1
  [[ -f "$path" ]] || fail "expected file to exist: $path"
}

assert_dir_exists() {
  local path=$1
  [[ -d "$path" ]] || fail "expected directory to exist: $path"
}

assert_contains() {
  local path=$1
  local expected=$2
  grep -F -- "$expected" "$path" >/dev/null || fail "expected '$expected' in $path"
}

assert_not_exists() {
  local path=$1
  [[ ! -e "$path" ]] || fail "did not expect path to exist: $path"
}

assert_matches() {
  local path=$1
  local pattern=$2
  grep -E -- "$pattern" "$path" >/dev/null || fail "expected pattern '$pattern' in $path"
}

assert_not_contains() {
  local path=$1
  local unexpected=$2
  if grep -F -- "$unexpected" "$path" >/dev/null; then
    fail "did not expect '$unexpected' in $path"
  fi
}

FAKE_BIN="$TMP_DIR/fake-bin"
FAKE_STATE="$TMP_DIR/fake-state"
OUTPUT_DIR="$TMP_DIR/qqbot-dir"
mkdir -p "$FAKE_BIN" "$FAKE_STATE"

cat > "$FAKE_BIN/npm" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

LOG_FILE="${FAKE_STATE_DIR:?}/npm.log"
printf '%s\n' "$*" >> "$LOG_FILE"

if [[ "${1:-}" == "pack" ]]; then
  package_spec="${2:-}"
  [[ "$package_spec" == @sliverp/qqbot@* ]] || {
    echo "unexpected package spec: $package_spec" >&2
    exit 1
  }

  version="${package_spec#@sliverp/qqbot@}"
  tar_name="sliverp-qqbot-${version}.tgz"
  pack_root="$(mktemp -d "${TMPDIR:-/tmp}/qqbot-pack.XXXXXX")"
  mkdir -p \
    "$pack_root/package/skills/qqbot-cron" \
    "$pack_root/package/node_modules/ws" \
    "$pack_root/package/node_modules/silk-wasm" \
    "$pack_root/package/node_modules/mpg123-decoder"

  cat > "$pack_root/package/package.json" <<JSON
{
  "name": "@sliverp/qqbot",
  "version": "$version",
  "bundleDependencies": [
    "ws",
    "silk-wasm",
    "mpg123-decoder"
  ],
  "dependencies": {
    "ws": "^8.18.0",
    "silk-wasm": "^3.7.1",
    "mpg123-decoder": "^1.0.3"
  }
}
JSON
  cat > "$pack_root/package/openclaw.plugin.json" <<'JSON'
{
  "id": "qqbot"
}
JSON
  cat > "$pack_root/package/skills/qqbot-cron/SKILL.md" <<'EOF_SKILL'
# qqbot cron
EOF_SKILL
  cat > "$pack_root/package/node_modules/ws/package.json" <<'JSON'
{
  "name": "ws",
  "version": "8.18.0"
}
JSON
  cat > "$pack_root/package/node_modules/silk-wasm/package.json" <<'JSON'
{
  "name": "silk-wasm",
  "version": "3.7.1"
}
JSON
  cat > "$pack_root/package/node_modules/mpg123-decoder/package.json" <<'JSON'
{
  "name": "mpg123-decoder",
  "version": "1.0.3"
}
JSON

  tar -czf "$tar_name" -C "$pack_root" package
  printf '%s\n' "$tar_name"
  exit 0
fi
echo "unexpected npm invocation: $*" >&2
exit 99
EOF
chmod +x "$FAKE_BIN/npm"

mkdir -p "$OUTPUT_DIR/package"
printf 'stale plugin data' > "$OUTPUT_DIR/package/STALE.txt"

FAKE_STATE_DIR="$FAKE_STATE" PATH="$FAKE_BIN:$PATH" "$SCRIPT_PATH" "1.5.4" "$OUTPUT_DIR"

assert_dir_exists "$OUTPUT_DIR/package"
assert_file_exists "$OUTPUT_DIR/package/package.json"
assert_file_exists "$OUTPUT_DIR/package/openclaw.plugin.json"
assert_file_exists "$OUTPUT_DIR/package/node_modules/ws/package.json"
assert_file_exists "$OUTPUT_DIR/package/node_modules/silk-wasm/package.json"
assert_file_exists "$OUTPUT_DIR/package/node_modules/mpg123-decoder/package.json"
assert_file_exists "$OUTPUT_DIR/package/skills/qqbot-cron/SKILL.md"
assert_not_exists "$OUTPUT_DIR/package/STALE.txt"
assert_contains "$OUTPUT_DIR/package/package.json" '"version": "1.5.4"'
assert_contains "$FAKE_STATE/npm.log" "pack @sliverp/qqbot@1.5.4"
assert_not_contains "$FAKE_STATE/npm.log" "install"

echo "PASS: build-qqbot-plugin-bundle.sh generated package dir for @sliverp/qqbot@1.5.4"
