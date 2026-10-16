#!/usr/bin/env bash
set -euo pipefail

REPO="https://github.com/braintrustdata/braintrust-spec"
REF="${1:?Usage: $0 <sha-or-tag>}"

OUTDIR="${2:-.}"
mkdir -p "$OUTDIR"

# The archive endpoint validates tags and historical SHAs; ls-remote only lists ref tips.
curl -sfL "$REPO/archive/$REF.tar.gz" -o /tmp/braintrust-spec-$$.tar.gz || {
  echo "Error: failed to download archive for '$REF'" >&2
  exit 1
}

tar -xzf /tmp/braintrust-spec-$$.tar.gz --strip-components=1 -C "$OUTDIR"
rm -f /tmp/braintrust-spec-$$.tar.gz
echo "Fetched $REF into $OUTDIR"
