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


def asset(rel, ext):
    for a in rel.get("assets", []):
        if a.get("name", "").endswith(ext):
            return {"url": a["browser_download_url"], "size": a.get("size", 0)}
    return None


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
            "apk": asset(r, ".apk"),
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
