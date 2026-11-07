"""Mechanical checks of the maintainers' docs, for the review-docs skill.

Run from the repository's root: python3 -I .claude/skills/review-docs/check_docs.py
Prints what it finds, one line each; what needs judgment is left to whoever reads it.
"""
import glob
import os
import re
import sys
from collections import defaultdict

AGENTS_MAX = 15_000  # bytes: AGENTS.md is read in every session
PAGE_MAX = 20_000  # bytes: past this, a page is worth splitting

DOCS = ["AGENTS.md", "README.md"] + sorted(glob.glob("docs/*.md")) + sorted(glob.glob("docs/dev/*.md"))
DEV = sorted(glob.glob("docs/dev/*.md"))
# Named in the docs on purpose though the code has no such type or path: names that Nuki or
# Spring use, git-ignored folders, time zones. Add to these rather than living with the noise.
KNOWN_NAMES = {"LockAction", "SmartlockAction", "SessionRegistry", "WebAuthnConfigurer"}
KNOWN_PATHS = re.compile(r"^(secrets/|backups/|origin/|ghcr\.io/|Java/|[A-Z][a-z]+/[A-Z]|\w+\.\w+/)")
found = defaultdict(list)


def report(kind, message):
    found[kind].append(message)


def slugs(path):
    """GitHub's anchors for a page's headings."""
    result = set()
    with open(path) as f:
        for line in f:
            m = re.match(r"#+ (.*)", line)
            if m:
                result.add(re.sub(r"[^\w\- ]", "", m.group(1).strip().lower()).replace(" ", "-"))
    return result


def text(path):
    with open(path) as f:
        return f.read()


# Links and anchors.
for doc in DOCS:
    for m in re.finditer(r"\]\(([^)\s]+)\)", text(doc)):
        url = m.group(1)
        if re.match(r"[a-z]+:", url):
            continue
        path, _, anchor = url.partition("#")
        target = os.path.normpath(os.path.join(os.path.dirname(doc), path)) if path else doc
        if not os.path.exists(target):
            report("broken link", f"{doc}: {url}")
        elif anchor and target.endswith(".md") and anchor not in slugs(target):
            report("broken anchor", f"{doc}: {url}")

# Sizes.
size = os.path.getsize("AGENTS.md")
if size > AGENTS_MAX:
    report("size", f"AGENTS.md: {size} bytes, over {AGENTS_MAX}: move what is not for every task to its area")
for page in DEV:
    size = os.path.getsize(page)
    if size > PAGE_MAX:
        report("size", f"{page}: {size} bytes, over {PAGE_MAX}: worth splitting")

# Every area page says when to read it and is reachable from AGENTS.md.
agents = text("AGENTS.md")
for page in DEV:
    if not re.search(r"^Read when: ", text(page), re.M):
        report("structure", f"{page}: no 'Read when:' line")
    if f"(docs/dev/{os.path.basename(page)}" not in agents:
        report("structure", f"{page}: not linked from AGENTS.md")

# Names in backticks that the code no longer has. External types (Spring's, Tink's...) show up
# here too: only names that look like the project's own are worth a look.
java = {}
for path in glob.glob("src/**/*.java", recursive=True):
    java[path] = text(path)
declared = set()
for source in java.values():
    declared.update(re.findall(r"\b(?:class|record|interface|enum|@interface)\s+(\w+)", source))
    declared.update(re.findall(r"^import (?:static )?[\w.]+\.(\w+);", source, re.M))  # a library's types
for doc in ["AGENTS.md"] + DEV:
    names = set()
    for span in re.findall(r"`([^`\n]+)`", text(doc)):
        names.update(re.findall(r"\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+\b", span))
    for name in sorted(names - declared - KNOWN_NAMES):
        report("unknown name", f"{doc}: {name}")

# Paths in backticks that are gone.
for doc in ["AGENTS.md"] + DEV:
    for span in set(re.findall(r"`([^`\s]+)`", text(doc))):
        if span.startswith(("/", "http")) or "/" not in span or "{" in span or "<" in span or "*" in span:
            continue
        if re.match(r"^[\w.-]+/[\w./-]*$", span) and not KNOWN_PATHS.match(span):
            if not os.path.exists(span.rstrip("/")) and not glob.glob(f"**/{span.rstrip('/')}", recursive=True):
                report("unknown path", f"{doc}: {span}")

# The same long sentence in two places: one of them should link to the other.
seen = defaultdict(set)
for doc in DOCS:
    for sentence in re.split(r"(?<=[.:;])\s+", re.sub(r"\s+", " ", text(doc))):
        if len(sentence) >= 100:
            seen[sentence].add(doc)
for sentence, docs in seen.items():
    if len(docs) > 1:
        report("repeated", f"{', '.join(sorted(docs))}: {sentence[:90]}...")

for kind, messages in found.items():
    print(f"## {kind} ({len(messages)})")
    for message in messages:
        print(f"- {message}")
if not found:
    print("Nothing found.")
sys.exit(0)
