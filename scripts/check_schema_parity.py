#!/usr/bin/env python3
"""Check that nothing an app ships is missing from what the repository publishes.

The two copies are alternatives, not layers: an install that has fetched successfully runs the
published file and never consults the one in the APK, and an install with repository updates turned
off runs only the one in the APK. That is the design — one source in force, and a visible setting
choosing which.

What the design assumes, and nothing enforced until this script, is that the published copy is never
*behind* the shipped one. Add a term to the asset alone and it reaches only installs that have never
fetched, which in practice is nobody: the term looks added, every test passes, and the capture it was
meant to fix carries on failing on every real device. That failure is silent and survives a release.

So the rule is one-directional. Publishing more than you ship is how a term is added without a
release and stays allowed; shipping more than you publish is the mistake.

    python3 scripts/check_schema_parity.py

Exit code is 1 if any shipped list has an entry its published twin lacks.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PUBLISHED = ROOT / "remote-schemas"

# Only the manifest is legitimately shipped without a published twin: it describes the published
# files rather than being one of them.
NOT_PUBLISHED = {"manifest.json"}

problems: list[str] = []


def entries(value, path: str = "") -> dict[str, list]:
    """Every list of scalars in a document, keyed by where it sits.

    Comparing per list rather than per file is what makes the message useful — "banks is missing
    Pluxee" instead of "the files differ".
    """
    found: dict[str, list] = {}
    if isinstance(value, dict):
        for key, child in value.items():
            found.update(entries(child, f"{path}.{key}" if path else key))
    elif isinstance(value, list) and all(not isinstance(x, (dict, list)) for x in value):
        found[path] = value
    elif isinstance(value, list):
        for index, child in enumerate(value):
            found.update(entries(child, f"{path}[{index}]"))
    return found


def app_name(app_dir: Path) -> str:
    """`vox-expenses/src/main/assets/schemas` -> `expenses`, the folder name used under remote-schemas."""
    for parent in app_dir.parents:
        if parent.name.startswith("vox-"):
            return parent.name[len("vox-"):]
    raise ValueError(f"not inside a vox-* module: {app_dir}")


def check(app_dir: Path) -> None:
    for shipped_file in sorted(app_dir.glob("*.json")):
        if shipped_file.name in NOT_PUBLISHED:
            continue
        published_file = PUBLISHED / app_name(app_dir) / shipped_file.name
        if not published_file.exists():
            problems.append(f"{shipped_file}: nothing published at {published_file}")
            continue
        shipped = entries(json.loads(shipped_file.read_text(encoding="utf-8")))
        published = entries(json.loads(published_file.read_text(encoding="utf-8")))
        for key, values in shipped.items():
            missing = [v for v in values if v not in published.get(key, [])]
            if missing:
                problems.append(
                    f"{published_file}: '{key}' is missing {missing} — present in {shipped_file}. "
                    "An install that has fetched will never see them."
                )


def main() -> int:
    app_dirs = sorted(ROOT.glob("vox-*/src/main/assets/schemas"))
    if not app_dirs:
        print("No shipped schema folders found — nothing to check.")
        return 0
    for app_dir in app_dirs:
        check(app_dir)

    if problems:
        print("Published schemas are behind what an app ships:\n")
        for problem in problems:
            print(f"  - {problem}")
        print("\nAdd the missing entries to remote-schemas/ and re-sign: ./scripts/vox schemas sign")
        return 1

    print(f"✅ Every shipped schema entry is published ({len(app_dirs)} app(s) checked).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
