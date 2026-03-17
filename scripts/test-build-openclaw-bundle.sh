#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_PATH="$ROOT_DIR/scripts/build-openclaw-bundle.sh"
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

assert_contains() {
  local path=$1
  local expected=$2
  grep -F -- "$expected" "$path" >/dev/null || fail "expected '$expected' in $path"
}

assert_not_contains() {
  local path=$1
  local unexpected=$2
  if grep -F "$unexpected" "$path" >/dev/null; then
    fail "did not expect '$unexpected' in $path"
  fi
}

FAKE_BIN="$TMP_DIR/fake-bin"
FAKE_STATE="$TMP_DIR/fake-state"
OUTPUT_TAR="$TMP_DIR/openclaw-runtime.tar"
EXTRACT_DIR="$TMP_DIR/extracted"
SCRIPT_OUTPUT="$TMP_DIR/script-output.log"
mkdir -p "$FAKE_BIN" "$FAKE_STATE" "$EXTRACT_DIR"

cat > "$FAKE_BIN/npm" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

LOG_FILE="${FAKE_STATE_DIR:?}/npm.log"
printf '%s\n' "$*" >> "$LOG_FILE"

prefix=""
package_spec=""
args=("$@")
i=0
while [[ $i -lt ${#args[@]} ]]; do
  case "${args[$i]}" in
    --prefix)
      i=$((i + 1))
      prefix="${args[$i]}"
      ;;
    openclaw@*)
      package_spec="${args[$i]}"
      ;;
  esac
  i=$((i + 1))
done

[[ -n "$prefix" ]] || {
  echo "missing --prefix" >&2
  exit 1
}
[[ -n "$package_spec" ]] || {
  echo "missing openclaw package spec" >&2
  exit 1
}

version="${package_spec#openclaw@}"
mkdir -p \
  "$prefix/node_modules/openclaw/bin" \
  "$prefix/node_modules/@scope/helper" \
  "$prefix/node_modules/@lydell/node-pty"
cat > "$prefix/node_modules/openclaw/package.json" <<JSON
{
  "name": "openclaw",
  "version": "$version"
}
JSON
cat > "$prefix/node_modules/openclaw/bin/openclaw.js" <<'JSON'
console.log("openclaw");
JSON
cat > "$prefix/node_modules/@scope/helper/package.json" <<'JSON'
{
  "name": "@scope/helper",
  "version": "1.0.0"
}
JSON
cat > "$prefix/node_modules/@lydell/node-pty/package.json" <<'JSON'
{
  "name": "@lydell/node-pty",
  "version": "1.2.0-beta.3"
}
JSON
EOF
chmod +x "$FAKE_BIN/npm"

printf 'stale-output' > "$OUTPUT_TAR"
FAKE_STATE_DIR="$FAKE_STATE" PATH="$FAKE_BIN:$PATH" "$SCRIPT_PATH" "2026.3.13" "$OUTPUT_TAR" >"$SCRIPT_OUTPUT" 2>&1

assert_file_exists "$OUTPUT_TAR"
tar -xf "$OUTPUT_TAR" -C "$EXTRACT_DIR"
assert_file_exists "$EXTRACT_DIR/node_modules/openclaw/package.json"
assert_file_exists "$EXTRACT_DIR/node_modules/@scope/helper/package.json"
assert_contains "$EXTRACT_DIR/node_modules/openclaw/package.json" '"version": "2026.3.13"'
assert_contains "$FAKE_STATE/npm.log" "openclaw@2026.3.13"
assert_contains "$FAKE_STATE/npm.log" "--os"
assert_contains "$FAKE_STATE/npm.log" "android"
assert_contains "$FAKE_STATE/npm.log" "--cpu"
assert_contains "$FAKE_STATE/npm.log" "arm64"
assert_not_contains "$OUTPUT_TAR" "stale-output"
assert_contains "$SCRIPT_OUTPUT" "Installing openclaw@2026.3.13 for android/arm64..."
assert_contains "$SCRIPT_OUTPUT" "@lydell/node-pty"
assert_contains "$SCRIPT_OUTPUT" "android-arm64"

echo "PASS: build-openclaw-bundle.sh generated runtime tar for openclaw@2026.3.13"
