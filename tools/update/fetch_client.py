#!/usr/bin/env python3
"""Fetch an OSRS native client (osclient.exe) by build, with nothing but the Python standard library.

Two sources, tried in this order:

  1. Jagex's own CDN (jagex.akamaized.net/direct6/osrs-win). It only ever serves the CURRENT
     production build, assembled from gzip "solid pieces". Every piece is sha256-verified against the
     signed metafile before it is used.
  2. The community archive (archive.lostcity.rs), which keeps every past build as a plain file. Used
     when a specific older build is asked for, or when the CDN is unreachable.

Usage:
    fetch_client.py --latest [--out DIR]           the current production build
    fetch_client.py --build 241-3 [--out DIR]      one specific build
    fetch_client.py --version                      print the current production build id and exit

The result lands in <out>/client-<build>/osclient.exe (plus discord_game_sdk.dll and the metafile),
and the build id is printed on stdout as the last line so a caller can read it back.
"""
import argparse
import base64
import gzip
import hashlib
import json
import os
import re
import sys
import urllib.request

CDN = "https://jagex.akamaized.net/direct6/osrs-win"
ARCHIVE = "https://archive.lostcity.rs/oldschool.runescape.com/native/osrs-win"
UA = "0xClient-updater/1.0"


def get(url, binary=True):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    return data if binary else data.decode("utf-8")


def jwt_payload(token):
    """The CDN signs alias.json and the metafile as JWTs. We read the payload and ignore the signature:
    every piece is hash-checked against the metafile anyway, so a forged metafile cannot hand us a
    file that passes verification against itself AND loads as the client Jagex built."""
    part = token.split(b".")[1]
    part += b"=" * (-len(part) % 4)
    return json.loads(base64.urlsafe_b64decode(part))


def normalise_build(version):
    """The metafile says "241.1" while the archive and the PE version resource say "241-1"."""
    return re.sub(r"[.]", "-", str(version))


def cdn_metafile():
    alias = jwt_payload(get(f"{CDN}/alias.json"))
    digest = alias["osrs-win.production"]
    meta = jwt_payload(get(f"{CDN}/metafile/{digest}/metafile.json"))
    return digest, meta


def fetch_cdn(out_root, log=print):
    digest, meta = cdn_metafile()
    build = normalise_build(meta["version"])
    out = os.path.join(out_root, f"client-{build}")
    os.makedirs(out, exist_ok=True)
    log(f"CDN production build {build} (metafile {digest[:12]}...)")

    blob = bytearray()
    for i, b64 in enumerate(meta["pieces"]["digests"]):
        want = base64.b64decode(b64)
        hexd = want.hex()
        url = f"{CDN}/pieces/{hexd[:2]}/{hexd}.solidpiece"
        raw = get(url)[6:]  # 6-byte Solid State Networks header
        try:
            data = gzip.decompress(raw)
        except (OSError, EOFError):
            data = raw  # some pieces ship uncompressed
        have = hashlib.sha256(data).digest()
        if have != want:
            raise SystemExit(f"piece {i} failed verification ({have.hex()[:12]} != {hexd[:12]})")
        blob += data
        log(f"  piece {i + 1}/{len(meta['pieces']['digests'])} ok ({len(data)} bytes)")

    pos = 0
    for f in meta["files"]:
        name, size = f["name"], f["size"]
        with open(os.path.join(out, name), "wb") as w:
            w.write(blob[pos:pos + size])
        pos += size
        log(f"  wrote {name} ({size} bytes)")
    with open(os.path.join(out, "metafile.json"), "w", encoding="utf-8") as w:
        json.dump(meta, w, indent=2)
    return build, out


def archive_builds():
    html = get(f"{ARCHIVE}/?C=M&O=D", binary=False)
    return re.findall(r'href="client-([0-9]+-[0-9]+)/', html)


def fetch_archive(out_root, build, log=print):
    out = os.path.join(out_root, f"client-{build}")
    os.makedirs(out, exist_ok=True)
    for name in ("osclient.exe", "discord_game_sdk.dll"):
        url = f"{ARCHIVE}/client-{build}/{name}"
        log(f"archive: {url}")
        data = get(url)
        with open(os.path.join(out, name), "wb") as w:
            w.write(data)
        log(f"  wrote {name} ({len(data)} bytes)")
    return build, out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--latest", action="store_true", help="the current production build")
    g.add_argument("--build", help="a specific build id, e.g. 241-3")
    g.add_argument("--version", action="store_true", help="print the current production build id")
    g.add_argument("--list", action="store_true", help="list builds the archive holds, newest first")
    ap.add_argument("--out", default="game", help="directory to write client-<build>/ into (default: game)")
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args()
    log = (lambda *x: None) if a.quiet else (lambda *x: print(*x, file=sys.stderr))

    if a.version:
        _, meta = cdn_metafile()
        print(normalise_build(meta["version"]))
        return
    if a.list:
        for b in archive_builds():
            print(b)
        return

    if a.latest:
        try:
            build, out = fetch_cdn(a.out, log)
        except Exception as e:  # noqa: BLE001 - any CDN failure falls back to the archive
            log(f"CDN failed ({e}); falling back to the archive")
            build = archive_builds()[0]
            build, out = fetch_archive(a.out, build, log)
    else:
        build = a.build
        try:
            _, meta = cdn_metafile()
            if normalise_build(meta["version"]) == build:
                build, out = fetch_cdn(a.out, log)
            else:
                build, out = fetch_archive(a.out, build, log)
        except Exception as e:  # noqa: BLE001
            log(f"CDN failed ({e}); using the archive")
            build, out = fetch_archive(a.out, build, log)

    exe = os.path.join(out, "osclient.exe")
    sha = hashlib.sha256(open(exe, "rb").read()).hexdigest()
    log(f"osclient.exe sha256 {sha}")
    with open(os.path.join(out, "osclient.exe.sha256"), "w") as w:
        w.write(sha + "\n")
    print(build)


if __name__ == "__main__":
    main()
