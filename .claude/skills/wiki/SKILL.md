---
name: wiki
description: Keep the 0xClient wiki (wiki/index.html, generated from javadoc, C++ comments, offsets and every Markdown document) complete and current. Use after ANY change to Java sources, C++ headers, offsets, docs, skills or agents, and whenever someone asks where something is documented.
---

# The 0xClient wiki

`wiki/index.html` is a single self-contained page that documents everything in this repository:
every Java type with its javadoc, fields and methods; every native; every offset with its
derivation note; every C++ file; every Markdown document; every skill and agent. It is generated,
never hand-edited, and it is committed, so a reader can open it from a clone without building.

Open it locally with any browser:

```bash
python tools/wiki/build_wiki.py && start wiki/index.html
```

## The rule

**Every change that touches the API, the natives, the offsets, the docs or the automation ends
with a wiki rebuild in the same commit.** The wiki must never describe a repository that no longer
exists. `python tools/wiki/build_wiki.py --check` exits 1 when the committed wiki is stale; run it
before you say a task is done.

## What "documented" means here

The wiki can only show what the sources say, so documentation lives in the sources:

1. **Java**: every public or protected type, constructor, method and field carries a javadoc
   comment. The first sentence is the summary the package page shows; write it so it stands alone.
   Use `{@code ...}` for identifiers, `{@link Type}` or `{@link Type#member}` for cross-references
   (they resolve to wiki links automatically), `@param`/`@return`/`@throws` where they add
   information the signature does not already give.
2. **Natives**: each `native` method in `oxclient.Natives` has a javadoc that says what it reads,
   which offsets it depends on, and what it returns when the game is not ready.
3. **Offsets**: every variable in `client/offsets.hpp` keeps its "HOW FOUND" comment, with the build
   it was measured on and whether it was verified live. The wiki renders that comment verbatim and
   derives the status badge (verified / not verified / suspect / refuted) from it.
4. **C++**: a file starts with a comment that says what it owns. Functions get a `//` comment
   immediately above them when their name does not say it all.
5. **Markdown**: `README.md`, `docs/*.md`, `tools/*.md`, skills and agents are rendered as pages.
   Headings become the page's table of contents; relative links to files in the repository resolve
   to the matching wiki page.

## Adding a new kind of thing

If you add a directory of sources the generator does not know (a new language, a new docs folder),
teach `tools/wiki/build_wiki.py` about it in the same change: add the path to `JAVA_ROOTS`,
`CPP_DIRS`, `DOC_FILES` or `DOC_DIRS`, or add a parser. The wiki is only complete if it is
generated from everything.

## When you finish a task

```bash
python tools/wiki/build_wiki.py
git add wiki/index.html
```

and mention in the commit body that the wiki was rebuilt. Nothing else to remember.
