#!/bin/bash
# Generating and applying the vendored forks' patches.
#
# Shared by the regen scripts, the verify script and the sync workflows, so a patch is written in
# exactly the form the thing that applies it expects.
#
# The form matters. `git apply --3way` can only merge if the patch carries `index <pre>..<post>`
# lines and the pre-image blob is reachable in the object database. Plain `diff -u` output has
# neither, and then an upstream release that touches any line near an adaptation makes the whole
# patch fail — the sync workflow reports "did not apply", the re-vendored file stays pristine, and
# the adaptation is gone until somebody notices it is missing.
#
# With three-way, the same release merges: the adaptation survives an unrelated change, and a real
# collision arrives as conflict markers in the file, which is a thing a person can resolve.

# --- Generating -------------------------------------------------------------------------------

# vox_patch_diff <pristine-file> <patched-file> <repo-relative-label>
#
# One file's diff, on stdout, labelled a/<label> and b/<label> so it applies with -p1 from the
# repository root. `git diff --no-index` rather than `diff -u`: it writes the index lines.
vox_patch_diff() {
    local pristine="$1" patched="$2" label="$3"
    # Exits 1 when the files differ, which is the normal case here.
    git diff --no-index --src-prefix=a/ --dst-prefix=b/ "$pristine" "$patched" 2>/dev/null \
        | sed -e "s|^diff --git a/.* b/.*|diff --git a/$label b/$label|" \
              -e "s|^--- a/.*|--- a/$label|" \
              -e "s|^+++ b/.*|+++ b/$label|" \
        || true
}

# --- Applying ---------------------------------------------------------------------------------

# vox_patch_apply_3way <patch-file> [pristine-file ...]
#
# Applies <patch-file> to the working tree, merging rather than refusing when upstream has moved.
# Each pristine file is written into the object database first, because that is what the patch's
# index lines name as the merge base; without them git reports "sha1 information is lacking" and
# falls back to a plain apply.
#
# Echoes one word and returns:
#   clean     0   applied, whether or not upstream had drifted
#   conflict  0   applied with conflict markers — a person has to resolve them
#   failed    1   could not apply at all
#
# Conflicts are checked for explicitly: `git apply --3way` exits 0 when it leaves markers behind,
# so a caller that only tests the exit code will commit a file with `<<<<<<<` in it.
vox_patch_apply_3way() {
    local patch="$1"; shift
    local pristine

    for pristine in "$@"; do
        [ -f "$pristine" ] && git hash-object -w "$pristine" >/dev/null 2>&1
    done

    if ! git apply --3way "$patch" >/dev/null 2>&1; then
        # No merge was possible. Try a plain apply so a patch predating this format still lands.
        if git apply "$patch" >/dev/null 2>&1; then
            echo clean; return 0
        fi
        echo failed; return 1
    fi

    if [ -n "$(git diff --name-only --diff-filter=U 2>/dev/null)" ]; then
        echo conflict; return 0
    fi
    echo clean; return 0
}

# vox_patch_conflicts — the files left with conflict markers by the call above, one per line.
vox_patch_conflicts() {
    git diff --name-only --diff-filter=U 2>/dev/null
}
