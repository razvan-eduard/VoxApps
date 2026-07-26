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

for entry in "${APPS[@]}"; do
    IFS=":" read -r DIR APP_ID TAG_PREFIX <<< "$entry"

    echo "Processing $APP_ID (dir: $DIR, prefix: $TAG_PREFIX)..."

    # Get the latest tag for this app
    LATEST_TAG=$(git tag -l "${TAG_PREFIX}-v*" --sort=-v:refname | head -n 1)

    if [ -z "$LATEST_TAG" ]; then
        echo "  No tags found for $TAG_PREFIX, skipping."
        continue
    fi

    echo "  Latest tag: $LATEST_TAG"

    # Extract versionCode from the build.gradle.kts of that tag
    # (Actually, we want the versionCode of the release we are processing.
    # Since we are usually running this AFTER a push that bumped it,
    # we might want to just read the current file if we assume we just pushed.)
    VERSION_CODE=$(grep 'versionCode' "$DIR/build.gradle.kts" | grep -oE '[0-9]+')

    if [ -z "$VERSION_CODE" ]; then
        echo "  Could not find versionCode for $DIR, skipping."
        continue
    fi

    echo "  Version code: $VERSION_CODE"

    # Fetch release body from GitHub
    CHANGELOG=$(gh release view "$LATEST_TAG" --json body -q .body)

    if [ -z "$CHANGELOG" ] || [ "$CHANGELOG" == "null" ]; then
        echo "  No release body found for $LATEST_TAG, using generic message."
        CHANGELOG="Bug fixes and improvements."
    fi

    # Create directory structure
    CHANGELOG_DIR="$METADATA_DIR/$APP_ID/en-US/changelogs"
    mkdir -p "$CHANGELOG_DIR"

    # Write changelog file
    echo "$CHANGELOG" > "$CHANGELOG_DIR/${VERSION_CODE}.txt"
    echo "  Changelog written to $CHANGELOG_DIR/${VERSION_CODE}.txt"
done

echo "F-Droid metadata sync complete."
