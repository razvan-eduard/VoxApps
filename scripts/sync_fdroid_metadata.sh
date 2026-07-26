#!/bin/bash
set -e

# Syncs GitHub release bodies (changelogs) to F-Droid metadata structure.
# Format: metadata/<applicationId>/en-US/changelogs/<versionCode>.txt

APPS=(
    "vox-commander:com.voxapps.commander:commander"
    "vox-notes:com.voxapps.notes:notes"
    "vox-expenses:com.voxapps.expenses:expenses"
    "vox-calendar:com.voxapps.calendar:calendar"
    "vox-hub:com.voxapps.hub:hub"
    "vox-vision:com.voxapps.vision:vision"
)

METADATA_DIR="metadata"
mkdir -p "$METADATA_DIR"

# Check if gh is authenticated
if ! gh auth status >/dev/null 2>&1; then
    if [ -z "$GH_TOKEN" ] && [ -z "$GITHUB_TOKEN" ]; then
        echo "Error: gh CLI is not authenticated and GITHUB_TOKEN is not set."
        exit 1
    fi
fi

for entry in "${APPS[@]}"; do
    IFS=":" read -r DIR APP_ID TAG_PREFIX <<< "$entry"

    echo "Processing $APP_ID..."

    # Get the latest tag for this app from the local repo (fetch-depth 0 ensured tags are there)
    LATEST_TAG=$(git tag -l "${TAG_PREFIX}-v*" --sort=-v:refname | head -n 1)

    if [ -z "$LATEST_TAG" ]; then
        echo "  No tags found for $TAG_PREFIX, skipping."
        continue
    fi

    echo "  Latest tag: $LATEST_TAG"

    # Extract versionCode from the build.gradle.kts
    # We use the current file since we expect it to match the latest state
    VERSION_CODE=$(grep 'versionCode' "$DIR/build.gradle.kts" | grep -oE '[0-9]+' || echo "")

    if [ -z "$VERSION_CODE" ]; then
        echo "  Warning: Could not find versionCode for $DIR, skipping."
        continue
    fi

    # Fetch release body from GitHub. Don't fail if it doesn't exist yet (racing conditions)
    echo "  Fetching release body for $LATEST_TAG..."
    CHANGELOG=$(gh release view "$LATEST_TAG" --json body -q .body 2>/dev/null || echo "")

    if [ -z "$CHANGELOG" ] || [ "$CHANGELOG" == "null" ]; then
        echo "  No release body found for $LATEST_TAG yet, using generic message."
        CHANGELOG="Maintenance update and performance improvements."
    fi

    # Create directory structure
    CHANGELOG_DIR="$METADATA_DIR/$APP_ID/en-US/changelogs"
    mkdir -p "$CHANGELOG_DIR"

    # Write changelog file
    echo "$CHANGELOG" > "$CHANGELOG_DIR/${VERSION_CODE}.txt"
    echo "  Changelog written to $CHANGELOG_DIR/${VERSION_CODE}.txt"
done

echo "F-Droid metadata generation complete."
