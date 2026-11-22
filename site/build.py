#!/usr/bin/env python3
"""Build the website: copy site/ and add data.json from the GitHub releases.

Usage: python3 site/build.py releases.json OUT_DIR
(releases.json = output of `gh api repos/OWNER/REPO/releases`). Run by
.github/workflows/site.yml; no dependencies beyond Python 3.
"""
import json
import os
import shutil
import sys
from datetime import datetime, timezone

SITE = os.path.dirname(os.path.abspath(__file__))


ABI_LABELS = {
    "arm64-v8a": "Most phones (64-bit)",
    "armeabi-v7a": "Older phones (32-bit)",
    "x86_64": "Chromebooks / x86",
}


def apks(rel):
    """APK downloads, best first (64-bit ARM fits nearly every phone)."""
    out = []
    for a in rel.get("assets", []):
        name = a.get("name", "")
        if not name.endswith(".apk"):
            continue
        abi = next((k for k in ABI_LABELS if name.endswith(f"-{k}.apk")), None)
        out.append({
            "url": a["browser_download_url"], "size": a.get("size", 0),
            "abi": abi, "label": ABI_LABELS.get(abi, "APK"),
        })
    order = list(ABI_LABELS)
    out.sort(key=lambda x: order.index(x["abi"]) if x["abi"] in order else len(order))
    return out


def main(releases_path, out):
    with open(releases_path, encoding="utf-8") as f:
        raw = json.load(f)
    releases = []
    for r in raw:
        if r.get("draft"):
            continue
        releases.append({
            "tag": r.get("tag_name", ""),
            "name": r.get("name") or r.get("tag_name", ""),
            "date": (r.get("published_at") or r.get("created_at") or "")[:10],
            "notes": r.get("body") or "",
            "url": r.get("html_url", ""),
            "prerelease": bool(r.get("prerelease")),
            "apks": apks(r),
        })
    latest = next((r for r in releases if not r["prerelease"]), releases[0] if releases else None)
    data = {
        "generated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "repo": os.environ.get("GITHUB_REPOSITORY", "nabin47/uptime-loom-android"),
        "playUrl": os.environ.get("PLAY_STORE_URL", "").strip(),
        "latest": latest,
        "releases": releases[:15],
    }
    if os.path.exists(out):
        shutil.rmtree(out)
    shutil.copytree(SITE, out, ignore=shutil.ignore_patterns("build.py", "__pycache__"))
    with open(os.path.join(out, "data.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)
    print(f"Built {out} with {len(releases)} release(s); latest = {latest['tag'] if latest else 'none'}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
