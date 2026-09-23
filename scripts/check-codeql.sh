#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v codeql >/dev/null 2>&1; then
  echo "CodeQL is required. Run 'mise install', then 'mise exec -- ./scripts/check-codeql.sh'." >&2
  exit 2
fi

mkdir -p "$ROOT/build/codeql"
SCAN_DIR="$(mktemp -d "$ROOT/build/codeql/scan.XXXXXX")"
echo "CodeQL database and results: $SCAN_DIR"

# Match CI: capture fresh compilation, including generated sources.
# Invoke the wrapper's Java entry point directly: macOS system shells can break tracing.
codeql database create "$SCAN_DIR/java-db" \
  --language=java \
  --source-root="$ROOT" \
  --command='java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain compileJava --no-daemon --no-build-cache --rerun-tasks'

codeql database analyze "$SCAN_DIR/java-db" \
  java-code-scanning.qls \
  --format=sarif-latest \
  --output="$SCAN_DIR/results.sarif" \
  --sarif-category='/language:java-kotlin' \
  --threads=0

# Reuse the computed results; CSV has one record per alert and no header.
# This lets us fail on findings without another JSON parser dependency.
codeql database interpret-results "$SCAN_DIR/java-db" \
  java-code-scanning.qls \
  --format=csv \
  --output="$SCAN_DIR/results.csv"

printf '\nCodeQL scan complete. Results: %s/results.sarif\n' "$SCAN_DIR"
echo "Open the report in a SARIF viewer, or import java-db into the CodeQL VS Code extension."
echo "Nothing was uploaded."

if [ -s "$SCAN_DIR/results.csv" ]; then
  echo "FAIL: CodeQL reported findings:" >&2
  cat "$SCAN_DIR/results.csv" >&2
  exit 1
fi

echo "PASS: CodeQL reported no findings."
