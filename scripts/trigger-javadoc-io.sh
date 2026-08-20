#!/usr/bin/env bash

set -euo pipefail

# Asks javadoc.io to ingest a version that has already been released to
# Maven Central.
#
# Usage: ./scripts/trigger-javadoc-io.sh [--wait] <version> [group-id] [artifact-id]
# Example: ./scripts/trigger-javadoc-io.sh 0.3.20
#          ./scripts/trigger-javadoc-io.sh --wait 0.3.20
#
# By default this fires the request and returns as soon as javadoc.io has
# accepted it; javadoc.io generates the docs on its own schedule, which can
# take a while. Pass --wait to block until the version actually shows up as
# uploaded (useful when running this by hand).
#
# Why this exists: javadoc.io is supposed to ingest new Maven Central
# versions on its own, but our releases have been sitting at "Not uploaded
# yet" indefinitely. The fix is what a human does by hand: open
# https://javadoc.io/versions/<group>/<artifact>, press "Sync from Maven",
# tick the new version and press "Upload selected". This script replays
# exactly those two HTTP requests.
#
# BEST-EFFORT WORKAROUND: javadoc.io publishes no API. The two endpoints
# used here (POST /versions/<g>/<a>/sync and POST /versions/<g>/<a>/upload)
# were read off the site's own HTML forms; they are undocumented and
# private, so they can change or disappear without notice. They need no
# credentials -- only a Play-framework CSRF token, which the page hands to
# anonymous visitors along with a session cookie. If javadoc.io ever ships
# a real API, replace this script with it.

WAIT_FOR_UPLOAD=false
ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --wait) WAIT_FOR_UPLOAD=true; shift ;;
        -h|--help)
            echo "Usage: $0 [--wait] <version> [group-id] [artifact-id]"
            exit 0
            ;;
        -*)
            echo "Error: unknown flag '$1'" >&2
            echo "Usage: $0 [--wait] <version> [group-id] [artifact-id]" >&2
            exit 1
            ;;
        *) ARGS+=("$1"); shift ;;
    esac
done

VERSION="${ARGS[0]:-}"
GROUP_ID="${ARGS[1]:-dev.braintrust}"
ARTIFACT_ID="${ARGS[2]:-braintrust-sdk-java}"

if [[ -z "$VERSION" ]]; then
    echo "Error: Version is required" >&2
    echo "Usage: $0 [--wait] <version> [group-id] [artifact-id]" >&2
    echo "Example: $0 0.3.20" >&2
    exit 1
fi

# Tunables, env-overridable so CI can shorten them.
JAR_MAX_WAIT_SECONDS="${JAR_MAX_WAIT_SECONDS:-1800}"          # -javadoc.jar on Maven Central
JAR_POLL_SECONDS="${JAR_POLL_SECONDS:-30}"
DISCOVERY_MAX_WAIT_SECONDS="${DISCOVERY_MAX_WAIT_SECONDS:-120}" # version to appear on javadoc.io
DISCOVERY_POLL_SECONDS="${DISCOVERY_POLL_SECONDS:-10}"
TRIGGER_ATTEMPTS="${TRIGGER_ATTEMPTS:-3}"                     # retries for transient failures
INGEST_MAX_WAIT_SECONDS="${INGEST_MAX_WAIT_SECONDS:-900}"     # only used with --wait
INGEST_POLL_SECONDS="${INGEST_POLL_SECONDS:-20}"

GROUP_PATH="$(echo "$GROUP_ID" | tr '.' '/')"
JAVADOC_JAR_URL="https://repo1.maven.org/maven2/${GROUP_PATH}/${ARTIFACT_ID}/${VERSION}/${ARTIFACT_ID}-${VERSION}-javadoc.jar"
VERSIONS_URL="https://javadoc.io/versions/${GROUP_ID}/${ARTIFACT_ID}"
DOC_URL="https://javadoc.io/doc/${GROUP_ID}/${ARTIFACT_ID}/${VERSION}"

WORK_DIR="$(mktemp -d)"
COOKIE_JAR="${WORK_DIR}/cookies.txt"
PAGE="${WORK_DIR}/versions.html"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "================================================"
echo " javadoc.io ingestion"
echo "================================================"
echo "Artifact: ${GROUP_ID}:${ARTIFACT_ID}:${VERSION}"
echo "Jar:      ${JAVADOC_JAR_URL}"
echo "Versions: ${VERSIONS_URL}"
echo "Mode:     $([[ "$WAIT_FOR_UPLOAD" == true ]] && echo 'wait for docs to go live' || echo 'fire and forget')"
echo ""

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Loads the versions page into $PAGE, refreshing the session cookie, and
# prints the HTTP status code.
fetch_versions_page() {
    curl -sS --connect-timeout 15 --max-time 60 \
        --retry 3 --retry-delay 2 --retry-connrefused \
        -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
        -o "$PAGE" -w '%{http_code}' \
        "$VERSIONS_URL"
}

# Both forms carry a hidden CSRF token bound to the PLAY_SESSION cookie in
# $COOKIE_JAR, so token and cookie have to travel together.
#
# The trailing `|| true` in these parsing helpers is load-bearing: a no-match
# grep (or a SIGPIPE from head) fails the pipeline, and under `set -o
# pipefail` that failure propagates out of `x=$(helper)` and `set -e` kills
# the script on the spot. Every no-match here is an expected outcome that the
# caller handles, so it must not look like an error.
extract_csrf_token() {
    grep -o 'name="csrfToken" value="[^"]*"' "$PAGE" | head -1 | sed 's/.*value="//; s/"$//' || true
}

# Each version is a table row whose status badge is titled DISCOVERED
# ("Not uploaded yet") or UPLOADED (docs are live). Prints the badge title
# for $VERSION, or nothing when javadoc.io does not list the version -- the
# normal state right after a sync, since discovery is asynchronous, so the
# discovery poll below depends on that empty result being a success.
version_status() {
    grep -F -A4 "<td>${VERSION}</td>" "$PAGE" \
        | grep -o 'title="[A-Z_]*"' | head -1 | sed 's/title="//; s/"$//' || true
}

# Play answers both forms with 303 whether or not it acted on the request,
# so the status code alone proves little. The flash message is logged for
# context; the version's badge is the signal that means something.
post_form() {
    local path="$1"
    shift
    curl -sS --connect-timeout 15 --max-time 120 \
        -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
        -o /dev/null -w '%{http_code}' \
        -X POST "${VERSIONS_URL}${path}" "$@"
}

flash_message() {
    sed -n '/alert alert-/,/<\/div>/p' "$PAGE" \
        | sed 's/<[^>]*>//g; s/&times;//' \
        | sed 's/&#x27;/'"'"'/g; s/&quot;/"/g; s/&lt;/</g; s/&gt;/>/g; s/&amp;/\&/g' \
        | sed 's/<[^>]*>//g' \
        | tr -d '\t' | grep -v '^[[:space:]]*$' | head -2 | tr '\n' ' ' || true
}

diagnostics() {
    echo "" >&2
    echo "Diagnostics:" >&2
    echo "  javadoc.io status for ${VERSION}: '$(version_status)'" >&2
    echo "  last flash message: $(flash_message)" >&2
    echo "  versions page: ${VERSIONS_URL}" >&2
    echo "  javadoc jar:   ${JAVADOC_JAR_URL}" >&2
    echo "" >&2
    echo "Manual fallback: open ${VERSIONS_URL}, press 'Sync from Maven', tick" >&2
    echo "${VERSION} and press 'Upload selected'. Re-running the release" >&2
    echo "workflow with the same version also just retries this step." >&2
}

# ---------------------------------------------------------------------------
# 1. Wait for the -javadoc.jar to be served by Maven Central. javadoc.io
#    pulls the jar from repo1, so asking it to ingest a version whose javadoc
#    jar is not there yet accomplishes nothing.
# ---------------------------------------------------------------------------

echo "--- Waiting for the javadoc jar on Maven Central (up to $((JAR_MAX_WAIT_SECONDS / 60)) minutes)"
START_TIME=$(date +%s)
attempt=0
jar_bytes=""

while true; do
    attempt=$((attempt + 1))
    head_out=$(curl -sS --connect-timeout 15 --max-time 60 \
        -I -w '\nHTTP_CODE:%{http_code}\n' "$JAVADOC_JAR_URL" || echo "HTTP_CODE:000")
    http_code=$(printf '%s' "$head_out" | sed -n 's/^HTTP_CODE://p' | tail -1)

    if [[ "$http_code" == "200" ]]; then
        jar_bytes=$(printf '%s' "$head_out" \
            | awk 'tolower($1) == "content-length:" { gsub(/\r/, "", $2); print $2 }' | tail -1)
        echo "[Attempt ${attempt}] javadoc jar is available (HTTP 200, ${jar_bytes:-?} bytes)"
        break
    fi

    ELAPSED=$(($(date +%s) - START_TIME))
    echo "[Attempt ${attempt}] HTTP ${http_code} - not served yet ($((ELAPSED / 60))m elapsed)"

    if [[ $((ELAPSED + JAR_POLL_SECONDS)) -ge $JAR_MAX_WAIT_SECONDS ]]; then
        echo "" >&2
        echo "Error: ${JAVADOC_JAR_URL} was not served within ${JAR_MAX_WAIT_SECONDS}s." >&2
        echo "Maven Central has the version but not the javadoc jar; check the" >&2
        echo "'Publish to Sonatype' step." >&2
        exit 1
    fi
    sleep "$JAR_POLL_SECONDS"
done

# An empty javadoc jar (a manifest and nothing else, ~250 bytes) is accepted
# by Maven Central but javadoc.io has nothing to render from it, so it stays
# at "Not uploaded yet" no matter how often it is asked.
if [[ -n "$jar_bytes" && "$jar_bytes" -lt 10000 ]]; then
    echo "Warning: the javadoc jar is only ${jar_bytes} bytes, which usually means it is empty." >&2
    echo "javadoc.io cannot ingest an empty javadoc jar." >&2
fi

# ---------------------------------------------------------------------------
# 2. Ask javadoc.io to ingest it: sync so the version is discovered, then
#    upload it (the "Upload selected" button).
# ---------------------------------------------------------------------------

echo ""
echo "--- Triggering javadoc.io ingestion"

for ((try = 1; try <= TRIGGER_ATTEMPTS; try++)); do
    echo "[Try ${try}/${TRIGGER_ATTEMPTS}]"

    page_code=$(fetch_versions_page || echo "000")
    if [[ "$page_code" != "200" ]]; then
        echo "  GET ${VERSIONS_URL} -> HTTP ${page_code}" >&2
        if [[ $try -lt $TRIGGER_ATTEMPTS ]]; then
            sleep $((try * 10))
            continue
        fi
        echo "Error: could not load the javadoc.io versions page." >&2
        diagnostics
        exit 1
    fi

    if [[ "$(version_status)" == "UPLOADED" ]]; then
        echo "  ${VERSION} is already uploaded on javadoc.io; nothing to do."
        echo ""
        echo "Javadoc: ${DOC_URL}"
        exit 0
    fi

    # A pre-sync NO_JAVADOC can be stale -- javadoc.io may have scanned the
    # version before its javadoc jar was fully served. Note it and carry on;
    # the sync below asks for a re-scan, and the check after it decides.
    if [[ "$(version_status)" == "NO_JAVADOC" ]]; then
        echo "  Note: javadoc.io currently reports NO_JAVADOC for ${VERSION}; asking it to re-scan."
    fi

    token=$(extract_csrf_token)
    if [[ -z "$token" ]]; then
        echo "  No csrfToken on the page; javadoc.io markup may have changed." >&2
        if [[ $try -lt $TRIGGER_ATTEMPTS ]]; then
            sleep $((try * 10))
            continue
        fi
        echo "Error: could not parse the javadoc.io upload form." >&2
        diagnostics
        exit 1
    fi

    # "Sync from Maven": javadoc.io re-reads maven-metadata.xml so that a
    # brand-new version exists in its database. This is asynchronous.
    sync_code=$(post_form "/sync" --data-urlencode "csrfToken=${token}" || echo "000")
    echo "  POST ${VERSIONS_URL}/sync -> HTTP ${sync_code}"

    # The upload endpoint silently ignores versions it does not know about,
    # so wait for the row to show up before selecting it.
    DISCOVERY_START=$(date +%s)
    while true; do
        page_code=$(fetch_versions_page || echo "000")
        status=$(version_status)
        [[ -n "$status" ]] && break

        ELAPSED=$(($(date +%s) - DISCOVERY_START))
        if [[ $((ELAPSED + DISCOVERY_POLL_SECONDS)) -ge $DISCOVERY_MAX_WAIT_SECONDS ]]; then
            break
        fi
        echo "    ${VERSION} not listed yet (page HTTP ${page_code}, ${ELAPSED}s elapsed)"
        sleep "$DISCOVERY_POLL_SECONDS"
    done

    status=$(version_status)
    echo "  after sync, ${VERSION} status: '${status:-<not listed>}' ($(flash_message))"

    if [[ -z "$status" ]]; then
        echo "  ${VERSION} is still not listed on javadoc.io." >&2
        if [[ $try -lt $TRIGGER_ATTEMPTS ]]; then
            sleep $((try * 10))
            continue
        fi
        echo "Error: javadoc.io never discovered ${VERSION}, so nothing was queued." >&2
        diagnostics
        exit 1
    fi

    if [[ "$status" == "UPLOADED" ]]; then
        echo "  ${VERSION} is already uploaded on javadoc.io; nothing to do."
        echo ""
        echo "Javadoc: ${DOC_URL}"
        exit 0
    fi

    # NO_JAVADOC is javadoc.io's own verdict that the version's javadoc jar
    # holds nothing it can render. It is terminal: queueing another upload
    # cannot change it, so fail here instead of burning the retries and
    # reporting a request that will never produce docs.
    if [[ "$status" == "NO_JAVADOC" ]]; then
        echo "" >&2
        echo "Error: javadoc.io reports NO_JAVADOC for ${VERSION} -- it fetched the" >&2
        echo "javadoc jar and found no documentation in it. Triggering an upload" >&2
        echo "cannot fix that; the jar itself has to have content." >&2
        diagnostics
        exit 1
    fi

    # "Upload selected": one versionId per checkbox a human would tick.
    token=$(extract_csrf_token)
    upload_code=$(post_form "/upload" \
        --data-urlencode "csrfToken=${token}" \
        --data-urlencode "versionId=${VERSION}" || echo "000")
    echo "  POST ${VERSIONS_URL}/upload versionId=${VERSION} -> HTTP ${upload_code}"

    if [[ "$upload_code" != "303" && "$upload_code" != "200" ]]; then
        echo "  Unexpected status from the upload endpoint." >&2
        if [[ $try -lt $TRIGGER_ATTEMPTS ]]; then
            sleep $((try * 10))
            continue
        fi
        echo "Error: javadoc.io upload request failed with HTTP ${upload_code}." >&2
        diagnostics
        exit 1
    fi

    if [[ "$WAIT_FOR_UPLOAD" != true ]]; then
        # 303 does not mean javadoc.io acted: a rejected CSRF token answers
        # 303 too, with the flash "<path> is not valid" instead of "Upload
        # started for N version(s)". Read that back before reporting success,
        # so a silently dropped request spends the remaining retries rather
        # than passing as queued.
        page_code=$(fetch_versions_page || echo "000")
        flash=$(flash_message)

        if [[ "$flash" != *"Upload started"* ]]; then
            echo "  javadoc.io did not confirm the upload (page HTTP ${page_code})." >&2
            echo "  flash: ${flash:-<none>}" >&2
            if [[ $try -lt $TRIGGER_ATTEMPTS ]]; then
                sleep $((try * 10))
                continue
            fi
            echo "Error: javadoc.io never confirmed the upload request for ${VERSION}." >&2
            diagnostics
            exit 1
        fi

        echo ""
        echo "================================================"
        echo " Upload requested"
        echo "================================================"
        echo "javadoc.io accepted the request: ${flash}"
        echo "It generates the docs on its own schedule, so they may take a"
        echo "few minutes to appear."
        echo ""
        echo "Javadoc:  ${DOC_URL}"
        echo "Versions: ${VERSIONS_URL}"
        echo ""
        exit 0
    fi

    # --wait: the endpoint answers 303 even when it ignores the request, so
    # the badge flipping to UPLOADED is the only trustworthy confirmation.
    echo "  Waiting for the docs to go live (up to $((INGEST_MAX_WAIT_SECONDS / 60)) minutes)"
    VERIFY_START=$(date +%s)
    while true; do
        sleep "$INGEST_POLL_SECONDS"
        page_code=$(fetch_versions_page || echo "000")
        status=$(version_status)
        ELAPSED=$(($(date +%s) - VERIFY_START))

        if [[ "$status" == "UPLOADED" ]]; then
            echo ""
            echo "================================================"
            echo " Javadocs are live"
            echo "================================================"
            echo "Javadoc: ${DOC_URL}"
            echo ""
            exit 0
        fi

        if [[ "$status" == "NO_JAVADOC" ]]; then
            echo "" >&2
            echo "Error: javadoc.io processed ${VERSION} and found no documentation in" >&2
            echo "its javadoc jar (NO_JAVADOC). Waiting longer will not help." >&2
            diagnostics
            exit 1
        fi

        echo "    status='${status:-<not listed>}' (page HTTP ${page_code}, ${ELAPSED}s elapsed)"
        if [[ $((ELAPSED + INGEST_POLL_SECONDS)) -ge $INGEST_MAX_WAIT_SECONDS ]]; then
            break
        fi
    done

    echo "  ${VERSION} did not go live within ${INGEST_MAX_WAIT_SECONDS}s." >&2
    if [[ $try -lt $TRIGGER_ATTEMPTS ]]; then
        echo "  Retrying the trigger." >&2
    fi
done

echo "" >&2
echo "Error: javadoc.io did not ingest ${GROUP_ID}:${ARTIFACT_ID}:${VERSION} after ${TRIGGER_ATTEMPTS} attempts." >&2
diagnostics
exit 1
