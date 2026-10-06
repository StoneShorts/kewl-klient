#!/usr/bin/env python3
"""The 0xClient updater: turn a new osclient.exe into offsets/client-<build>.json.

    python tools/update/update.py --latest          # whatever Jagex is shipping right now
    python tools/update/update.py --build 241-3     # one specific build (CDN if current, else archive)
    python tools/update/update.py --exe path\\to\\osclient.exe   # a file you already have

Steps, each of which can be run on its own with the flags below:

  1. fetch      tools/update/fetch_client.py downloads the build into game/client-<build>/
  2. analyse    Ghidra headless imports and analyses the binary (10-20 min the first time, cached
                after) and runs tools/ghidra_scripts/DeriveOffsets.java, which walks from the
                client's own Lua binding names to every number the DLL needs and writes
                build/ghidra-<build>/derived.json with the evidence for each.
  3. merge      the derived values are merged with the previous build's file: anything the script
                could not derive keeps the previous value, marked "carried" so nothing silently
                pretends to be measured; anything new is "derived"; anything the previous file
                marked "verified" that the script re-derived to the SAME value stays "verified".
  4. write      offsets/client-<build>.json, plus a diff against the previous build on stdout.

Ghidra is found from --ghidra, then $GHIDRA_HOME, then the newest ghidra_* folder under common
locations. Java 21+ must be on PATH or in $JAVA_HOME for Ghidra to start.
"""
import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HERE = os.path.dirname(os.path.abspath(__file__))
OFFSETS_DIR = os.path.join(ROOT, "offsets")
SCRIPTS = os.path.join(ROOT, "tools", "ghidra_scripts")


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def pe_file_version(path):
    """FileVersion from the PE version resource, read by hand so this runs anywhere."""
    data = open(path, "rb").read()
    # VS_VERSION_INFO blocks carry "FileVersion\0" as UTF-16LE followed by the value string.
    key = "FileVersion".encode("utf-16-le")
    for m in re.finditer(re.escape(key), data):
        i = m.end()
        # skip NUL terminator(s) and padding to the next 4-byte boundary
        while i < len(data) and data[i:i + 2] == b"\x00\x00":
            i += 2
        if i % 4:
            i += 4 - (i % 4)
        s = data[i:i + 64].decode("utf-16-le", errors="ignore")
        s = s.split("\x00")[0].strip()
        if re.fullmatch(r"\d+-\d+", s):
            return s
    return None


def find_ghidra(explicit):
    candidates = []
    if explicit:
        candidates.append(explicit)
    if os.environ.get("GHIDRA_HOME"):
        candidates.append(os.environ["GHIDRA_HOME"])
    for pat in ("C:/ghidra*", "C:/Program Files/ghidra*", os.path.expanduser("~/ghidra*"),
                os.path.expanduser("~/toolchains/ghidra*/ghidra_*"), os.path.expanduser("~/toolchains/ghidra*"),
                "/opt/ghidra*", "/usr/share/ghidra*", "/usr/local/ghidra*"):
        candidates.extend(sorted(glob.glob(pat), reverse=True))
    for c in candidates:
        for name in ("analyzeHeadless.bat", "analyzeHeadless"):
            p = os.path.join(c, "support", name)
            if os.path.exists(p):
                return p
    return None


def fetch(args):
    cmd = [sys.executable, os.path.join(HERE, "fetch_client.py"), "--out", os.path.join(ROOT, "game")]
    cmd += ["--latest"] if args.latest else ["--build", args.build]
    log("fetch:", " ".join(cmd[1:]))
    out = subprocess.run(cmd, capture_output=True, text=True, check=True)
    build = out.stdout.strip().splitlines()[-1]
    return build, os.path.join(ROOT, "game", f"client-{build}", "osclient.exe")


def analyse(exe, build, headless, project_root):
    proj = os.path.join(project_root, f"ghidra-{build}")
    os.makedirs(proj, exist_ok=True)
    derived = os.path.join(proj, "derived.json")
    if os.path.exists(derived):
        os.remove(derived)
    gpr = os.path.join(proj, "osrs.gpr")
    cmd = [headless, proj, "osrs"]
    # -import creates the project the first time; -process reuses the analysed program after that.
    cmd += ["-process", "osclient.exe"] if os.path.exists(gpr) else ["-import", exe]
    cmd += ["-scriptPath", SCRIPTS, "-postScript", "DeriveOffsets.java", derived,
            "-analysisTimeoutPerFile", "3600"]
    if os.path.exists(gpr):
        cmd += ["-noanalysis"]
    log("ghidra:", " ".join(cmd))
    with open(os.path.join(proj, "headless.log"), "w", encoding="utf-8") as lf:
        r = subprocess.run(cmd, stdout=lf, stderr=subprocess.STDOUT)
    if r.returncode != 0 or not os.path.exists(derived):
        raise SystemExit(f"Ghidra did not produce {derived}; see {os.path.join(proj, 'headless.log')}")
    return json.load(open(derived, encoding="utf-8"))


def previous_file(build):
    """The newest offsets file for a build older than `build` (by numeric order)."""
    def key(b):
        return tuple(int(x) for x in b.split("-"))
    files = []
    for f in glob.glob(os.path.join(OFFSETS_DIR, "client-*.json")):
        b = re.search(r"client-(\d+-\d+)\.json$", f)
        if b and key(b.group(1)) < key(build):
            files.append((key(b.group(1)), f))
    if not files:
        return None, None
    files.sort()
    f = files[-1][1]
    return f, json.load(open(f, encoding="utf-8"))


def table_keys():
    out = subprocess.run([sys.executable, os.path.join(HERE, "gen_offset_table.py"), "--json"],
                         capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


def header_notes():
    """Each offset's derivation note from client/offsets.hpp, and the status that note states.
    Used only when there is no previous build file: the compiled defaults are the first baseline,
    and their notes already say which were confirmed against a running game."""
    text = open(os.path.join(ROOT, "client", "offsets.hpp"), encoding="utf-8").read()
    notes, comment = {}, []
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("//"):
            comment.append(s[2:].strip())
            continue
        m = re.match(r"^inline\s+(?:std::uintptr_t|std::int32_t|int)\s+(\w+)\s*=\s*[^;]+;\s*(?://\s*(.*))?$", line)
        if m:
            note = " ".join(comment).strip()
            trail = (m.group(2) or "").strip()
            whole = (note + " " + trail).upper()
            live = "VERIFIED LIVE" in whole and "NOT VERIFIED" not in whole and "NOT RE-VERIFIED" not in whole and "NOT (RE-)VERIFIED" not in whole
            if "SUSPECT" in trail.upper() or "NOT DERIVED" in whole:
                status = "suspect"
            elif "REFUTED" in whole and not live:
                status = "refuted"
            elif live:
                status = "verified"
            else:
                status = "default"
            notes[m.group(1)] = (status, (trail or note)[:400])
            comment = []
        elif s:
            comment = []
    return notes


# Fields that are displacements on the client object. When a build inserts or removes bytes in that
# object, every field past the edit moves by the same amount. A carried field whose nearest DERIVED
# neighbours on both sides moved by the same delta is moved by that delta too, and labelled "shifted"
# rather than "carried" -- still not a measurement, but an inference with a stated basis.
CLIENT_OBJECT_FIELDS = {
    "VIEW_OBJ", "PENDING_ACTION_PACKED_ID", "PENDING_ACTION_INDEX", "PENDING_ACTION_TARGET",
    "PENDING_ACTION_SEQ", "PENDING_ACTION_PENDING", "GAME_STATE", "CYCLE", "SKILL_EFFECTIVE",
    "SKILL_BASE", "SKILL_XP", "RUN_ENERGY", "WORLD_MAP", "CAMERA_FINE_X", "CAMERA_FINE_H",
    "CAMERA_FINE_Y", "REGISTRY_MAP", "REGISTRY_GROUPS", "REGISTRY_GROUP_COUNT", "SCENE",
    "LOCAL_PLAYER_IDX", "REGISTRY_GROUP_SEL", "PLAYER_COUNT", "PLAYER_IDS", "IFACE_MANAGER",
}


def infer_shifts(offsets, prev_offsets):
    derived = sorted((p["value"], offsets[n]["value"] - p["value"]) for n, p in prev_offsets.items()
                     if n in CLIENT_OBJECT_FIELDS and n in offsets and offsets[n]["status"] in ("derived", "verified")
                     and isinstance(p.get("value"), int))
    for name, entry in offsets.items():
        if name not in CLIENT_OBJECT_FIELDS or entry["status"] != "carried":
            continue
        old = entry["value"]
        below = [(v, d) for v, d in derived if v < old]
        above = [(v, d) for v, d in derived if v > old]
        if not below or not above:
            continue
        lo, hi = below[-1], above[0]
        if lo[1] != hi[1]:
            continue
        if lo[1] == 0:
            entry["evidence"] = (f"carried; the nearest derived client-object fields on both sides "
                                 f"(0x{lo[0]:x}, 0x{hi[0]:x}) did not move, so this one most likely did not either. " + entry["evidence"])
            continue
        entry["previous"] = old
        entry["value"] = old + lo[1]
        entry["status"] = "shifted"
        entry["evidence"] = (f"not derived; the nearest derived client-object fields on both sides "
                             f"(0x{lo[0]:x} and 0x{hi[0]:x}) both moved by {lo[1]:+#x}, so this one was moved with them. "
                             f"Verify before trusting. " + entry["evidence"])


def merge(build, sha, derived, prev):
    keys = table_keys()
    prev_offsets = (prev or {}).get("offsets", {})
    offsets = {}
    for k in keys:
        name = k["name"]
        d = derived.get("offsets", {}).get(name)
        p = prev_offsets.get(name)
        if d and d.get("value") is not None:
            status = "derived"
            if p and p.get("value") == d["value"] and p.get("status") == "verified":
                status = "verified"
            offsets[name] = {"value": d["value"], "status": status, "evidence": d.get("evidence", "")}
            if p and p.get("value") != d["value"]:
                offsets[name]["previous"] = p.get("value")
        elif p and p.get("value") is not None:
            offsets[name] = {"value": p["value"], "status": "carried",
                             "evidence": f"not derived on {build}; carried from {prev.get('build')}: " + str(p.get("evidence", ""))}
        else:
            default = k["default"]
            try:
                val = int(default, 0)
            except ValueError:
                val = None
            offsets[name] = {"value": val, "status": "missing" if val is None else "default",
                             "evidence": "no derivation and no previous build; compiled default"}
    if not prev:
        # First baseline: the compiled defaults' own notes say what was confirmed against a running
        # game, what is suspect and what was refuted. Carry that knowledge into the file.
        for name, (status, note) in header_notes().items():
            e = offsets.get(name)
            if not e:
                continue
            if e["status"] == "default":
                e["status"] = status
                e["evidence"] = "compiled default (client/offsets.hpp): " + note
            elif e["status"] == "derived" and status == "verified":
                e["status"] = "verified"
                e["evidence"] += " | confirmed against a running game on this build (client/offsets.hpp)"
    infer_shifts(offsets, prev_offsets)
    return {
        "build": build,
        "sha256": sha,
        "derived": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": derived.get("source", "DeriveOffsets.java"),
        "notes": derived.get("notes", ""),
        "offsets": offsets,
    }


def diff(prev, cur):
    po = (prev or {}).get("offsets", {})
    rows = []
    for name, v in cur["offsets"].items():
        pv = po.get(name, {}).get("value")
        if pv != v["value"]:
            rows.append((name, pv, v["value"], v["status"]))
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--latest", action="store_true")
    src.add_argument("--build")
    src.add_argument("--exe")
    ap.add_argument("--ghidra", help="Ghidra install folder (the one with support/analyzeHeadless)")
    ap.add_argument("--projects", default=os.path.join(ROOT, "build"), help="where Ghidra projects live")
    ap.add_argument("--no-analyse", action="store_true", help="reuse build/ghidra-<build>/derived.json")
    ap.add_argument("--dry-run", action="store_true", help="print the file instead of writing it")
    a = ap.parse_args()

    if a.exe:
        exe = os.path.abspath(a.exe)
        build = pe_file_version(exe)
        if not build:
            raise SystemExit(f"{exe}: no FileVersion resource; pass a real osclient.exe")
    else:
        build, exe = fetch(a)
    sha = sha256(exe)
    log(f"build {build}, sha256 {sha}")

    derived_path = os.path.join(a.projects, f"ghidra-{build}", "derived.json")
    if a.no_analyse and os.path.exists(derived_path):
        derived = json.load(open(derived_path, encoding="utf-8"))
    else:
        headless = find_ghidra(a.ghidra)
        if not headless:
            raise SystemExit("Ghidra not found: pass --ghidra <folder> or set GHIDRA_HOME")
        derived = analyse(exe, build, headless, a.projects)
    if derived.get("sha256") and derived["sha256"] != sha:
        raise SystemExit(f"derived.json is for sha256 {derived['sha256']}, not this exe")

    prev_path, prev = previous_file(build)
    cur = merge(build, sha, derived, prev)
    changes = diff(prev, cur)
    counts = {}
    for v in cur["offsets"].values():
        counts[v["status"]] = counts.get(v["status"], 0) + 1
    log(f"status: {counts}")
    if prev:
        log(f"changes against {os.path.basename(prev_path)}: {len(changes)}")
        for name, pv, nv, st in changes:
            log(f"  {name:28s} {hex(pv) if isinstance(pv, int) else pv} -> {hex(nv) if isinstance(nv, int) else nv}  [{st}]")
    text = json.dumps(cur, indent=2)
    if a.dry_run:
        print(text)
        return
    os.makedirs(OFFSETS_DIR, exist_ok=True)
    out = os.path.join(OFFSETS_DIR, f"client-{build}.json")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text + "\n")
    log(f"wrote {os.path.relpath(out, ROOT)}")
    print(build)


if __name__ == "__main__":
    main()
