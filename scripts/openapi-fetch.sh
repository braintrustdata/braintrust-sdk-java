#!/usr/bin/env bash
# Fetches openapi/spec.yaml from a pinned commit (SHA or branch) of
# https://github.com/braintrustdata/braintrust-openapi
#
# Usage: openapi-fetch.sh <sha-or-branch> <output-dir>
set -euo pipefail

REPO="braintrustdata/braintrust-openapi"
REF="${1:?Usage: $0 <sha-or-branch> <output-dir>}"
OUTDIR="${2:-.}"

mkdir -p "$OUTDIR"

URL="https://raw.githubusercontent.com/${REPO}/${REF}/openapi/spec.yaml"

echo "Fetching braintrust-openapi@${REF} -> ${OUTDIR}/spec.yaml"
curl -sfL "$URL" -o "${OUTDIR}/spec.yaml" || {
    echo "Error: failed to download spec.yaml from ${URL}" >&2
    exit 1
}
echo "Done."
