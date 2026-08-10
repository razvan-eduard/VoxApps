#!/bin/bash
# Sourced by scripts/vox and by every script under scripts/. Holds what 14 of them were each
# carrying their own copy of: colours, log functions, and the project root.
#
# It is deliberately tiny and dependency-free. These scripts run inside Gradle at build time, so
# anything slow or networked here would be paid on every compile.
#
# Sourcing this does NOT make a script dependent on being launched by `vox` — every script under
# scripts/ must still run correctly when invoked directly. The dispatcher routes and documents; it
# never initialises anything a child needs.

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()  { printf "${GREEN}%s${NC}\n" "$1"; }
log_warn()  { printf "${YELLOW}%s${NC}\n" "$1"; }
log_error() { printf "${RED}%s${NC}\n" "$1"; }
log_blue()  { printf "${BLUE}%s${NC}\n" "$1"; }

# Resolves from this file's own location, so it is correct whether the caller is scripts/vox,
# a script in scripts/, a Gradle Exec task with its own working directory, or a CI step.
VOX_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT_ROOT="${PROJECT_ROOT:-$VOX_ROOT}"
