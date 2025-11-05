#!/usr/bin/env bash

set -euo pipefail

# Script to wait for a Maven artifact to become available on Maven Central
# Usage: ./scripts/wait-for-maven.sh <version>
# Example: ./scripts/wait-for-maven.sh 0.0.3

VERSION="${1:-}"

if [[ -z "$VERSION" ]]; then
    echo "Error: Version is required" >&2
    echo "Usage: $0 <version>" >&2
    echo "Example: $0 0.0.3" >&2
    exit 1
fi

# Configuration
GROUP_ID="dev.braintrust"
ARTIFACT_ID="braintrust-sdk-java"
MAX_DURATION_SECONDS=18000  # 5 hours
SLEEP_DURATION=300  # 5 minutes

# Convert group ID to path format (dev.braintrust -> dev/braintrust)
GROUP_PATH="${GROUP_ID//./\/}"

# Maven Central artifact URL
MAVEN_CENTRAL_URL="https://repo1.maven.org/maven2/${GROUP_PATH}/${ARTIFACT_ID}/${VERSION}/${ARTIFACT_ID}-${VERSION}.pom"

echo "================================================"
echo " Waiting for Maven Central"
echo "================================================"
echo "Group ID:    ${GROUP_ID}"
echo "Artifact ID: ${ARTIFACT_ID}"
echo "Version:     ${VERSION}"
echo "Max time:    5 hours"
echo ""
echo "Checking: ${MAVEN_CENTRAL_URL}"
echo ""

# Record start time
START_TIME=$(date +%s)
attempt=0

while true; do
    # Calculate elapsed time
    CURRENT_TIME=$(date +%s)
    ELAPSED_SECONDS=$((CURRENT_TIME - START_TIME))
    ELAPSED_MINUTES=$((ELAPSED_SECONDS / 60))

    # Check if we've exceeded the max duration
    if [[ $ELAPSED_SECONDS -ge $MAX_DURATION_SECONDS ]]; then
        break
    fi

    attempt=$((attempt + 1))
    echo "[Attempt ${attempt}] Checking Maven Central... (${ELAPSED_MINUTES} minutes elapsed)"

    # Check if the artifact exists (HTTP 200)
    http_code=$(curl -s -o /dev/null -w "%{http_code}" "${MAVEN_CENTRAL_URL}")

    if [[ "$http_code" == "200" ]]; then
        echo ""
        echo "✓ Success! Artifact is available on Maven Central"
        echo ""

        # Trigger javadoc.io to update
        JAVADOC_URL="https://javadoc.io/doc/${GROUP_ID}/${ARTIFACT_ID}/${VERSION}"
        echo "Triggering javadoc.io update..."
        echo "URL: ${JAVADOC_URL}"

        # Fire and forget - we don't wait for javadoc.io
        curl -s -o /dev/null "${JAVADOC_URL}" || true

        echo ""
        echo "================================================"
        echo " Maven Central Sync Complete!"
        echo "================================================"
        echo "Maven Central: https://central.sonatype.com/artifact/${GROUP_ID}/${ARTIFACT_ID}/${VERSION}"
        echo "Javadoc:       ${JAVADOC_URL}"
        echo ""

        exit 0
    elif [[ "$http_code" == "404" ]]; then
        echo "  → Not found yet (HTTP 404)"
    else
        echo "  → Unexpected HTTP status: ${http_code}"
    fi

    # Calculate remaining time
    REMAINING_SECONDS=$((MAX_DURATION_SECONDS - ELAPSED_SECONDS))

    # If we have time left, sleep for 5 minutes (or less if timeout is near)
    if [[ $REMAINING_SECONDS -gt 0 ]]; then
        SLEEP_TIME=$SLEEP_DURATION
        if [[ $REMAINING_SECONDS -lt $SLEEP_DURATION ]]; then
            SLEEP_TIME=$REMAINING_SECONDS
        fi
        echo "  → Sleeping for $((SLEEP_TIME / 60)) minutes..."
        echo ""
        sleep "$SLEEP_TIME"
    fi
done

echo ""
echo "================================================"
echo " Timeout!"
echo "================================================"
echo "Artifact did not appear on Maven Central after 5 hours."
echo "This might indicate a publishing issue."
echo ""
echo "Please check:"
echo "1. Sonatype Central Portal: https://central.sonatype.com/publishing"
echo "2. Build logs for errors"
echo "3. Maven Central manually: ${MAVEN_CENTRAL_URL}"
echo ""

exit 1
