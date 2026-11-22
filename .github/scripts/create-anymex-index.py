"""Generate a curated index for AnymeX's Aniyomi desktop manager.

AnymeX runs Aniyomi extensions on Linux/macOS/Windows inside a JVM sidecar using
dex2jar. Sources that need Android-only components (torrent clients, Debrid
bridges, MediaSession/Stremio/Jellyfin integrations, Google Drive WebView
player) cannot work there, so they are left out of this index.

The canonical ``index.min.json`` is never touched: Redantotsu/Aniyomi on
Android keeps receiving every extension.
"""

import json
import os
import re
from pathlib import Path

INDEX = Path("index.json")
OUTPUT = Path("index.anymex.json")

# AnymeX only strips /index.min.json, /index.json and /index.pb when deriving
# icon/APK paths. Since this file is named index.anymex.json, relative paths
# would resolve to ".../index.anymex.json/icon/<pkg>.png" (404) and installs
# would silently fail. Absolute URLs are ignored by Android clients.
RAW_BASE = os.environ.get(
    "ANYMEX_RAW_BASE",
    "https://raw.githubusercontent.com/Nahiro18/animek/repo",
).rstrip("/")

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

        pkg = entry.get("pkg", "")
        apk = entry.get("apk", "")
        if apk:
            entry["apkUrl"] = f"{RAW_BASE}/apk/{apk}"
        if pkg:
            entry["iconUrl"] = f"{RAW_BASE}/icon/{pkg}.png"

    with OUTPUT.open("w", encoding="utf-8") as f:
        json.dump(kept, f, ensure_ascii=False, separators=(",", ":"))

    print(f"Wrote {OUTPUT} with {len(kept)} of {len(index)} extensions")


if __name__ == "__main__":
    main()
