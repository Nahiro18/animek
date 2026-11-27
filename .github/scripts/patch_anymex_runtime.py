"""Patch AnymeX desktop sidecar to support Aniyomi's streaming video API.

AnymeX 3.2.1 calls `aniyomiGetVideoListStream` on the sidecar, but upstream's
DesktopExtensionLoader only implements `getVideoList`. This adds the missing
handler by reusing fetchVideoList and emitting each video as a partial stream
response, followed by "completed".

Usage: python3 patch_anymex_runtime.py <bridge-checkout-dir>
"""

import sys
from pathlib import Path

OLD = '''            "getVideoList" -> {
                val sourceId = getSafeString("sourceId")
                val episode = methodArgs.getAsJsonObject("episode") ?: JsonObject()
                val url = episode.get("url")?.let { if (it.isJsonPrimitive) it.asString else "" } ?: ""
                val name = episode.get("name")?.let { if (it.isJsonPrimitive) it.asString else "" } ?: ""
                AniyomiSourceMethods.fetchVideoList(sourceId, url, name)
            }
            "getPageList" -> {'''

NEW = '''            "getVideoList" -> {
                val sourceId = getSafeString("sourceId")
                val episode = methodArgs.getAsJsonObject("episode") ?: JsonObject()
                val url = episode.get("url")?.let { if (it.isJsonPrimitive) it.asString else "" } ?: ""
                val name = episode.get("name")?.let { if (it.isJsonPrimitive) it.asString else "" } ?: ""
                AniyomiSourceMethods.fetchVideoList(sourceId, url, name)
            }
            "aniyomiGetVideoListStream" -> {
                // AnymeX 3.2.1 switched Aniyomi servers to a stream API. The Dart
                // side calls this method, but upstream never implemented it in
                // the desktop sidecar. Emit each resolved video as a partial
                // response and close with "completed" so Video.fromJson works.
                val sourceId = getSafeString("sourceId")
                val episode = methodArgs.getAsJsonObject("episode") ?: JsonObject()
                val url = episode.get("url")?.let { if (it.isJsonPrimitive) it.asString else "" } ?: ""
                val name = episode.get("name")?.let { if (it.isJsonPrimitive) it.asString else "" } ?: ""
                val result = AniyomiSourceMethods.fetchVideoList(sourceId, url, name)
                val videos = try {
                    gson.fromJson(result, JsonArray::class.java) ?: JsonArray()
                } catch (e: Exception) {
                    JsonArray()
                }
                videos.forEach { onPartialResponse?.invoke(it) }
                "completed"
            }
            "getPageList" -> {'''


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_anymex_runtime.py <bridge-checkout-dir>")

    target = (
        Path(sys.argv[1])
        / "RuntimeBridges"
        / "Desktop"
        / "src"
        / "main"
        / "kotlin"
        / "com"
        / "anymex"
        / "desktop"
        / "DesktopExtensionLoader.kt"
    )

    text = target.read_text(encoding="utf-8")
    if "aniyomiGetVideoListStream" in text:
        print("Already patched: aniyomiGetVideoListStream present")
        return
    if OLD not in text:
        raise SystemExit(f"Could not find target block in {target}; upstream changed")

    target.write_text(text.replace(OLD, NEW, 1), encoding="utf-8")
    print(f"Patched {target}")


if __name__ == "__main__":
    main()
