#!/usr/bin/env python3
"""Push large UTF-8 files via GitHub Git Database API (blobs → tree → commit → ref).
Avoids Contents API UTF-8/size issues. Needs GH_TOKEN or GITHUB_TOKEN (repo scope).

Usage (after apply-all.sh so local sources are patched):
  export GH_TOKEN=ghp_...
  python3 scripts/git-database-push-caravan.py
"""
from __future__ import annotations
import base64, json, os, sys, urllib.request, urllib.error
from pathlib import Path

OWNER, REPO, BRANCH = "mvr347", "LoveShop", "feature/caravan-commission-rework"
FILES = [
    "src/main/java/dev/lovelace/loveshops/managers/LostCaravanManager.java",
    "src/main/java/dev/lovelace/loveshops/commands/LoveShopsAdminCommand.java",
    "src/main/java/dev/lovelace/loveshops/textures/HeadTextures.java",
]
MESSAGE = "feat(caravan): deposit gate, empty leave, force start, heads (git-database-api)"

def api(method: str, path: str, body: dict | None = None) -> dict:
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        sys.exit("Set GH_TOKEN or GITHUB_TOKEN")
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        f"https://api.github.com/repos/{OWNER}/{REPO}{path}",
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
            "User-Agent": "loveshop-git-database-push",
        },
    )
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        err = e.read().decode()
        sys.exit(f"HTTP {e.code} {path}: {err}")

def main() -> None:
    root = Path(__file__).resolve().parents[1]
    ref = api("GET", f"/git/ref/heads/{BRANCH}")
    base_sha = ref["object"]["sha"]
    commit = api("GET", f"/git/commits/{base_sha}")
    base_tree = commit["tree"]["sha"]

    tree_items = []
    for rel in FILES:
        content = (root / rel).read_bytes()
        blob = api("POST", "/git/blobs", {
            "content": base64.b64encode(content).decode("ascii"),
            "encoding": "base64",
        })
        print(f"blob {rel}: {blob['sha']} ({len(content)} bytes)")
        tree_items.append({
            "path": rel,
            "mode": "100644",
            "type": "blob",
            "sha": blob["sha"],
        })

    tree = api("POST", "/git/trees", {
        "base_tree": base_tree,
        "tree": tree_items,
    })
    print("tree", tree["sha"])

    new_commit = api("POST", "/git/commits", {
        "message": MESSAGE,
        "tree": tree["sha"],
        "parents": [base_sha],
    })
    print("commit", new_commit["sha"])

    api("PATCH", f"/git/refs/heads/{BRANCH}", {
        "sha": new_commit["sha"],
        "force": False,
    })
    print(f"updated refs/heads/{BRANCH} → {new_commit['sha']}")

if __name__ == "__main__":
    main()
