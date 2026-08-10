#!/bin/bash
# Sourced by every scripts/check_*_version.sh, AFTER their log_* definitions (it overrides them).
#
# Gives each check script a `--report` mode: machine-readable `key=value` lines on stdout, all human
# logging diverted to stderr. That is what lets one script serve two callers — a person reading the
# output, and a sync workflow doing:
#
#     ./scripts/check_vosk_version.sh --report >> "$GITHUB_OUTPUT"
#
# Before this, every sync-*.yml carried its own copy of its script's detection logic in a `run:`
# block. Four of the six pairs happened to agree; keeping them agreeing was manual, and the one that
# drifted (PaddleOCR) drifted silently.
#
# Each script emits the key names its own workflow already consumes — has_update always, plus
# whatever identifies a version there (current/latest, current_tag/latest_tag, current_sha/…).

REPORT=false
for _arg in "$@"; do
    [ "$_arg" = "--report" ] && REPORT=true
done

# In report mode stdout is reserved for key=value pairs, so anything human goes to stderr.
if [ "$REPORT" = true ]; then
    log_info()  { printf '%s\n' "$1" >&2; }
    log_warn()  { printf '%s\n' "$1" >&2; }
    log_error() { printf '%s\n' "$1" >&2; }
    log_blue()  { printf '%s\n' "$1" >&2; }
fi

# Emits only in report mode — a no-op for a human run.
emit() {
    [ "$REPORT" = true ] && printf '%s=%s\n' "$1" "$2"
    return 0
}
