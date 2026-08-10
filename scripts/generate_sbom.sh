#!/bin/bash
set -euo pipefail

# The bill of materials for one app, as CycloneDX JSON.
#
#     ./scripts/vox release sbom commander
#     ./scripts/vox release sbom vision --out /tmp/
#
# Two sources, because one is not enough here. The CycloneDX Gradle plugin describes the resolved
# dependency graph, which is every Maven and JitPack artifact the APK links. It cannot describe what
# this repository compiles from vendored source — whisper.cpp, OpenCV, PaddleOCR, OpenWakeWord are
# submodules built at build time, so they appear in no configuration and would be missing from a
# plain SBOM of an app that ships them. Those are added here from the submodule each is pinned to,
# which is the same commit the build compiles.
#
# See docs/BUILD_TIME_DEPENDENCIES.md for what each vendored dependency is and how it is kept in
# step with upstream.

# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
cd "$VOX_ROOT" || exit 1

APP="${1:-}"
OUT_DIR="."
shift || true
while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT_DIR="${2:?--out needs a directory}"; shift 2 ;;
        *) log_error "Unknown argument: $1"; exit 1 ;;
    esac
done

case "$APP" in
    commander|vision|notes|calendar|expenses|hub) ;;
    *) log_error "Usage: vox release sbom <commander|vision|notes|calendar|expenses|hub> [--out DIR]"; exit 1 ;;
esac

MODULE=":vox-$APP"
log_blue "📦 Resolving $MODULE's dependency graph…"
./gradlew "$MODULE:cyclonedxBom" --no-daemon -q

BOM=$(find "vox-$APP/build" -name "bom.json" -print -quit)
[ -n "$BOM" ] || { log_error "❌ The CycloneDX plugin produced no bom.json for $MODULE."; exit 1; }

# Which vendored sources this app actually compiles. An app that ships none gets none — an SBOM
# claiming OpenCV for Vox Notes would be worse than one that omits it.
case "$APP" in
    commander) VENDORED="vox-commander/src/main/cpp/whisper.cpp|whisper.cpp|https://github.com/ggml-org/whisper.cpp
vendor/openwakeword-android-kt|openwakeword-android-kt|https://github.com/rementia/openwakeword-android-kt" ;;
    vision)    VENDORED="vendor/opencv|opencv|https://github.com/opencv/opencv
vendor/paddleocr-upstream|PaddleOCR|https://github.com/PaddlePaddle/PaddleOCR" ;;
    *)         VENDORED="" ;;
esac

VERSION_NAME=$(grep -m1 'versionName' "vox-$APP/build.gradle.kts" | sed 's/.*"\(.*\)".*/\1/')
# Named like the APK beside it: VoxCommander-sbom.json, not Voxcommander-.
APP_NAME="Vox$(printf '%s' "${APP:0:1}" | tr '[:lower:]' '[:upper:]')${APP:1}"
mkdir -p "$OUT_DIR"
TARGET="$OUT_DIR/${APP_NAME}-sbom.json"

BOM="$BOM" VENDORED="$VENDORED" TARGET="$TARGET" APP_NAME="$APP_NAME" VERSION_NAME="$VERSION_NAME" python3 <<'PY'
import json, os, subprocess

bom = json.load(open(os.environ["BOM"]))
added = 0

for line in filter(None, (l.strip() for l in os.environ["VENDORED"].split("\n"))):
    path, name, url = line.split("|")
    # The commit the submodule is pinned to *is* the version: it is what the build compiles, and it
    # is more precise than the tag it may or may not sit on.
    sha = subprocess.run(["git", "rev-parse", f"HEAD:{path}"],
                         capture_output=True, text=True).stdout.strip()
    if not sha:
        continue
    bom.setdefault("components", []).append({
        "type": "library",
        "name": name,
        "version": sha[:12],
        "scope": "required",
        "purl": f"pkg:github/{url.split('github.com/')[1]}@{sha}",
        "externalReferences": [{"type": "vcs", "url": url}],
        "properties": [
            {"name": "voxapps:vendored", "value": path},
            {"name": "voxapps:builtFromSource", "value": "true"},
        ],
    })
    added += 1

meta = bom.setdefault("metadata", {})
meta.setdefault("component", {})
meta["component"].update({
    "type": "application",
    "name": os.environ["APP_NAME"],
    "version": os.environ["VERSION_NAME"],
})

json.dump(bom, open(os.environ["TARGET"], "w"), indent=2)
print(f"  {len(bom.get('components', []))} components ({added} built from vendored source)")
PY

log_info "✅ $TARGET"
