#!/usr/bin/env bash
set -euo pipefail

REPO="https://github.com/braintrustdata/braintrust-spec"
REF="${1:?Usage: $0 <sha-or-tag>}"

# Verify the ref exists
if ! git ls-remote --exit-code "$REPO" "$REF" >/dev/null 2>&1; then
  # ls-remote matches refs by name; for a raw SHA we need to actually try fetching
  RESOLVED=$(git ls-remote "$REPO" | awk '{print $1}' | grep -q "^${REF}" && echo yes || echo no)
  if [ "$RESOLVED" = "no" ]; then
    echo "Error: ref '$REF' not found in $REPO" >&2
    exit 1
  fi
fi

OUTDIR="${2:-.}"
mkdir -p "$OUTDIR"

# Download and extract the tarball — no full clone needed
curl -sfL "$REPO/archive/$REF.tar.gz" -o /tmp/braintrust-spec-$$.tar.gz || {
  echo "Error: failed to download archive for '$REF'" >&2
  exit 1
}

tar -xzf /tmp/braintrust-spec-$$.tar.gz --strip-components=1 -C "$OUTDIR"
rm -f /tmp/braintrust-spec-$$.tar.gz
echo "Fetched $REF into $OUTDIR"
