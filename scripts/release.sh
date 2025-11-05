#!/usr/bin/env bash

set -euo pipefail


# Usage function
usage() {
    echo "Usage: ./scripts/release.sh <version> [--dry-run] [--skip-push]"
}

# Parse arguments
VERSION=""
DRY_RUN=false
SKIP_PUSH=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --skip-push)
            SKIP_PUSH=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            if [[ -z "$VERSION" ]]; then
                VERSION="$1"
            else
                echo "Error: Unknown argument: $1" >&2
                usage
                exit 1
            fi
            shift
            ;;
    esac
done

if [[ -z "$VERSION" ]]; then
    echo "Error: Version is required" >&2
    usage
    exit 1
fi

# Validate version format (basic semver check)
if [[ ! "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.-]+)?$ ]]; then
    echo "Error: Version must follow semantic versioning format (e.g., v1.2.3 or v1.2.3-beta.1)" >&2
    exit 1
fi

if ! git diff-index --quiet HEAD --; then
    echo "Error: Working directory is not clean." >&2
    git status --porcelain
    exit 1
fi

# Check if local branch is in sync with remote
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
git fetch origin "$CURRENT_BRANCH" > /dev/null 2>&1 || {
    echo "Error: Failed to fetch remote branch '$CURRENT_BRANCH'" >&2
    exit 1
}

LOCAL_COMMIT=$(git rev-parse HEAD)
REMOTE_COMMIT=$(git rev-parse "origin/$CURRENT_BRANCH" 2>/dev/null || echo "")

if [[ -z "$REMOTE_COMMIT" ]]; then
    echo "Error: Remote branch 'origin/$CURRENT_BRANCH' does not exist" >&2
    exit 1
fi

if [[ "$LOCAL_COMMIT" != "$REMOTE_COMMIT" ]]; then
    echo "Error: Local branch '$CURRENT_BRANCH' is not in sync with remote 'origin/$CURRENT_BRANCH'" >&2
    echo "Local:  $LOCAL_COMMIT"
    echo "Remote: $REMOTE_COMMIT"
    echo "Please pull or push to sync before releasing."
    exit 1
fi

if git tag --list | grep -q "^$VERSION$"; then
    echo "Error: Version '$VERSION' already exists locally" >&2
    exit 1
fi

# Check remote tags
git fetch --tags > /dev/null 2>&1 || true
if git ls-remote --tags origin | grep -q "refs/tags/$VERSION$"; then
    echo "Error: Version '$VERSION' already exists on remote" >&2
    exit 1
fi

# Show release information
COMMIT=$(git rev-parse HEAD)
SHORT_COMMIT=$(git rev-parse --short HEAD)
REPO_URL=$(git config --get remote.origin.url | sed 's/git@github.com:/https:\/\/github.com\//' | sed 's/\.git$//')
LAST_TAG=$(git tag --sort=-version:refname | grep -v -- '-rc' | head -n 1 2>/dev/null || echo "")

echo "================================================"
echo " Java SDK Release"
echo "================================================"
printf "%-13s %s\n" "version:" "$VERSION"
printf "%-13s %s\n" "commit:" "$SHORT_COMMIT"
printf "%-13s %s\n" "code:" "$REPO_URL/commit/$COMMIT"
if [[ -n "$LAST_TAG" ]]; then
    printf "%-13s %s\n" "changeset:" "$REPO_URL/compare/$LAST_TAG...$COMMIT"
else
    printf "%-13s %s\n" "changeset:" "$REPO_URL/commits/$COMMIT"
fi
echo ""

# Confirmation prompt (skip in dry-run)
if [[ "$DRY_RUN" == true ]]; then
    echo "dry-run was requested. Bailing"
    exit 0
fi

read -p "Are you ready to release version $VERSION? Type 'YOLO' to continue: " -r
echo ""
if [[ "$REPLY" != "YOLO" ]]; then
    echo "Release aborted"
    exit 0
fi

if ! ./gradlew check; then
    echo "Error: ./gradlew check" >&2
    exit 1
fi

git tag -a "$VERSION" -m "Release $VERSION"
if [[ "$SKIP_PUSH" == true ]]; then
    echo "skip-push was requested. tag is created locally but not pushed. Do what you will."
    exit 0
fi
git push origin "$VERSION"

echo "================================================"
echo " Release Complete!"
echo "================================================"
echo "Version $VERSION has been created and pushed to origin."
echo ""
echo "View changelog: $REPO_URL/releases/tag/$VERSION"
