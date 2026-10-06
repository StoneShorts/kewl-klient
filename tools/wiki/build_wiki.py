#!/usr/bin/env python3
"""Build the 0xClient wiki: one self-contained HTML file covering everything in the repository.

    python tools/wiki/build_wiki.py            -> wiki/index.html
    python tools/wiki/build_wiki.py --check    -> exit 1 if wiki/index.html is out of date

What goes in, all generated from the sources so it cannot drift:

  * every Java type under java/ (0xClient's own packages, the RuneLite API shim, Shortest Path):
    its javadoc, fields, constructors, methods, nested types, and where it lives in the tree
  * the native surface: every `native` method in oxclient.Natives
  * every number in client/offsets.hpp, with the comment that says how it was found
  * every C++ header and source under client/ and launcher/: file comment, functions, structs
  * every Markdown document: README, docs/, tools/, THIRD_PARTY_NOTICES, PROGRESS
  * the agent skills and agents under .claude/
  * the per-build offset files under offsets/

Standard library only. Deliberately one file, so it can be opened from disk, searched with ctrl-F,
and committed whole.
"""
import argparse
import hashlib
import html
import json
import os
import re
import sys
from collections import OrderedDict
from datetime import date

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "wiki", "index.html")
JAVA_ROOTS = ["java", "java-test"]
CPP_DIRS = ["client", "launcher", "launcher/accounts", "tools"]
DOC_FILES = ["README.md", "THIRD_PARTY_NOTICES.md", "PROGRESS.md", "LICENSE"]
DOC_DIRS = ["docs", "tools", ".claude/skills", ".claude/agents"]


# ----------------------------------------------------------------------------------------------
# Markdown -> HTML (the subset these documents use)
# ----------------------------------------------------------------------------------------------
def md_inline(s, link_resolver=None):
    s = html.escape(s, quote=False)
    s = re.sub(r"`([^`]+)`", lambda m: "<code>" + m.group(1) + "</code>", s)
    s = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", s)
    s = re.sub(r"(?<![\w*])\*([^*\n]+)\*(?![\w*])", r"<i>\1</i>", s)
    s = re.sub(r"~~([^~]+)~~", r"<s>\1</s>", s)

    def link(m):
        text, href = m.group(1), m.group(2)
        if link_resolver:
            href = link_resolver(href)
        return f'<a href="{href}">{text}</a>'

    s = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", link, s)
    s = re.sub(r"(?<![\"'>=])\b(https?://[^\s<)]+)", r'<a href="\1">\1</a>', s)
    return s


def md_to_html(text, link_resolver=None):
    lines = text.splitlines()
    out, i, n = [], 0, len(lines)
    para = []
    toc = []

    def flush():
        if para:
            out.append("<p>" + md_inline(" ".join(para), link_resolver) + "</p>")
            para.clear()

    while i < n:
        line = lines[i]
        if line.startswith("```"):
            flush()
            lang = line[3:].strip()
            buf = []
            i += 1
            while i < n and not lines[i].startswith("```"):
                buf.append(lines[i])
                i += 1
            out.append(f'<pre class="code" data-lang="{html.escape(lang)}"><code>' + html.escape("\n".join(buf)) + "</code></pre>")
            i += 1
            continue
        m = re.match(r"^(#{1,6})\s+(.*)$", line)
        if m:
            flush()
            level = len(m.group(1))
            title = m.group(2).strip()
            anchor = slug(title)
            toc.append((level, title, anchor))
            out.append(f'<h{level + 1} id="{anchor}">{md_inline(title, link_resolver)}</h{level + 1}>')
            i += 1
            continue
        if re.match(r"^\s*(-{3,}|\*{3,})\s*$", line):
            flush()
            out.append("<hr>")
            i += 1
            continue
        if line.startswith(">"):
            flush()
            buf = []
            while i < n and lines[i].startswith(">"):
                buf.append(lines[i][1:].strip())
                i += 1
            out.append('<blockquote class="hatnote">' + md_to_html("\n".join(buf), link_resolver)[0] + "</blockquote>")
            continue
        if re.match(r"^\s*\|", line) and i + 1 < n and re.match(r"^\s*\|?\s*:?-{2,}", lines[i + 1]):
            flush()
            header = [c.strip() for c in line.strip().strip("|").split("|")]
            i += 2
            rows = []
            while i < n and re.match(r"^\s*\|", lines[i]):
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")])
                i += 1
            t = ['<table class="wikitable"><thead><tr>' + "".join(f"<th>{md_inline(h, link_resolver)}</th>" for h in header) + "</tr></thead><tbody>"]
            for r in rows:
                t.append("<tr>" + "".join(f"<td>{md_inline(c, link_resolver)}</td>" for c in r) + "</tr>")
            t.append("</tbody></table>")
            out.append("".join(t))
            continue
        m = re.match(r"^(\s*)([-*+]|\d+[.)])\s+(.*)$", line)
        if m:
            flush()
            ordered = m.group(2)[0].isdigit()
            tag = "ol" if ordered else "ul"
            items = []
            base_indent = len(m.group(1))
            while i < n:
                m2 = re.match(r"^(\s*)([-*+]|\d+[.)])\s+(.*)$", lines[i])
                if m2 and len(m2.group(1)) == base_indent:
                    items.append([m2.group(3)])
                    i += 1
                elif lines[i].strip() == "":
                    # blank line inside a list only ends it if the next non-blank is not indented
                    j = i + 1
                    while j < n and lines[j].strip() == "":
                        j += 1
                    if j < n and (len(lines[j]) - len(lines[j].lstrip())) > base_indent:
                        i = j
                        continue
                    break
                elif (len(lines[i]) - len(lines[i].lstrip())) > base_indent and items:
                    items[-1].append(lines[i])
                    i += 1
                else:
                    break
            li = []
            for it in items:
                first, rest = it[0], it[1:]
                body = md_inline(first, link_resolver)
                if rest:
                    sub = md_to_html("\n".join(l[min(len(l) - len(l.lstrip()), base_indent + 2):] for l in rest), link_resolver)[0]
                    body += sub
                li.append(f"<li>{body}</li>")
            out.append(f"<{tag}>" + "".join(li) + f"</{tag}>")
            continue
        if line.strip() == "":
            flush()
            i += 1
            continue
        if line.startswith("    ") and not para:
            flush()
            buf = []
            while i < n and (lines[i].startswith("    ") or lines[i].strip() == ""):
                buf.append(lines[i][4:])
                i += 1
            out.append('<pre class="code"><code>' + html.escape("\n".join(buf).rstrip()) + "</code></pre>")
            continue
        if line.lstrip().startswith("<") and not para:
            flush()
            out.append(line)
            i += 1
            continue
        para.append(line.strip())
        i += 1
    flush()
    return "\n".join(out), toc


def slug(s):
    s = re.sub(r"<[^>]+>", "", s)
    s = re.sub(r"[^\w\s.-]", "", s).strip().lower()
    return re.sub(r"[\s]+", "-", s) or "section"


# ----------------------------------------------------------------------------------------------
# Java parsing
# ----------------------------------------------------------------------------------------------
MODIFIERS = {"public", "protected", "private", "static", "final", "abstract", "native", "synchronized",
             "transient", "volatile", "strictfp", "default", "sealed", "non-sealed"}


class JavaType:
    def __init__(self, kind, name, package, path, line, doc, decl, outer=None):
        self.kind, self.name, self.package, self.path, self.line = kind, name, package, path, line
        self.doc, self.decl, self.outer = doc, decl, outer
        self.fields, self.ctors, self.methods, self.nested, self.enum_constants = [], [], [], [], []
        self.annotations = []

    @property
    def fqcn(self):
        return f"{self.package}.{self.qualified}" if self.package else self.qualified

    @property
    def qualified(self):
        return f"{self.outer.qualified}.{self.name}" if self.outer else self.name

    @property
    def page_id(self):
        return "java:" + self.fqcn


def strip_comments_keep_docs(src):
    """Return (code, docs) where code has comments blanked (same length, newlines kept) and docs is a
    list of (end_offset, javadoc_text) for every /** */ block."""
    out, docs = [], []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"' or c == "'":
            q = c
            j = i + 1
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                j = n if j < 0 else j + 3
            else:
                while j < n and src[j] != q:
                    if src[j] == "\\":
                        j += 1
                    j += 1
                j += 1
            lit = src[i:j]
            # keep the quotes, blank the content: a '{' or ';' inside a string must not split declarations
            out.append(lit[0] + re.sub(r"[^\n]", " ", lit[1:-1]) + lit[-1] if len(lit) >= 2 else lit)
            i = j
        elif src.startswith("/**", i) and not src.startswith("/**/", i):
            j = src.find("*/", i + 3)
            j = n if j < 0 else j + 2
            docs.append((j, src[i:j]))
            out.append(re.sub(r"[^\n]", " ", src[i:j]))
            i = j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r"[^\n]", " ", src[i:j]))
            i = j
        elif src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out), docs


def doc_before(docs, offset, code):
    """The javadoc whose end is followed only by whitespace/annotations before `offset`."""
    import bisect
    ends = [d[0] for d in docs]
    k = bisect.bisect_right(ends, offset) - 1
    if k < 0:
        return None
    end, text = docs[k]
    between = code[end:offset]
    if len(between) > 4000:
        return None
    if re.fullmatch(r"(\s|@\w+(\([^)]*\))?)*", between):
        return text
    return None


def javadoc_html(doc, resolver):
    if not doc:
        return "", {}
    body = doc.strip()
    body = re.sub(r"^/\*\*", "", body)
    body = re.sub(r"\*/$", "", body)
    lines = [re.sub(r"^\s*\*\s?", "", l) for l in body.splitlines()]
    text = "\n".join(lines).strip()
    tags = OrderedDict()
    main = []
    cur = None
    for l in text.splitlines():
        m = re.match(r"^@(\w+)\s*(.*)$", l)
        if m:
            cur = m.group(1)
            tags.setdefault(cur, []).append(m.group(2))
        elif cur:
            tags[cur][-1] += "\n" + l
        else:
            main.append(l)
    main = "\n".join(main)

    def code_tag(s):
        # {@code ...} with balanced braces inside (javadoc allows nested {} in code snippets)
        out, i = [], 0
        while True:
            k = s.find("{@code", i)
            if k < 0:
                out.append(s[i:])
                break
            out.append(s[i:k])
            j, d = k + 6, 1
            while j < len(s) and d:
                if s[j] == "{":
                    d += 1
                elif s[j] == "}":
                    d -= 1
                j += 1
            out.append("<code>" + html.escape(s[k + 6:j - 1].strip("\n").strip()) + "</code>")
            i = j
        return "".join(out)

    def inline(s):
        s = code_tag(s)
        s = re.sub(r"\{@literal\s+([^}]*)\}", lambda m: html.escape(m.group(1)), s)

        def link(m):
            target = m.group(1).strip()
            label = m.group(2) or target
            href = resolver(target)
            return f'<a href="{href}"><code>{html.escape(label)}</code></a>' if href else f"<code>{html.escape(label)}</code>"

        s = re.sub(r"\{@link(?:plain)?\s+([^}\s]+)\s*([^}]*)\}", link, s)
        s = re.sub(r"\{@inheritDoc\}", "<i>(inherited)</i>", s)
        return s

    paras = re.split(r"\n\s*\n", main)
    out = []
    for p in paras:
        p = p.strip()
        if not p:
            continue
        if p.startswith("<pre") or p.startswith("<ul") or p.startswith("<ol") or p.startswith("<table"):
            out.append(inline(p))
        elif p.startswith("<p>"):
            out.append(inline(p) + ("" if p.endswith("</p>") else "</p>"))
        else:
            out.append("<p>" + inline(p) + "</p>")
    rendered_tags = OrderedDict((k, [inline(v) for v in vs]) for k, vs in tags.items())
    return "\n".join(out), rendered_tags


def parse_java_file(path, rel):
    src = open(path, encoding="utf-8", errors="replace").read()
    code, docs = strip_comments_keep_docs(src)
    pkg = re.search(r"^\s*package\s+([\w.]+)\s*;", code, re.M)
    package = pkg.group(1) if pkg else ""
    types = []

    import bisect
    newlines = [m.start() for m in re.finditer("\n", code)]

    def line_of(off):
        return bisect.bisect_left(newlines, off) + 1

    def parse_body(start, end, outer):
        """Walk the member declarations between start..end (inside one pair of braces)."""
        i = start
        depth = 0
        stmt_start = start
        while i < end:
            c = code[i]
            if c == "{":
                if depth == 0:
                    decl = code[stmt_start:i].strip()
                    handle_decl(decl, stmt_start, i, outer, is_block=True)
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    stmt_start = i + 1
            elif c == ";" and depth == 0:
                decl = code[stmt_start:i].strip()
                handle_decl(decl, stmt_start, i, outer, is_block=False)
                stmt_start = i + 1
            elif c == "(" and depth == 0:
                # skip parenthesised content (annotation args, parameter lists) so ';' inside lambdas don't split
                j, d = i, 0
                while j < end:
                    if code[j] == "(":
                        d += 1
                    elif code[j] == ")":
                        d -= 1
                        if d == 0:
                            break
                    j += 1
                i = j
            i += 1

    def handle_decl(decl, s, e, outer, is_block):
        if not decl:
            return
        # s is where the previous declaration ended; the javadoc sits in the blanked gap before the
        # first real character, so look it up from there
        s = s + (len(code[s:e]) - len(code[s:e].lstrip()))
        decl_clean = re.sub(r"\s+", " ", decl)
        m = re.search(r"\b(class|interface|enum|record|@interface)\s+(\w+)", decl_clean)
        if m and is_block:
            kind, name = m.group(1), m.group(2)
            if kind == "@interface":
                kind = "annotation"
            t = JavaType(kind, name, package, rel, line_of(s), doc_before(docs, s, code), decl_clean, outer)
            t.annotations = re.findall(r"@\w+(?:\([^)]*\))?", decl_clean[:m.start()])
            if outer:
                outer.nested.append(t)
            types.append(t)
            # body
            body_start = e + 1
            body_end = matching_brace(e)
            if kind == "enum":
                parse_enum_body(body_start, body_end, t)
            else:
                parse_body(body_start, body_end, t)
            return
        if outer is None:
            return
        # method or constructor or field
        head = decl_clean
        if "=" in head and not re.search(r"\)\s*(throws\s+[\w.,\s]+)?$", head.split("=")[0]):
            head = head.split("=", 1)[0].strip()
        paren = head.find("(")
        if paren >= 0 and is_block or (paren >= 0 and ("native" in head.split("(")[0] or "abstract" in head.split("(")[0] or outer.kind in ("interface", "annotation"))):
            sig = head
            before = sig[:paren].strip()
            params = sig[paren:]
            parts = before.split()
            name = parts[-1] if parts else ""
            mods = [p for p in parts if p in MODIFIERS]
            ann = [p for p in parts if p.startswith("@")]
            rest = [p for p in parts if p not in MODIFIERS and not p.startswith("@")]
            if name == outer.name:
                outer.ctors.append({"name": name, "params": params, "mods": mods, "doc": doc_before(docs, s, code), "line": line_of(s), "ann": ann})
            else:
                ret = " ".join(rest[:-1]) if len(rest) > 1 else ""
                outer.methods.append({"name": name, "ret": ret, "params": params, "mods": mods, "doc": doc_before(docs, s, code), "line": line_of(s), "ann": ann})
            return
        if not is_block and paren < 0:
            parts = head.split()
            if len(parts) >= 2 and not head.startswith("import") and not head.startswith("package"):
                name = parts[-1]
                mods = [p for p in parts if p in MODIFIERS]
                ann = [p for p in parts if p.startswith("@")]
                typ = " ".join(p for p in parts[:-1] if p not in MODIFIERS and not p.startswith("@"))
                value = decl_clean.split("=", 1)[1].strip() if "=" in decl_clean else ""
                outer.fields.append({"name": name, "type": typ, "mods": mods, "doc": doc_before(docs, s, code), "line": line_of(s), "value": value, "ann": ann})

    def parse_enum_body(start, end, t):
        # constants up to the first ';' at depth 0, then normal members
        i, depth = start, 0
        while i < end:
            c = code[i]
            if c in "({":
                depth += 1
            elif c in ")}":
                depth -= 1
            elif c == ";" and depth == 0:
                break
            i += 1
        consts = code[start:i]
        for m in re.finditer(r"(?:^|,)\s*(?:@\w+\s*)*([A-Z_][A-Z0-9_]*)\s*(\([^)]*\))?", consts):
            t.enum_constants.append({"name": m.group(1), "args": m.group(2) or "", "doc": doc_before(docs, start + m.start(1), code)})
        parse_body(i + 1 if i < end else end, end, t)

    def matching_brace(open_idx):
        d = 0
        for j in range(open_idx, len(code)):
            if code[j] == "{":
                d += 1
            elif code[j] == "}":
                d -= 1
                if d == 0:
                    return j
        return len(code)

    parse_body(0, len(code), None)
    return types


# ----------------------------------------------------------------------------------------------
# C++ parsing (light: file comment, offsets, functions, structs)
# ----------------------------------------------------------------------------------------------
def parse_cpp(path):
    src = open(path, encoding="utf-8", errors="replace").read()
    lines = src.splitlines()
    head = []
    for l in lines:
        if l.startswith("//"):
            head.append(l[2:].strip())
        elif l.strip() == "" and head:
            head.append("")
        elif head:
            break
        elif l.strip():
            break
    functions, structs, offsets = [], [], []
    comment = []
    for idx, l in enumerate(lines):
        s = l.strip()
        if s.startswith("//"):
            comment.append(s[2:].strip() if not s.startswith("///") else s[3:].strip())
            continue
        m = re.match(r"^\s*(?:inline\s+)?constexpr\s+([\w:<>]+)\s+(\w+)\s*=\s*([^;]+);\s*(?://\s*(.*))?$", l)
        if not m:
            m = re.match(r"^\s*inline\s+constexpr\s+([\w:<>]+)\s+(\w+)\s*=\s*([^;]+);\s*(?://\s*(.*))?$", l)
        if m:
            offsets.append({"type": m.group(1), "name": m.group(2), "value": m.group(3).strip(), "trail": m.group(4) or "", "comment": "\n".join(comment).strip(), "line": idx + 1})
            comment = []
            continue
        m = re.match(r"^\s*(?:(?:inline|static|constexpr|extern|virtual|explicit)\s+)*([\w:<>*&\s,]+?)\s+\**(\w+)\s*\(([^;{]*)\)\s*(const)?\s*(?:noexcept)?\s*(?:->\s*[\w:<>*&]+)?\s*\{", l)
        if m and m.group(2) not in ("if", "for", "while", "switch", "catch", "return", "sizeof") and not l.strip().startswith("}"):
            functions.append({"ret": m.group(1).strip(), "name": m.group(2), "params": m.group(3).strip(), "comment": "\n".join(comment).strip(), "line": idx + 1})
            comment = []
            continue
        m = re.match(r"^\s*(struct|class|enum class|enum|namespace)\s+([\w:]+)", l)
        if m:
            structs.append({"kind": m.group(1), "name": m.group(2), "comment": "\n".join(comment).strip(), "line": idx + 1})
            comment = []
            continue
        if s:
            comment = []
    return {"head": "\n".join(head).strip(), "functions": functions, "structs": structs, "offsets": offsets, "lines": len(lines)}


# ----------------------------------------------------------------------------------------------
# Site assembly
# ----------------------------------------------------------------------------------------------
CSS = r"""
:root{--bg:#fff;--fg:#202122;--link:#36c;--visited:#795cb2;--border:#a2a9b1;--box:#f8f9fa;--head:#eaecf0;--accent:#40f650;--accent-dark:#1f8a2d;--mono:ui-monospace,Consolas,"Cascadia Mono",Menlo,monospace;--serif:"Linux Libertine","Georgia","Times",serif;--sans:system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif}
*{box-sizing:border-box}
html,body{margin:0;padding:0;background:var(--bg);color:var(--fg);font-family:var(--sans);font-size:14px;line-height:1.6}
a{color:var(--link);text-decoration:none}a:hover{text-decoration:underline}a:visited{color:var(--visited)}
#top{position:fixed;top:0;left:0;right:0;height:52px;background:#fff;border-bottom:1px solid var(--border);display:flex;align-items:center;gap:14px;padding:0 16px;z-index:10}
#top .logo{display:flex;align-items:center;gap:10px;font-family:var(--serif);font-size:20px;color:var(--fg)}
#top .logo b{color:var(--accent-dark)}
#top .logo .mark{width:34px;height:34px;border-radius:50%;background:#060806;border:1.5px solid var(--accent);display:grid;place-items:center;font:700 13px var(--mono);color:var(--accent)}
#top input{flex:1;max-width:520px;padding:7px 10px;border:1px solid var(--border);border-radius:2px;font-size:14px;font-family:var(--sans)}
#top .meta{margin-left:auto;color:#54595d;font-size:12px}
#side{position:fixed;top:52px;left:0;width:260px;bottom:0;overflow:auto;padding:12px 10px 40px 14px;border-right:1px solid #eaecf0;font-size:13px;background:#fff}
#side h3{font-size:12px;text-transform:uppercase;letter-spacing:.04em;color:#54595d;margin:14px 0 4px;font-weight:600}
#side ul{list-style:none;margin:0;padding:0}#side li{margin:0;padding:1px 0}
#side details summary{cursor:pointer;color:#202122;font-weight:600}
#side .pkg{padding-left:10px}
#main{margin-left:260px;padding:70px 32px 60px 32px;max-width:1100px}
article{display:none}article.active{display:block}
h1{font-family:var(--serif);font-weight:400;font-size:28.8px;border-bottom:1px solid var(--border);margin:0 0 6px;padding-bottom:4px;line-height:1.3}
h2{font-family:var(--serif);font-weight:400;font-size:22px;border-bottom:1px solid var(--border);margin:1.2em 0 .4em;padding-bottom:2px}
h3{font-size:16px;margin:1em 0 .3em}h4{font-size:14px;margin:.8em 0 .2em;font-weight:700}
.tagline{color:#54595d;font-size:12.5px;margin-bottom:14px}
.hatnote{font-style:italic;color:#54595d;padding:4px 0 4px 24px;margin:0 0 10px;border:0;border-left:0}
blockquote.hatnote{background:var(--box);border:1px solid #c8ccd1;padding:8px 12px;font-style:normal;color:var(--fg);margin:8px 0}
.toc{display:inline-block;background:var(--box);border:1px solid #a2a9b1;padding:8px 14px;margin:6px 0 14px;font-size:12.5px}
.toc .t{font-weight:700;text-align:center;margin-bottom:4px}.toc ol{margin:0;padding-left:18px}.toc ol ol{padding-left:16px}
table.wikitable,table.api{border-collapse:collapse;border:1px solid #a2a9b1;background:var(--box);margin:8px 0;font-size:13px;width:100%}
table.wikitable th,table.api th{background:var(--head);border:1px solid #a2a9b1;padding:4px 8px;text-align:left}
table.wikitable td,table.api td{border:1px solid #a2a9b1;padding:4px 8px;vertical-align:top}
table.api td.sig{font-family:var(--mono);font-size:12.5px;white-space:pre-wrap;width:46%}
.infobox{float:right;clear:right;width:300px;background:var(--box);border:1px solid #a2a9b1;padding:6px;margin:0 0 14px 18px;font-size:12.5px}
.infobox th{text-align:left;padding:3px 6px;vertical-align:top;white-space:nowrap;background:var(--head)}
.infobox td{padding:3px 6px;word-break:break-word}
.infobox .cap{text-align:center;font-weight:700;font-size:14px;background:var(--accent);color:#06100a;padding:6px}
code{font-family:var(--mono);font-size:12.5px;background:var(--box);border:1px solid #eaecf0;padding:0 3px;border-radius:2px}
pre.code{background:var(--box);border:1px solid #c8ccd1;padding:10px 12px;overflow:auto;font-family:var(--mono);font-size:12.5px;line-height:1.45}
pre.code code{border:0;padding:0;background:none}
.doc{margin:6px 0 10px}
.badge{display:inline-block;font-size:11px;padding:0 6px;border-radius:9px;background:var(--head);color:#202122;margin-left:6px;vertical-align:middle}
.badge.verified{background:#d5fdda;color:#0b4d16}.badge.suspect,.badge.carried{background:#fdeedc;color:#6b3a00}.badge.refuted,.badge.missing{background:#fde2e2;color:#7a0a0a}
.src{font-size:12px;color:#54595d}
.cats{border:1px solid #a2a9b1;background:var(--box);padding:4px 8px;margin-top:30px;font-size:12.5px}
.cats a{margin-right:10px}
#results{position:fixed;top:52px;left:276px;width:520px;max-height:70vh;overflow:auto;background:#fff;border:1px solid var(--border);box-shadow:0 2px 8px rgba(0,0,0,.15);display:none;z-index:20;font-size:13px}
#results div{padding:6px 10px;border-bottom:1px solid #eaecf0;cursor:pointer}#results div:hover{background:var(--box)}
#results .k{color:#54595d;font-size:11px;margin-left:6px}
.members li{margin:2px 0}
.kbd{font-family:var(--mono);font-size:12px;border:1px solid #a2a9b1;border-bottom-width:2px;padding:0 4px;border-radius:3px;background:#fff}
.mono{font-family:var(--mono)}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:10px;margin:12px 0}
.stat{border:1px solid #a2a9b1;background:var(--box);padding:8px 12px}.stat b{display:block;font-size:22px;font-family:var(--serif);font-weight:400}
.sig{font-family:var(--mono)}
@media (max-width:900px){#side{display:none}#main{margin-left:0;padding:66px 16px 40px}.infobox{float:none;width:auto;margin:0 0 14px}}
@media print{#top,#side{display:none}#main{margin:0}article{display:block;page-break-before:always}}
"""

JS = r"""
(function(){
  var index = window.__WIKI_INDEX__;
  function show(id){
    var cur = document.querySelector('article.active'); if (cur) cur.classList.remove('active');
    var a = document.getElementById('page:' + id) || document.getElementById('page:Main Page');
    a.classList.add('active'); window.scrollTo(0,0);
    document.title = (a.dataset.title || id) + ' - 0xClient Wiki';
  }
  function route(){
    var h = decodeURIComponent(location.hash.replace(/^#/, ''));
    var sec = null;
    var i = h.indexOf('#'); if (i >= 0){ sec = h.slice(i+1); h = h.slice(0,i); }
    show(h || 'Main Page');
    if (sec){ var el = document.querySelector('article.active #' + CSS.escape(sec)); if (el) el.scrollIntoView(); }
  }
  window.addEventListener('hashchange', route); route();
  var box = document.getElementById('q'), res = document.getElementById('results');
  function search(){
    var q = box.value.trim().toLowerCase(); res.innerHTML = '';
    if (!q){ res.style.display = 'none'; return; }
    var hits = [];
    for (var k = 0; k < index.length && hits.length < 60; k++){
      var e = index[k], t = e[0].toLowerCase(), s = e[2].toLowerCase();
      var score = t === q ? 0 : t.startsWith(q) ? 1 : t.indexOf(q) >= 0 ? 2 : s.indexOf(q) >= 0 ? 3 : -1;
      if (score >= 0) hits.push([score, e]);
    }
    hits.sort(function(a,b){ return a[0]-b[0] || a[1][0].length - b[1][0].length; });
    hits.forEach(function(h){
      var d = document.createElement('div');
      d.innerHTML = '<b>' + h[1][0].replace(/</g,'&lt;') + '</b><span class="k">' + h[1][1] + '</span>';
      d.onclick = function(){ location.hash = '#' + encodeURIComponent(h[1][3]); res.style.display='none'; box.value=''; };
      res.appendChild(d);
    });
    res.style.display = hits.length ? 'block' : 'none';
  }
  box.addEventListener('input', search);
  box.addEventListener('keydown', function(e){ if (e.key === 'Enter'){ var f = res.querySelector('div'); if (f) f.onclick(); } if (e.key === 'Escape'){ res.style.display='none'; } });
  document.addEventListener('click', function(e){ if (!res.contains(e.target) && e.target !== box) res.style.display = 'none'; });
  document.addEventListener('keydown', function(e){ if (e.key === '/' && document.activeElement !== box){ e.preventDefault(); box.focus(); } });
})();
"""


class Wiki:
    def __init__(self):
        self.pages = OrderedDict()   # id -> dict(title, html, kind, summary)
        self.index = []              # [title, kind, summary, id]
        self.java_types = []
        self.by_simple = {}
        self.by_fqcn = {}

    def add(self, pid, title, body, kind, summary="", cats=()):
        cat_html = ""
        if cats:
            cat_html = '<div class="cats">Categories: ' + " ".join(f'<a href="#{self.href("cat:" + c)}">{html.escape(c)}</a>' for c in cats) + "</div>"
        self.pages[pid] = {"title": title, "html": body + cat_html, "kind": kind, "cats": list(cats)}
        self.index.append([title, kind, (summary or "")[:200], pid])

    @staticmethod
    def href(pid):
        return html.escape(pid.replace("%", "%25").replace("#", "%23"), quote=True)

    def link(self, pid, text=None):
        return f'<a href="#{self.href(pid)}">{html.escape(text or self.pages.get(pid, {}).get("title", pid))}</a>'

    def resolve_java(self, target):
        target = target.split("(")[0].split("#")[0].strip()
        if not target:
            return None
        t = self.by_fqcn.get(target) or self.by_simple.get(target.split(".")[-1])
        return "#" + self.href(t.page_id) if t else None

    def resolve_doc_link(self, href):
        if href.startswith("#"):
            return "#" + self.href(self._doc_ctx) + "%23" + href[1:]
        if re.match(r"^https?://", href):
            return href
        clean = href.split("#")[0].strip("./")
        frag = href.split("#")[1] if "#" in href else ""
        pid = None
        if clean in self.doc_ids:
            pid = self.doc_ids[clean]
        elif clean.endswith(".java"):
            for t in self.java_types:
                if t.path.endswith(clean) and t.outer is None:
                    pid = t.page_id
                    break
        elif clean.endswith((".hpp", ".cpp", ".h")):
            pid = "cpp:" + clean
        if pid and pid in self.pages or pid and pid.startswith("java:"):
            return "#" + self.href(pid) + ("%23" + frag if frag else "")
        return "https://github.com/StoneShorts/0xClient/blob/main/" + clean + ("#" + frag if frag else "")


def toc_html(toc):
    if len(toc) < 3:
        return ""
    out = ['<div class="toc"><div class="t">Contents</div><ol>']
    depth = 1
    for level, title, anchor in toc:
        level = max(1, min(level, 4))
        while depth < level:
            out.append("<ol>")
            depth += 1
        while depth > level:
            out.append("</ol>")
            depth -= 1
        out.append(f'<li><a href="#__PAGE__%23{anchor}">{html.escape(re.sub(r"`", "", title))}</a></li>')
    while depth > 1:
        out.append("</ol>")
        depth -= 1
    out.append("</ol></div>")
    return "".join(out)


def build(root=ROOT):
    w = Wiki()
    # ---- Java
    for jr in JAVA_ROOTS:
        base = os.path.join(root, jr)
        for dp, _, fs in os.walk(base):
            for f in sorted(fs):
                if f.endswith(".java"):
                    p = os.path.join(dp, f)
                    rel = os.path.relpath(p, root).replace("\\", "/")
                    try:
                        w.java_types.extend(parse_java_file(p, rel))
                    except Exception as e:  # noqa: BLE001
                        print(f"warn: {rel}: {e}", file=sys.stderr)
    for t in w.java_types:
        w.by_fqcn[t.fqcn] = t
        w.by_simple.setdefault(t.name, t)

    # ---- docs ids (so cross-links resolve)
    w.doc_ids = {}
    doc_paths = []
    for f in DOC_FILES:
        if os.path.exists(os.path.join(root, f)):
            doc_paths.append(f)
    for d in DOC_DIRS:
        dd = os.path.join(root, d)
        if os.path.isdir(dd):
            for dp, _, fs in os.walk(dd):
                for f in sorted(fs):
                    if f.endswith(".md"):
                        doc_paths.append(os.path.relpath(os.path.join(dp, f), root).replace("\\", "/"))
    for rel in doc_paths:
        w.doc_ids[rel] = "doc:" + rel

    # ---- Main page placeholder (filled last)
    # ---- Java pages
    packages = OrderedDict()
    for t in w.java_types:
        if t.outer is None:
            packages.setdefault(t.package or "(default)", []).append(t)

    def type_link(name):
        # link a type name in a signature if we know it
        def repl(m):
            s = m.group(0)
            tt = w.by_simple.get(s)
            return f'<a href="#{w.href(tt.page_id)}">{s}</a>' if tt else s
        return re.sub(r"\b[A-Z]\w+\b", repl, html.escape(name))

    for pkg, ts in packages.items():
        rows = []
        for t in sorted(ts, key=lambda x: x.name):
            summary = first_sentence(t.doc)
            rows.append(f"<tr><td>{w.link(t.page_id, t.name)}<span class='badge'>{t.kind}</span></td><td>{javadoc_html(summary, w.resolve_java)[0] if summary else ''}</td></tr>")
        body = f'<div class="hatnote">Package of {len(ts)} top-level types.</div><table class="api"><tr><th>Type</th><th>Summary</th></tr>{"".join(rows)}</table>'
        w.add("pkg:" + pkg, f"Package {pkg}", body, "package", f"Java package {pkg}", cats=("Java packages",))

    for t in w.java_types:
        w.add(t.page_id, t.qualified, render_java_type(t, w, type_link), t.kind, first_sentence(t.doc) or t.decl,
              cats=(f"Package {t.package}", f"Java {t.kind}s") + (("Test code",) if t.path.startswith("java-test") else ()))

    # ---- natives
    nat = w.by_fqcn.get("oxclient.Natives")
    if nat:
        rows = []
        for m in nat.methods:
            if "native" in m["mods"]:
                d, tags = javadoc_html(m["doc"], w.resolve_java)
                rows.append(f'<tr><td class="sig">{html.escape(m["ret"])} <b>{m["name"]}</b>{html.escape(m["params"])}</td><td>{d}</td></tr>')
        body = (f'<div class="hatnote">The complete unsafe surface: every <code>native</code> method of {w.link(nat.page_id, "oxclient.Natives")}. '
                f'Each is implemented in <code>client/jvm.hpp</code> and registered with the JVM by name.</div>'
                f'<table class="api"><tr><th>Native</th><th>What it reads or does</th></tr>{"".join(rows)}</table>')
        w.add("Natives", "Native methods", body, "reference", "Every JNI native the Java side can call", cats=("Reference",))

    # ---- C++ and offsets
    cpp_files = []
    for d in CPP_DIRS:
        dd = os.path.join(root, d)
        if os.path.isdir(dd):
            for f in sorted(os.listdir(dd)):
                if f.endswith((".hpp", ".cpp", ".h", ".rc")):
                    cpp_files.append(os.path.join(d, f).replace("\\", "/"))
    offsets_rows = []
    for rel in cpp_files:
        info = parse_cpp(os.path.join(root, rel))
        parts = [f'<div class="hatnote">{html.escape(rel)}, {info["lines"]} lines.</div>']
        if info["head"]:
            parts.append('<h2 id="about">About</h2><pre class="code"><code>' + html.escape(info["head"]) + "</code></pre>")
        if info["structs"]:
            parts.append('<h2 id="types">Types and namespaces</h2><table class="api"><tr><th>Declaration</th><th>Comment</th></tr>')
            for s in info["structs"]:
                parts.append(f'<tr><td class="sig">{s["kind"]} {html.escape(s["name"])} <span class="src">line {s["line"]}</span></td><td>{html.escape(s["comment"])}</td></tr>')
            parts.append("</table>")
        if info["functions"]:
            parts.append('<h2 id="functions">Functions</h2><table class="api"><tr><th>Signature</th><th>Comment</th></tr>')
            for fn in info["functions"]:
                parts.append(f'<tr><td class="sig">{html.escape(fn["ret"])} <b>{fn["name"]}</b>({html.escape(fn["params"])}) <span class="src">line {fn["line"]}</span></td><td>{html.escape(fn["comment"])}</td></tr>')
            parts.append("</table>")
        if info["offsets"]:
            parts.append('<h2 id="constants">Constants</h2><table class="api"><tr><th>Name</th><th>Value</th><th>How it was found</th></tr>')
            for o in info["offsets"]:
                status = offset_status(o["comment"] + " " + o["trail"])
                parts.append(f'<tr><td class="sig"><b>{o["name"]}</b><br><span class="src">{html.escape(o["type"])}</span>{status}</td><td class="mono">{html.escape(o["value"])}</td><td>{html.escape(o["trail"])}<pre class="code"><code>{html.escape(o["comment"])}</code></pre></td></tr>')
                if rel.endswith("offsets.hpp"):
                    offsets_rows.append((o, status))
            parts.append("</table>")
        w.add("cpp:" + rel, rel, "".join(parts), "C++", info["head"][:200], cats=("Native code",))

    if offsets_rows:
        rows = "".join(f'<tr><td class="sig"><b>{o["name"]}</b>{st}</td><td class="mono">{html.escape(o["value"])}</td><td>{html.escape(o["trail"] or first_line(o["comment"]))}</td><td><a href="#{w.href("cpp:client/offsets.hpp")}">offsets.hpp:{o["line"]}</a></td></tr>' for o, st in offsets_rows)
        body = ('<div class="hatnote">Every game-specific number the DLL depends on. Defaults compiled into <code>client/offsets.hpp</code>; '
                'at run time the DLL loads the matching <code>offsets/client-&lt;build&gt;.json</code> instead (see <a href="#Offsets%20pipeline">Offsets pipeline</a>). '
                'Each entry\'s full derivation note is on the offsets.hpp page.</div>'
                f'<table class="api"><tr><th>Name</th><th>Default</th><th>Note</th><th>Source</th></tr>{rows}</table>')
        w.add("Offsets", "Offsets", body, "reference", "Every offset and RVA, with status", cats=("Reference",))

    # ---- per-build offset JSON
    off_dir = os.path.join(root, "offsets")
    if os.path.isdir(off_dir):
        files = sorted(f for f in os.listdir(off_dir) if f.endswith(".json"))
        rows = []
        for f in files:
            try:
                j = json.load(open(os.path.join(off_dir, f), encoding="utf-8"))
            except Exception as e:  # noqa: BLE001
                rows.append(f"<tr><td>{html.escape(f)}</td><td colspan=3>unreadable: {html.escape(str(e))}</td></tr>")
                continue
            offs = j.get("offsets", {})
            st = {}
            for v in offs.values():
                st[v.get("status", "?")] = st.get(v.get("status", "?"), 0) + 1
            rows.append(f'<tr><td>{w.link("offsets:" + f, f)}</td><td>{html.escape(str(j.get("build", "")))}</td><td class="mono">{html.escape(str(j.get("sha256", ""))[:16])}…</td><td>{", ".join(f"{k}: {n}" for k, n in sorted(st.items()))}</td></tr>')
            trs = []
            for name, v in offs.items():
                badge = f'<span class="badge {html.escape(v.get("status", ""))}">{html.escape(v.get("status", ""))}</span>'
                val = v.get("value")
                val_s = hex(val) if isinstance(val, int) and not isinstance(val, bool) else html.escape(str(val))
                trs.append(f'<tr><td class="sig"><b>{html.escape(name)}</b>{badge}</td><td class="mono">{val_s}</td><td>{html.escape(str(v.get("evidence", "")))}</td></tr>')
            page = (f'<table class="infobox"><tr><td class="cap" colspan=2>{html.escape(f)}</td></tr><tr><th>Build</th><td>{html.escape(str(j.get("build", "")))}</td></tr>'
                    f'<tr><th>sha256</th><td class="mono">{html.escape(str(j.get("sha256", "")))}</td></tr><tr><th>Derived</th><td>{html.escape(str(j.get("derived", "")))}</td></tr>'
                    f'<tr><th>Source</th><td>{html.escape(str(j.get("source", "")))}</td></tr></table>'
                    f'<p>{html.escape(str(j.get("notes", "")))}</p><table class="api"><tr><th>Offset</th><th>Value</th><th>Evidence</th></tr>{"".join(trs)}</table>')
            w.add("offsets:" + f, f"Offsets for {j.get('build', f)}", page, "build", f"Per-build offsets {f}", cats=("Builds",))
        body = ('<div class="hatnote">One JSON file per client build, produced by the derivation pipeline and loaded by the DLL at run time.</div>'
                f'<table class="api"><tr><th>File</th><th>Build</th><th>sha256</th><th>Status counts</th></tr>{"".join(rows)}</table>')
        w.add("Builds", "Builds", body, "reference", "Per-build offset files", cats=("Reference",))

    # ---- Markdown docs
    for rel in doc_paths:
        pid = w.doc_ids[rel]
        w._doc_ctx = pid
        text = open(os.path.join(root, rel), encoding="utf-8", errors="replace").read()
        if rel == "LICENSE":
            body, toc = '<pre class="code"><code>' + html.escape(text) + "</code></pre>", []
        else:
            body, toc = md_to_html(text, w.resolve_doc_link)
        title = rel
        m = re.search(r"^#\s+(.+)$", text, re.M)
        if m and rel not in ("README.md",):
            title = m.group(1).strip()
        if rel == "README.md":
            title = "README"
        fm = re.match(r"^---\n(.*?)\n---\n", text, re.S)
        if fm:
            nm = re.search(r"^name:\s*(.+)$", fm.group(1), re.M)
            if nm:
                title = nm.group(1).strip()
        toc_h = toc_html(toc).replace("__PAGE__", w.href(pid))
        kind = "skill" if "/skills/" in rel else "agent" if "/agents/" in rel else "document"
        cats = ("Documents",) if kind == "document" else ("Agents and skills",)
        w.add(pid, title, f'<div class="hatnote">Source: <code>{html.escape(rel)}</code></div>' + toc_h + body, kind, text[:200], cats=cats)

    # ---- category pages
    cats = OrderedDict()
    for pid, p in list(w.pages.items()):
        for c in p["cats"]:
            cats.setdefault(c, []).append(pid)
    for c, pids in cats.items():
        items = "".join(f"<li>{w.link(pid)}</li>" for pid in sorted(pids, key=lambda x: w.pages[x]["title"].lower()))
        w.add("cat:" + c, f"Category: {c}", f'<div class="hatnote">{len(pids)} pages.</div><ul class="members">{items}</ul>', "category", c)

    # ---- plugins page
    plugin_rows = []
    for t in w.java_types:
        if re.search(r"\b(extends|implements)\b[^{]*\bPlugin\b", t.decl) and t.name != "Plugin":
            plugin_rows.append(f"<tr><td>{w.link(t.page_id, t.qualified)}</td><td>{javadoc_html(first_sentence(t.doc), w.resolve_java)[0]}</td></tr>")
    if plugin_rows:
        w.add("Plugins", "Plugins", '<div class="hatnote">Every class that is a 0xClient plugin, plus the RuneLite-ported ones hosted by <code>oxclient.rl.RlitePlugin</code>.</div><table class="api"><tr><th>Plugin</th><th>Summary</th></tr>' + "".join(plugin_rows) + "</table>", "reference", "Plugin list", cats=("Reference",))

    # ---- main page
    n_types = sum(1 for t in w.java_types if t.outer is None)
    n_methods = sum(len(t.methods) for t in w.java_types)
    n_nat = sum(1 for m in (nat.methods if nat else []) if "native" in m["mods"])
    main = f"""
<div class="tagline">Welcome to the <b>0xClient Wiki</b>, the encyclopedia of this client's API, internals and documentation. Generated from the repository by <code>tools/wiki/build_wiki.py</code>; every page below is derived from source, so what you read here is what the code says.</div>
<div class="stats">
<div class="stat"><b>{n_types}</b>Java types</div><div class="stat"><b>{n_methods}</b>methods</div><div class="stat"><b>{n_nat}</b>native methods</div>
<div class="stat"><b>{len(offsets_rows)}</b>offsets</div><div class="stat"><b>{len(doc_paths)}</b>documents</div><div class="stat"><b>{len(w.pages)}</b>pages</div>
</div>
<h2 id="start">Start here</h2>
<ul>
<li>{w.link("doc:README.md", "README")} — what 0xClient is, how to build it, how to write a plugin.</li>
<li>{w.link("Plugins")} — every plugin that ships, and {w.link("java:oxclient.Plugin", "oxclient.Plugin")} — the two methods you implement.</li>
<li>{w.link("pkg:oxclient.api", "Package oxclient.api")} — {w.link("java:oxclient.api.Game", "Game")}, {w.link("java:oxclient.api.Entity", "Entity")}, {w.link("java:oxclient.api.Actions", "Actions")}: the world as a plugin sees it.</li>
<li>{w.link("Natives")} — the whole unsafe surface, in one table.</li>
<li>{w.link("Offsets")} and {w.link("Builds")} — every game-specific number, and the per-build files the updater produces.</li>
<li>{w.link("doc:docs/plugin-system.md", "Plugin system")}, {w.link("doc:docs/architecture-after.md", "Architecture")}, {w.link("doc:docs/testing.md", "Testing")}.</li>
<li>{w.link("cat:Agents and skills", "Agents and skills")} — the automation that keeps this wiki and the offsets current.</li>
</ul>
<h2 id="browse">Browse</h2>
<ul>{"".join(f"<li>{w.link('cat:' + c)} ({len(p)})</li>" for c, p in cats.items())}</ul>
<h2 id="tips">Using this wiki</h2>
<p>Press <span class="kbd">/</span> to search. Every type, method, offset, document heading and skill is indexed. Links inside documents resolve to the matching wiki page where one exists and to GitHub otherwise. This file is self-contained: copy it anywhere and it still works.</p>
"""
    w.add("Main Page", "Main Page", main, "main", "0xClient Wiki main page")
    w.pages.move_to_end("Main Page", last=False)
    return w, packages, cpp_files, doc_paths


def offset_status(text):
    t = text.upper()
    if "REFUTED" in t:
        return '<span class="badge refuted">refuted</span>'
    if "SUSPECT" in t or "NOT DERIVED" in t:
        return '<span class="badge suspect">suspect</span>'
    if "VERIFIED LIVE" in t and "NOT VERIFIED" not in t and "NOT RE-VERIFIED" not in t and "NOT (RE-)VERIFIED" not in t:
        return '<span class="badge verified">verified live</span>'
    if "NOT VERIFIED" in t or "NOT RE-VERIFIED" in t or "NOT (RE-)VERIFIED" in t:
        return '<span class="badge carried">not verified</span>'
    return ""


def first_line(s):
    return (s or "").strip().splitlines()[0] if (s or "").strip() else ""


def first_sentence(doc):
    if not doc:
        return ""
    body = re.sub(r"^/\*\*|\*/$", "", doc.strip())
    lines = [re.sub(r"^\s*\*\s?", "", l) for l in body.splitlines()]
    text = " ".join(l for l in lines if not l.strip().startswith("@")).strip()
    text = re.sub(r"<p>|</p>", " ", text)
    m = re.match(r"(.+?[.!?])(\s|$)", text)
    return "/** " + (m.group(1) if m else text[:200]) + " */"


def render_java_type(t, w, type_link):
    doc, tags = javadoc_html(t.doc, w.resolve_java)
    gh = f"https://github.com/StoneShorts/0xClient/blob/main/{t.path}#L{t.line}"
    ext = re.search(r"\bextends\s+([^{]+?)(?=\bimplements\b|$)", t.decl)
    imp = re.search(r"\bimplements\s+([^{]+)$", t.decl)
    info = [f'<table class="infobox"><tr><td class="cap" colspan=2>{html.escape(t.name)}</td></tr>',
            f'<tr><th>Kind</th><td>{t.kind}</td></tr>',
            f'<tr><th>Package</th><td>{w.link("pkg:" + (t.package or "(default)"), t.package or "(default)")}</td></tr>']
    if t.outer:
        info.append(f'<tr><th>Enclosing</th><td>{w.link(t.outer.page_id, t.outer.qualified)}</td></tr>')
    if ext:
        info.append(f'<tr><th>Extends</th><td>{type_link(ext.group(1).strip())}</td></tr>')
    if imp:
        info.append(f'<tr><th>Implements</th><td>{type_link(imp.group(1).strip())}</td></tr>')
    if t.annotations:
        info.append(f'<tr><th>Annotations</th><td class="mono">{html.escape(" ".join(t.annotations))}</td></tr>')
    info.append(f'<tr><th>Source</th><td><a href="{gh}">{html.escape(t.path)}:{t.line}</a></td></tr>')
    info.append(f'<tr><th>Members</th><td>{len(t.fields)} fields, {len(t.ctors)} constructors, {len(t.methods)} methods, {len(t.nested)} nested</td></tr></table>')
    parts = ["".join(info)]
    parts.append(f'<div class="hatnote">{html.escape(t.kind)} <code>{html.escape(t.fqcn)}</code></div>')
    parts.append(f'<pre class="code"><code>{html.escape(t.decl)}</code></pre>')
    if doc:
        parts.append(f'<div class="doc">{doc}</div>')
    parts.append(render_tags(tags))
    if t.enum_constants:
        parts.append('<h2 id="constants">Enum constants</h2><table class="api"><tr><th>Constant</th><th>Description</th></tr>')
        for c in t.enum_constants:
            d, _ = javadoc_html(c["doc"], w.resolve_java)
            parts.append(f'<tr><td class="sig"><b>{c["name"]}</b>{html.escape(c["args"])}</td><td>{d}</td></tr>')
        parts.append("</table>")
    if t.fields:
        parts.append('<h2 id="fields">Fields</h2><table class="api"><tr><th>Field</th><th>Description</th></tr>')
        for f in t.fields:
            d, tg = javadoc_html(f["doc"], w.resolve_java)
            val = f' = {html.escape(f["value"][:120])}' if f["value"] and "final" in f["mods"] else ""
            parts.append(f'<tr><td class="sig">{html.escape(" ".join(f["mods"]))} {type_link(f["type"])} <b>{f["name"]}</b>{val} <span class="src">line {f["line"]}</span></td><td>{d}{render_tags(tg)}</td></tr>')
        parts.append("</table>")
    if t.ctors:
        parts.append('<h2 id="constructors">Constructors</h2><table class="api"><tr><th>Constructor</th><th>Description</th></tr>')
        for c in t.ctors:
            d, tg = javadoc_html(c["doc"], w.resolve_java)
            parts.append(f'<tr><td class="sig">{html.escape(" ".join(c["mods"]))} <b>{c["name"]}</b>{type_link(c["params"])} <span class="src">line {c["line"]}</span></td><td>{d}{render_tags(tg)}</td></tr>')
        parts.append("</table>")
    if t.methods:
        parts.append('<h2 id="methods">Methods</h2><table class="api"><tr><th>Method</th><th>Description</th></tr>')
        for m in sorted(t.methods, key=lambda x: (("native" not in x["mods"]), x["name"])):
            d, tg = javadoc_html(m["doc"], w.resolve_java)
            ann = (" ".join(html.escape(a) for a in m["ann"]) + " ") if m["ann"] else ""
            parts.append(f'<tr><td class="sig">{ann}{html.escape(" ".join(m["mods"]))} {type_link(m["ret"])} <b>{m["name"]}</b>{type_link(m["params"])} <span class="src">line {m["line"]}</span></td><td>{d}{render_tags(tg)}</td></tr>')
        parts.append("</table>")
    if t.nested:
        parts.append('<h2 id="nested">Nested types</h2><ul class="members">' + "".join(f'<li>{w.link(n.page_id, n.name)} <span class="badge">{n.kind}</span> — {javadoc_html(first_sentence(n.doc), w.resolve_java)[0]}</li>' for n in t.nested) + "</ul>")
    return "".join(parts)


def render_tags(tags):
    if not tags:
        return ""
    out = ['<dl class="tags">']
    names = {"param": "Parameters", "return": "Returns", "throws": "Throws", "exception": "Throws", "see": "See also", "deprecated": "Deprecated", "since": "Since", "author": "Author", "implNote": "Implementation note", "apiNote": "API note"}
    for k, vs in tags.items():
        out.append(f"<dt><b>{html.escape(names.get(k, k))}</b></dt>")
        for v in vs:
            out.append(f"<dd>{v}</dd>")
    out.append("</dl>")
    return "".join(out)


def render(w, packages, cpp_files, doc_paths, root):
    side = ['<h3>Navigation</h3><ul>',
            f'<li>{w.link("Main Page")}</li><li>{w.link("doc:README.md", "README")}</li><li>{w.link("Plugins")}</li><li>{w.link("Natives")}</li><li>{w.link("Offsets")}</li><li>{w.link("Builds")}</li>',
            "</ul><h3>Java API</h3>"]
    for pkg, ts in packages.items():
        side.append(f'<details{" open" if pkg.startswith("oxclient") else ""}><summary>{html.escape(pkg)}</summary><ul class="pkg">')
        for t in sorted(ts, key=lambda x: x.name):
            side.append(f"<li>{w.link(t.page_id, t.name)}</li>")
        side.append("</ul></details>")
    side.append("<h3>Native code</h3><ul>")
    for rel in cpp_files:
        side.append(f'<li>{w.link("cpp:" + rel, rel)}</li>')
    side.append("</ul><h3>Documents</h3><ul>")
    for rel in doc_paths:
        if "/skills/" in rel or "/agents/" in rel:
            continue
        side.append(f'<li>{w.link(w.doc_ids[rel])}</li>')
    side.append("</ul><h3>Agents and skills</h3><ul>")
    for rel in doc_paths:
        if "/skills/" in rel or "/agents/" in rel:
            side.append(f'<li>{w.link(w.doc_ids[rel])}</li>')
    side.append("</ul>")

    arts = []
    for pid, p in w.pages.items():
        arts.append(f'<article id="page:{html.escape(pid, quote=True)}" data-title="{html.escape(p["title"], quote=True)}"><h1>{html.escape(p["title"])}</h1>{p["html"]}</article>')
    index_json = json.dumps(w.index, ensure_ascii=False).replace("</", "<\\/")
    stamp = date.today().isoformat()
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>0xClient Wiki</title>
<meta name="description" content="The 0xClient API and internals, generated from source.">
<style>{CSS}</style></head>
<body>
<div id="top"><a class="logo" href="#Main%20Page"><span class="mark">0x</span><span><b>0x</b>Client Wiki</span></a>
<input id="q" type="search" placeholder="Search the wiki  (press / to focus)" autocomplete="off">
<span class="meta">{len(w.pages)} pages · built {stamp}</span></div>
<div id="results"></div>
<nav id="side">{"".join(side)}</nav>
<main id="main">{"".join(arts)}</main>
<script>window.__WIKI_INDEX__ = {index_json};</script>
<script>{JS}</script>
</body></html>
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="exit 1 if wiki/index.html differs from what would be built")
    ap.add_argument("--out", default=OUT)
    a = ap.parse_args()
    w, packages, cpp_files, doc_paths = build()
    page = render(w, packages, cpp_files, doc_paths, ROOT)
    # keep the build date out of the comparison so --check is stable within a day
    if a.check:
        old = open(a.out, encoding="utf-8").read() if os.path.exists(a.out) else ""
        norm = lambda s: re.sub(r"built \d{4}-\d{2}-\d{2}", "built X", s)
        if norm(old) != norm(page):
            print("wiki/index.html is out of date: run python tools/wiki/build_wiki.py", file=sys.stderr)
            sys.exit(1)
        print("wiki is current")
        return
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(page)
    print(f"wrote {os.path.relpath(a.out, ROOT)}: {len(w.pages)} pages, {len(page) // 1024} KB, "
          f"{sum(1 for t in w.java_types)} Java types, {len(cpp_files)} C++ files, {len(doc_paths)} documents")


if __name__ == "__main__":
    main()
