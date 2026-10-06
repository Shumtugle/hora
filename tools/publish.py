#!/usr/bin/env python3
"""Publishes what Hora fetches as files of one GitHub release, and writes the list.

Reads tools/sources.json. For each model: downloads it from its origin (resuming),
checks the sum, cuts it into parts below the host's per-file cap when needed.
For each pack: takes the local file. Then writes packs.json, where every item
lists the release first and its origin, if any, second.

    python3 tools/publish.py              # prepare everything, upload nothing
    python3 tools/publish.py --upload     # also upload what the release lacks (needs the gh tool, logged in)
    python3 tools/publish.py --upload --force   # upload everything again

Work files go to ./publish-work (several GB with the model)."""
import hashlib, json, os, subprocess, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.abspath("publish-work")
# Each release file must stay under 2 GiB; HORA_PART_MB makes parts smaller for a dry test.
PART = int(os.environ.get("HORA_PART_MB", "1900")) * 1024 * 1024


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fetch(url, path, size=0):
    """Downloads with resume: a cut-off run continues where it stopped."""
    have = os.path.getsize(path) if os.path.exists(path) else 0
    if size and have == size:
        return
    req = urllib.request.Request(url, headers={"Range": "bytes=%d-" % have} if have else {})
    with urllib.request.urlopen(req) as r, open(path, "ab" if have and r.status == 206 else "wb") as out:
        total = int(r.headers.get("Content-Length", 0)) + (have if r.status == 206 else 0)
        got = have if r.status == 206 else 0
        while True:
            block = r.read(1 << 20)
            if not block:
                break
            out.write(block)
            got += len(block)
            if total:
                print("\r  %s: %d%%" % (os.path.basename(path), got * 100 // total), end="", flush=True)
    print()


def cut(path):
    """Cuts a file into numbered parts; returns their paths. A file below the cap stays whole."""
    if os.path.getsize(path) < PART:
        return [path]
    parts = []
    with open(path, "rb") as f:
        n = 1
        while True:
            chunk_path = "%s.part%d" % (path, n)
            left = PART
            with open(chunk_path, "wb") as out:
                while left:
                    block = f.read(min(left, 1 << 20))
                    if not block:
                        break
                    out.write(block)
                    left -= len(block)
            if os.path.getsize(chunk_path) == 0:
                os.remove(chunk_path)
                break
            parts.append(chunk_path)
            n += 1
    return parts


def main():
    upload = "--upload" in sys.argv
    src = json.load(open(os.path.join(HERE, "sources.json")))
    repo, tag = src["repo"], src["tag"]
    base = "https://github.com/%s/releases/download/%s/" % (repo, tag)
    os.makedirs(WORK, exist_ok=True)
    files, out = [], {"packs": [], "models": []}

    def item(entry, path, origin):
        parts = cut(path)
        mine = ({"url": base + os.path.basename(path)} if parts == [path] else
                {"parts": [{"url": base + os.path.basename(p), "sha256": sha256(p)} for p in parts]})
        files.extend(parts)
        entry["sources"] = [mine] + ([{"url": origin}] if origin else [])
        return entry

    for m in src.get("models", []):
        path = os.path.join(WORK, m["name"])
        print("model", m["name"])
        fetch(m["origin"], path, m.get("bytes", 0))
        if sha256(path) != m["sha256"]:
            sys.exit("  sum differs for " + m["name"] + ": delete the file and run again")
        e = {"slot": m["slot"], "name": m["name"], "license": m.get("license", ""),
             "bytes": os.path.getsize(path), "sha256": m["sha256"]}
        out["models"].append(item(e, path, m["origin"]))
    for p in src.get("packs", []):
        path = os.path.abspath(p["file"])
        print("pack", p["id"], path)
        e = {"id": p["id"], "name": os.path.basename(path), "bytes": os.path.getsize(path), "sha256": sha256(path)}
        out["packs"].append(item(e, path, ""))
    for lic in src.get("licenses", []):
        path = os.path.join(WORK, lic["name"])
        if not os.path.exists(path):
            fetch(lic["origin"], path)
        files.append(path)

    listing = os.path.join(WORK, "packs.json")
    json.dump(out, open(listing, "w"), indent=2)
    files.append(listing)
    print("\nlist written:", listing)
    print("release files:")
    for f in files:
        print("  %8.1f MB  %s" % (os.path.getsize(f) / 1e6, os.path.basename(f)))
    if not upload:
        print("\nnothing uploaded; run again with --upload")
        return
    if subprocess.run(["gh", "release", "view", tag, "--repo", repo], capture_output=True).returncode != 0:
        subprocess.run(["gh", "release", "create", tag, "--repo", repo, "--title", "Downloads",
                        "--notes", "Files the app downloads on request. Each keeps its own licence."], check=True)
    # Files already in the release with the same name and size are skipped; the list always goes up.
    have = {}
    view = subprocess.run(["gh", "release", "view", tag, "--repo", repo, "--json", "assets"],
                          capture_output=True, text=True, check=True)
    for a in json.loads(view.stdout).get("assets", []):
        have[a["name"]] = a["size"]
    todo = [f for f in files if "--force" in sys.argv or os.path.basename(f) == "packs.json"
            or have.get(os.path.basename(f)) != os.path.getsize(f)]
    for f in todo:
        print("uploading", os.path.basename(f))
        subprocess.run(["gh", "release", "upload", tag, "--repo", repo, "--clobber", f], check=True)
    unused = sorted(set(have) - {os.path.basename(f) for f in files})
    if unused:
        print("in the release but not in the list (safe to delete):", ", ".join(unused))
    print("uploaded")


if __name__ == "__main__":
    main()
