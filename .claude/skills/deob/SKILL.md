---
name: deob
description: Reverse-engineer the OSRS client to find or re-derive an offset, function RVA, or struct field for KewlKlient. Use when an offset broke after a game update, or when adding a feature that needs data the client does not expose yet.
---

# Finding things in the game binary

You are helping someone re-derive an offset in a 16 MB stripped x64 binary that gets rebuilt weekly.
This skill is the method that works, and — just as importantly — the ways of working that look
productive and are not.

## The rule that matters most

**Anchor on something the client names itself, then walk to the thing you want.**

The client ships a Lua binding layer that registers its own functions by name. Those names are plain
text in the binary: `worldToScreenCoord`, `npcCoord`, `playerCoord`, `npcName`, `getNpcObj`. Find the
string, find what references it, and you are standing inside the registration code for exactly the
function you want.

Other good anchors, in rough order of how much they are worth:

1. **A name string the client uses for itself** (above). Best, because the client is describing itself.
2. **A distinctive constant.** A function that references both `0x2B0` and `0x3FF` is one function out
   of 218 that mention `0x2B0` alone. Intersecting two weak signals gives one strong one.
3. **Call-graph position.** "The only caller of X", "the function that calls both Y and Z".
4. **Size and shape.** A 184-byte function ending in `shr; and; ret` is an accessor.

## What not to do

**Do not scan for byte patterns.** A byte pattern is a guess about instructions the compiler may
rearrange on the next build. When it breaks it does not fail — it hands you an address that decompiles
into something plausible and wrong, and you lose a day. Blind scanning also cannot find anything hidden
behind a pointer, which is most of what you want.

**Do not trust a stale database.** IDA reuses the `.i64` beside the binary and will not warn you it is
from last month's build. Ghidra keeps its project. After any update, verify the binary hash first.

**Do not read a default as if it were live.** If the project has per-build overrides, the constant in
the header is the *oldest* value, not the running one. Check what actually applies before quoting a
number — this specific mistake has cost real days on projects like this.

## Getting the binary (no Windows needed)

The official client is fetched straight from Jagex's Akamai CDN — all plain GETs:

1. `https://jagex.akamaized.net/direct6/osrs-win/alias.json` — a **signed JWT**; decode the payload
   (ignore the signature), read `osrs-win.production` → a 64-hex digest naming the current release.
2. `https://jagex.akamaized.net/direct6/osrs-win/metafile/<digest>/metafile.json` — also a JWT; its
   payload has `files` (name + size), `pieces.digests` (base64 sha256s) and `version`
   (e.g. `client-240-6` — this is what goes in BUILD_ID).
3. `https://jagex.akamaized.net/direct6/osrs-win/pieces/<aa>/<digest>.solidpiece` per piece, where
   `aa` is the digest's first two hex chars. Each piece: strip the first **6 bytes** (Solid State
   Networks header), gunzip it (some pieces are plain — skip gunzip errors), and sha256-verify
   against the digest.
4. Concatenate the decompressed pieces **in metafile order** and split sequentially by each file's
   `size`. That yields `osclient.exe` (and whatever ships beside it).

This runs on Linux with nothing but Python. The launcher itself lives at `direct6/launcher-win/`
under the same scheme, but its alias.json is plain JSON rather than a JWT.

## The workflow

```bash
# 0. Fetch the current client (see above), record its sha256 — this is your BUILD_ID evidence.

# 1. First time, or after a game update: analyse the binary (slow once, cached after).
#    Ghidra headless runs natively on Linux; the PowerShell wrapper is a Windows convenience.
/opt/ghidra_*/support/analyzeHeadless /tmp/ghidra-proj osrs -import osclient.exe \
    -scriptPath tools/ghidra_scripts -postScript FindOffsets.java

# 2. Read the result
cat tools/offsets_found.txt
```

`FindOffsets.java` prints, for each binding name, the functions that reference it. That referencing
function is the **registration**, not the thing itself — open it and find the function pointer it
registers next to the name. That pointer is your target.

### The two registration shapes (sol2)

Reading the registration is where the real information is. The binding layer registers in two shapes,
and each hides the leaf differently:

1. **Label thunks** (ClientState usertype: getVarp, getVarbit, the stat getters). The registration
   calls `FUN_140101570(state, table, &LAB_1400f8b10)` — the third argument is the leaf, but it lives
   at a *jump-target label* Ghidra did not promote to a function, so it shows up as `LAB_...` inside
   the registration's own body. Disassemble the label's address range directly (`objdump -d
   --start-address=... --stop-address=...` works fine on the PE); the leaves are tiny — the whole
   getVarp leaf is three instructions ending in `ret`.
2. **Closures** (Graphics, inventory bindings). The registration stores a real function pointer into
   a closure struct beside the name: `local_638 = FUN_1401ab070` next to `FUN_14004d140(&x,
   "invGetObjId")`. That function is a **Lua trampoline** — it pulls arguments off the Lua stack and
   calls the actual implementation one hop deeper (`FUN_1401ab070` → `FUN_140032610`). Follow that
   last call; the trampoline itself tells you nothing about memory layout.

What the leaves read is the payoff. The getVarp leaf is `mov rax, [rip+X]; movsxd rcx, edx; mov eax,
[rax+rcx*4]` — that single line names the varp array's global. The inventory leaves name the whole
container table: bucket array, bucket count, node layout (id, item-id span, quantity span, next),
including the 4-bytes-per-entry item arrays. This is how struct fields should be derived: read them
off a function the client wrote, not guessed from a dump.

### Known dead ends on this binary

- **MSVC RTTI is not there.** There are no `.?AV...` type descriptors for the binding classes; the
  `ctti_get_type_name<T>` strings belong to functions with no inbound references Ghidra can see, so
  the "find the usertype registration via its ctti function" approach goes nowhere.
- **Names that look like bindings but are not.** `worldid` is a launch-argument parser ("jagex://v",
  "mem", "contentmode" surround it); `worldToScreenCoord` in the *Graphics* usertype is a drawing
  helper, though its closure body turned out to be the projection leaf we wanted. Check the strings
  around a hit before trusting what a name means.

Then put the RVA in `client/offsets.hpp` and **update `BUILD_ID` in the same commit**. Every number in
that file was measured on one build; mixing values from two builds is how you get a crash that looks
like a logic bug.

## Struct fields

Function RVAs move every build. Struct offsets move less, but they do move — an entity table moved by
`0x10` between builds a few weeks apart.

To find a field: find a function that *uses* it, and read what it does with it. If you want "the npc's
name", find the `npcName` binding, decompile the leaf, and read off the chain — it will be something
like `*(*(entity + 0x730) + 0x10)`. That is worth more than any amount of staring at a memory dump,
because it is the client telling you its own layout.

## Confidence, and saying so

Mark anything you have not confirmed against the running game as **NOT VERIFIED**, in the comment next
to it, with what would confirm it. A wrong offset fails *silently* — it reads a plausible number from
the wrong place. An admitted unknown is much cheaper than a confident wrong answer that reads as
evidence.

When two sources disagree, prefer them in this order:

1. What the running client was **observed doing** (a capture, a log line).
2. What the running client **reads right now** (a live memory read).
3. The game **cache** on disk.
4. **Anything written down elsewhere** — a wiki, a forum post, an older copy of this repo. It was
   measured on a different build, so treat those numbers as names and shapes, not as values.

## When you are done

Say plainly which of these you did: derived it from the binary, confirmed it against a running client,
or took it from something already written down. Those are three different levels of certainty and the
next person cannot tell them apart from the code alone.
