"""Generate a curated index for AnymeX's Aniyomi desktop manager.

AnymeX runs Aniyomi extensions on Linux/macOS/Windows inside a JVM sidecar using
dex2jar. Sources that need Android-only components (torrent clients, Debrid
bridges, MediaSession/Stremio/Jellyfin integrations, Google Drive WebView
player) cannot work there, so they are left out of this index.

The canonical ``index.min.json`` is never touched: Redantotsu/Aniyomi on
Android keeps receiving every extension.
"""

import json
import re
from pathlib import Path

INDEX = Path("index.json")
OUTPUT = Path("index.anymex.json")

# Matched against the extension package name (case-insensitive).
DESKTOP_UNSUPPORTED_PATTERNS = [
    r"\.torrent",
    r"torrent",
    r"debrid",
    r"torbox",
    r"realdebrid",
    r"stremio",
    r"jellyfin",
    r"googledriveplayer",
    r"googledriveindex",
    r"googleDrive",
    r"m3u8server",
    r"local",
]

EXCLUDE = re.compile("|".join(DESKTOP_UNSUPPORTED_PATTERNS), re.IGNORECASE)


def main() -> None:
    if not INDEX.exists():
        raise SystemExit(f"Missing {INDEX}; run this from the repo branch checkout")

    with INDEX.open(encoding="utf-8") as f:
        index = json.load(f)

    kept = [entry for entry in index if not EXCLUDE.search(entry.get("pkg", ""))]

    for entry in kept:
        for source in entry.get("sources", []):
            source.pop("versionId", None)

    with OUTPUT.open("w", encoding="utf-8") as f:
        json.dump(kept, f, ensure_ascii=False, separators=(",", ":"))

    print(f"Wrote {OUTPUT} with {len(kept)} of {len(index)} extensions")


if __name__ == "__main__":
    main()
