#!/bin/bash
set -e

# Syncs GitHub release bodies (changelogs) and fastlane assets to F-Droid metadata structure.
# Format: metadata/<applicationId>/en-US/changelogs/<versionCode>.txt
# Assets: metadata/<applicationId>/en-US/icon.png, summary.txt, description.txt, phoneScreenshots/*.png

APPS=(
    "vox-commander:com.voxapps.commander:commander"
    "vox-notes:com.voxapps.notes:notes"
    "vox-expenses:com.voxapps.expenses:expenses"
    "vox-calendar:com.voxapps.calendar:calendar"
    "vox-hub:com.voxapps.hub:hub"
    "vox-vision:com.voxapps.vision:vision"
)

METADATA_DIR="metadata"
rm -rf "$METADATA_DIR"
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

    # Get the latest tag for this app from the local repo
    LATEST_TAG=$(git tag -l "${TAG_PREFIX}-v*" --sort=-v:refname | head -n 1)

    if [ -z "$LATEST_TAG" ]; then
        echo "  No tags found for $TAG_PREFIX, skipping changelog."
    else
        echo "  Latest tag: $LATEST_TAG"

        # Extract versionCode from the build.gradle.kts
        VERSION_CODE=$(grep 'versionCode' "$DIR/build.gradle.kts" | grep -oE '[0-9]+' || echo "")

        if [ -z "$VERSION_CODE" ]; then
            echo "  Warning: Could not find versionCode for $DIR, skipping changelog."
        else
            # Fetch release body from GitHub
            echo "  Fetching release body for $LATEST_TAG..."
            CHANGELOG=$(gh release view "$LATEST_TAG" --json body -q .body 2>/dev/null || echo "")

            if [ -z "$CHANGELOG" ] || [ "$CHANGELOG" == "null" ]; then
                echo "  No release body found for $LATEST_TAG yet, using generic message."
                CHANGELOG="Maintenance update and performance improvements."
            fi

            # Create directory structure for changelogs
            CHANGELOG_DIR="$METADATA_DIR/$APP_ID/en-US/changelogs"
            mkdir -p "$CHANGELOG_DIR"

            # Write changelog file
            echo "$CHANGELOG" > "$CHANGELOG_DIR/${VERSION_CODE}.txt"
            echo "  Changelog written to $CHANGELOG_DIR/${VERSION_CODE}.txt"
        fi
    fi

    # --- Sync Fastlane Assets (Icons, Descriptions, Screenshots) ---
    FASTLANE_DIR="$DIR/fastlane/metadata/android/en-US"
    TARGET_EN_DIR="$METADATA_DIR/$APP_ID/en-US"
    mkdir -p "$TARGET_EN_DIR"

    # Icon
    if [ -f "$FASTLANE_DIR/images/icon.png" ]; then
        cp "$FASTLANE_DIR/images/icon.png" "$TARGET_EN_DIR/icon.png"
        echo "  Icon synced."
    fi

    # Summary (short description)
    if [ -f "$FASTLANE_DIR/short_description.txt" ]; then
        cp "$FASTLANE_DIR/short_description.txt" "$TARGET_EN_DIR/summary.txt"
        echo "  Summary synced."
    fi

    # Description (full description)
    if [ -f "$FASTLANE_DIR/full_description.txt" ]; then
        cp "$FASTLANE_DIR/full_description.txt" "$TARGET_EN_DIR/description.txt"
        echo "  Description synced."
    fi

    # Screenshots (Standard F-Droid structure: en-US/phoneScreenshots/)
    # Note: We try both locations to be safe, but documentation often points to direct subfolder
    if [ -d "$FASTLANE_DIR/images/phoneScreenshots" ]; then
        mkdir -p "$TARGET_EN_DIR/phoneScreenshots"
        mkdir -p "$TARGET_EN_DIR/images/phoneScreenshots"
        for ext in png jpg jpeg; do
            cp "$FASTLANE_DIR/images/phoneScreenshots"/*.$ext "$TARGET_EN_DIR/phoneScreenshots/" 2>/dev/null || true
            cp "$FASTLANE_DIR/images/phoneScreenshots"/*.$ext "$TARGET_EN_DIR/images/phoneScreenshots/" 2>/dev/null || true
        done
        echo "  Screenshots synced."
    fi
done

echo "F-Droid metadata generation complete."
