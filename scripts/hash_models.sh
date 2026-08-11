#!/bin/bash
set -uo pipefail

# Fills in the `sha256` beside a model's URL in a schema, by downloading it once and hashing it.
#
# The field is what binds a signed schema's URL to the bytes that should arrive there. Writing 96 of
# them by hand is the reason a feature like this never gets adopted, so this does it — one model, a
# whole engine, or everything.
#
#     ./scripts/vox schemas hash-models                    # everything missing a hash
#     ./scripts/vox schemas hash-models stt_whisper        # one engine
#     ./scripts/vox schemas hash-models --dry-run          # say what would be fetched
#     ./scripts/vox schemas hash-models --schema vox-vision/src/main/assets/ocr_models.json
#
# Two file shapes are understood: the engine registry (`{"engines": {…"models": [{path, sha256}]}}`,
# the commander schema) and the flat map (`{"<name>": {url, sha256}}`, vision's bundled
# ocr_models.json). Downloads to a temp file and discards it; nothing lands in the repo but the
# hash. Entries that already carry one are skipped, so it is safe to re-run and cheap to resume.
#
# A schema under remote-schemas/ must be re-signed afterwards:  ./scripts/vox schemas sign
# (bundled assets are covered by the APK signature and need nothing.)

# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
cd "$VOX_ROOT" || exit 1

SCHEMA="remote-schemas/commander/models.json"
DRY_RUN=false
ENGINE=""

EXPECT_SCHEMA=false
for arg in "$@"; do
    if $EXPECT_SCHEMA; then
        SCHEMA="$arg"
        EXPECT_SCHEMA=false
        continue
    fi
    case "$arg" in
        --dry-run) DRY_RUN=true ;;
        --schema) EXPECT_SCHEMA=true ;;
        *) ENGINE="$arg" ;;
    esac
done
$EXPECT_SCHEMA && { log_error "❌ --schema needs a path"; exit 1; }

[ -f "$SCHEMA" ] || { log_error "❌ No $SCHEMA"; exit 1; }
command -v python3 >/dev/null || { log_error "❌ python3 is needed to rewrite the JSON safely"; exit 1; }

log_blue "🔍 Looking for models with a URL and no sha256…"

# Python does the JSON, because a schema is not a thing to edit with sed: it has to come back out
# byte-identical apart from the field being added, or the signature covers a file nobody meant.
DRY_RUN="$DRY_RUN" ENGINE="$ENGINE" SCHEMA="$SCHEMA" python3 <<'PY'
import json, os, subprocess, sys, tempfile, hashlib

schema_path = os.environ["SCHEMA"]
dry_run = os.environ["DRY_RUN"] == "true"
only_engine = os.environ["ENGINE"]

with open(schema_path) as f:
    doc = json.load(f)

targets = []
if isinstance(doc.get("engines"), dict):
    # The engine registry shape: engines -> models -> {path, sha256}.
    for engine_key, engine in (doc["engines"] or {}).items():
        if only_engine and engine_key != only_engine:
            continue
        for model in (engine.get("models") or []):
            url = model.get("path") or ""
            if url.startswith("http") and not model.get("sha256"):
                targets.append((engine_key, model, url))
else:
    # The flat map shape: name -> {url, sha256}.
    for name, entry in doc.items():
        if only_engine and name != only_engine:
            continue
        if not isinstance(entry, dict):
            continue
        url = entry.get("url") or ""
        if url.startswith("http") and not entry.get("sha256"):
            targets.append((name, entry, url))

if not targets:
    print("  Nothing to do — every downloadable model already declares a sha256.")
    sys.exit(0)

print(f"  {len(targets)} model(s) without a hash"
      + (f" in {only_engine}" if only_engine else "") + ".")
if dry_run:
    for engine_key, model, url in targets:
        print(f"    {engine_key:22} {model.get('id','?'):28} {url}")
    sys.exit(0)

done = 0
for engine_key, model, url in targets:
    mid = model.get("id", "?")
    print(f"  ↓ {engine_key}/{mid}", flush=True)
    with tempfile.NamedTemporaryFile(delete=True) as tmp:
        result = subprocess.run(
            ["curl", "-sSL", "--fail", "--max-time", "900", "-o", tmp.name, url],
            capture_output=True, text=True)
        if result.returncode != 0:
            print(f"    ✗ could not fetch: {result.stderr.strip()[:100]}")
            continue
        digest = hashlib.sha256()
        with open(tmp.name, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                digest.update(chunk)
        model["sha256"] = digest.hexdigest()
        done += 1
        print(f"    ✓ {model['sha256'][:16]}…")

if done:
    with open(schema_path, "w") as f:
        json.dump(doc, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"\n  Wrote {done} hash(es) into {schema_path}")
    if schema_path.startswith("remote-schemas/"):
        print("  Re-sign the schemas:  ./scripts/vox schemas sign")
PY
