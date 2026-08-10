#!/bin/bash
set -uo pipefail

# Signs the shipped schemas, so an app can tell "this is what the maintainer published" apart from
# "this is what the server sent me".
#
# Why this exists: every launch fetches remote-schemas/<app>/*.json and adopts what it gets, with no
# authenticity check — the SHA-256 already in RemoteSchema compares the download against the *last
# download*, which answers "did this change?", not "is this genuine?". Those schemas define engine
# endpoints and the NLU prompt, so whoever can serve that path can change where every install sends
# speech, at the next launch, without an app update.
#
# The shape is a signed manifest rather than a signature per file: one signature covers every
# filename and hash together, so removing or adding a file is as detectable as editing one.
#
#     ./scripts/vox schemas sign      # writes remote-schemas/manifest.json{,.sig}
#     ./scripts/vox schemas verify    # checks the manifest against the files and the public key
#
# Key handling: the private key never goes to GitHub. It is not a CI secret, on purpose — the
# release keystore already is one (it must be, or CI could not sign APKs), and putting this key
# beside it would mean a single account compromise yields both a malicious APK and malicious
# schemas. Signing is local; CI only verifies. The public half is committed
# (remote-schemas/signing-key.pub) and compiled into the apps, which is what makes verification mean
# anything.

# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
cd "$VOX_ROOT" || exit 1

# Where key material lives on a developer machine: outside the repository, so `git clean -xfd`
# cannot delete it and no ignore rule is load-bearing. Same directory as the release keystore.
DEFAULT_KEY="$VOX_KEY_DIR/schema-signing.pem"

SCHEMA_DIR="remote-schemas"
MANIFEST="$SCHEMA_DIR/manifest.json"
SIGNATURE="$SCHEMA_DIR/manifest.json.sig"
PUBLIC_KEY="$SCHEMA_DIR/signing-key.pub"

MODE="${1:-sign}"

# sha256 of a file, portable between macOS (shasum) and Ubuntu runners (sha256sum).
file_hash() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

# Every schema, keyed by the path an app actually requests (<folder>/<file>) so the manifest is
# checkable against a URL without knowing anything about this repo's layout.
build_manifest() {
    {
        echo '{'
        echo '  "version": 1,'
        # A counter the app refuses to go backwards on. Without it, an attacker who can serve files
        # but cannot sign could replay an OLD, genuinely-signed manifest and its old schemas — every
        # signature checking out while the app quietly downgrades to, say, an endpoint since moved
        # off. Seconds since the epoch: monotonic, and no state to keep between runs.
        echo "  \"serial\": ${SERIAL:-$(date -u +%s)},"
        echo '  "files": {'
        first=true
        while IFS= read -r f; do
            rel="${f#"$SCHEMA_DIR"/}"
            [ "$first" = true ] || echo ','
            printf '    "%s": "%s"' "$rel" "$(file_hash "$f")"
            first=false
        done < <(find "$SCHEMA_DIR" -mindepth 2 -name '*.json' ! -name 'manifest.json' | sort)
        echo
        echo '  }'
        echo '}'
    }
}

case "$MODE" in
    sign)
        KEY_FILE="${SCHEMA_SIGNING_KEY_FILE:-}"
        [ -z "$KEY_FILE" ] && [ -f "$DEFAULT_KEY" ] && KEY_FILE="$DEFAULT_KEY"
        TMP_KEY=""
        if [ -z "$KEY_FILE" ] && [ -n "${SCHEMA_SIGNING_KEY:-}" ]; then
            TMP_KEY=$(mktemp)
            printf '%s' "$SCHEMA_SIGNING_KEY" > "$TMP_KEY"
            KEY_FILE="$TMP_KEY"
        fi
        if [ -z "$KEY_FILE" ] || [ ! -f "$KEY_FILE" ]; then
            log_error "❌ No signing key."
            log_error "   This is signed locally by design — it is not a CI secret."
            log_error "   Locally: put it at $DEFAULT_KEY, or set SCHEMA_SIGNING_KEY_FILE"
            log_error "   To create one: ./scripts/vox schemas keygen"
            exit 1
        fi

        # The serial is a clock reading, and a clock can go backwards — a machine with a skewed or
        # just-corrected clock would sign a manifest the apps then refuse as older than the one they
        # already have, and nothing would say so: updates would simply stop arriving. Never emit a
        # serial that is not greater than the one already published.
        PREV_SERIAL=0
        [ -f "$MANIFEST" ] && PREV_SERIAL=$(grep -oE '"serial": *[0-9]+' "$MANIFEST" | grep -oE '[0-9]+' || echo 0)
        NOW=$(date -u +%s)
        if [ "$NOW" -le "$PREV_SERIAL" ]; then
            log_warn "⚠️ This machine's clock ($NOW) is not ahead of the published serial ($PREV_SERIAL)."
            log_warn "   Using $((PREV_SERIAL + 1)) so the apps still accept this — but check the clock."
            SERIAL=$((PREV_SERIAL + 1))
        else
            SERIAL="$NOW"
        fi
        export SERIAL

        build_manifest > "$MANIFEST"
        openssl dgst -sha256 -sign "$KEY_FILE" -out /tmp/manifest.sig.bin "$MANIFEST" || {
            log_error "❌ Signing failed."; [ -n "$TMP_KEY" ] && rm -f "$TMP_KEY"; exit 1; }
        openssl base64 -A -in /tmp/manifest.sig.bin -out "$SIGNATURE"
        rm -f /tmp/manifest.sig.bin
        [ -n "$TMP_KEY" ] && rm -f "$TMP_KEY"

        log_info "✅ Signed $(grep -c '": "' "$MANIFEST") schema(s)."
        log_info "   $MANIFEST"
        log_info "   $SIGNATURE"
        ;;

    verify)
        [ -f "$MANIFEST" ]   || { log_error "❌ No $MANIFEST — run: ./scripts/vox schemas sign"; exit 1; }
        [ -f "$SIGNATURE" ]  || { log_error "❌ No $SIGNATURE"; exit 1; }
        [ -f "$PUBLIC_KEY" ] || { log_error "❌ No $PUBLIC_KEY"; exit 1; }

        openssl base64 -d -A -in "$SIGNATURE" -out /tmp/manifest.sig.check
        if openssl dgst -sha256 -verify "$PUBLIC_KEY" -signature /tmp/manifest.sig.check "$MANIFEST" >/dev/null 2>&1; then
            log_info "✅ Manifest signature is valid."
        else
            log_error "❌ Manifest signature does NOT verify against $PUBLIC_KEY."
            rm -f /tmp/manifest.sig.check
            exit 1
        fi
        rm -f /tmp/manifest.sig.check

        # A valid signature over a stale manifest still means tampering, so check the files too.
        FAILED=0
        while IFS= read -r f; do
            rel="${f#"$SCHEMA_DIR"/}"
            want=$(grep -oE "\"$rel\": \"[0-9a-f]{64}\"" "$MANIFEST" | grep -oE '[0-9a-f]{64}')
            got=$(file_hash "$f")
            if [ -z "$want" ]; then
                log_error "  ❌ $rel is not in the manifest"; FAILED=1
            elif [ "$want" != "$got" ]; then
                log_error "  ❌ $rel does not match the manifest"; FAILED=1
            fi
        done < <(find "$SCHEMA_DIR" -mindepth 2 -name '*.json' ! -name 'manifest.json' | sort)

        if [ "$FAILED" -eq 0 ]; then
            log_info "✅ Every schema matches its recorded hash."
        else
            log_error "❌ Re-sign after changing a schema: ./scripts/vox schemas sign"
            exit 1
        fi
        ;;

    keygen)
        OUT="${2:-$DEFAULT_KEY}"
        mkdir -p "$(dirname "$OUT")" && chmod 700 "$(dirname "$OUT")" 2>/dev/null
        if [ -f "$OUT" ]; then
            log_error "❌ $OUT already exists — refusing to overwrite a signing key."
            exit 1
        fi
        # ECDSA P-256: java.security verifies SHA256withECDSA on every Android version this app
        # supports, unlike Ed25519 which needs API 33+.
        openssl ecparam -name prime256v1 -genkey -noout -out "$OUT"
        openssl ec -in "$OUT" -pubout -out "$PUBLIC_KEY"
        chmod 600 "$OUT"
        log_info "✅ Private key: $OUT  (NOT in the repo — keep it, and add it as the"
        log_info "   SCHEMA_SIGNING_KEY secret; anyone holding it can sign schemas for every install)"
        log_info "✅ Public key:  $PUBLIC_KEY  (committed, and embedded in the apps)"
        ;;

    *)
        log_error "Usage: $0 [sign|verify|keygen]"
        exit 1
        ;;
esac
