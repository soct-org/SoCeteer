#!/usr/bin/env python3
"""
Extract supportedChiselVersions from build.sbt and print a JSON array, e.g.:
  ["7.9.0", "3.6.1"]

Expected build.sbt snippet (single or multi-line is OK):
  val supportedChiselVersions = Seq("7.9.0", "3.6.1")
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def die(msg: str, code: int = 1) -> None:
    print(f"Error: {msg}", file=sys.stderr)
    sys.exit(code)


def main() -> None:
    build_sbt = Path(sys.argv[1] if len(sys.argv) > 1 else "build.sbt")
    if not build_sbt.is_file():
        die(f"{build_sbt} not found")

    text = build_sbt.read_text(encoding="utf-8", errors="replace")

    m = re.search(
        r"(?s)^\s*val\s+supportedChiselVersions\s*=\s*Seq\s*\(\s*(.*?)\s*\)",
        text,
        flags=re.MULTILINE,
    )
    if not m:
        die("Could not find: val supportedChiselVersions = Seq(...) in build.sbt")

    inside = m.group(1)

    # Extract quoted strings inside the Seq(...)
    versions = re.findall(r'"([^"]+)"', inside)
    if not versions:
        die("Found supportedChiselVersions Seq(...), but no quoted versions inside")

    print(json.dumps(versions))


if __name__ == "__main__":
    main()