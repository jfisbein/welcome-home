---
name: review-docs
description: Review the maintainers' docs (AGENTS.md and docs/dev/) for what has gone stale, is in the wrong page, is repeated or has grown too much, fix it and commit. Run now and then, when the owner asks.
disable-model-invocation: true
---

# Review the maintainers' docs

AGENTS.md is the map, read in every session; `docs/dev/` has one page per area, each read only when a task touches it. Each task keeps them up to date (AGENTS.md, Rules, "Docs are part of the task"); this review catches what that misses: text that the code has left behind, knowledge in the wrong page, the same thing said twice, pages that grew too much.

## 1. Start from where things are

```sh
git fetch && git pull --ff-only
git log -1 --format='%h %as' --grep='^Review the maintainers. docs'   # the last review, if any
git log --oneline <that commit>..HEAD -- src deploy .github build.gradle.kts   # what changed since
```

With no earlier review, take the last 60 commits. Read the commit messages, not the diffs, and note each change a page should reflect: a new class, table, job, property, rule or decision, something renamed or removed. Open a diff only where a message leaves it unclear.

## 2. The mechanical checks

```sh
python3 -I .claude/skills/review-docs/check_docs.py
```

It reports broken links and anchors, pages over their size, area pages without "Read when:" or not linked from AGENTS.md, names in backticks that the code no longer has, paths that are gone, and long sentences repeated in two files. Fix each finding. A name or path that is right though the code has no such type (Nuki's, a git-ignored folder) goes in `KNOWN_NAMES` or `KNOWN_PATHS` in the script rather than being reported again next time.

## 3. Read each page against the code

Page by page in `docs/dev/`, then AGENTS.md. Check claims with `grep` and by reading the classes they name, not by reading the code whole. Look for:

- **Stale**: what a page says the code does and it no longer does, the changes noted in step 1 that no page reflects, tests named for what they no longer cover.
- **Misplaced**: anything in AGENTS.md that only one area needs (it moves to that area's page, leaving at most a link); a decision or test in an area other than the one whose code it is about; a "Read when:" that no longer matches what the page holds.
- **Repeated**: the same thing in two pages, or in a page and `docs/features.md`, `docs/security.md`, `docs/deployment.md` or `docs/runbook.md`. Keep it where it belongs and link to it from the other.
- **Done or answered**: items in `status.md` that a release did, or that real use answered. The finding moves to its area's page (what was learned, with its date); the item goes.
- **Too big**: a page over its size is split along what tasks read apart (in `nuki.md`, for instance, what was learned by hand apart from the decisions), each new page with its "Read when:" and its line in AGENTS.md.
- **Private**: anything the Rules keep out of the public repository (production's hostnames, addresses, paths on the server, who opened a door when). Remove it and tell the owner, as it is in git's history too.

## Rules of the review

- Move text rather than rewrite it. Correct what is wrong; do not reword what is right, or shorten a decision's why.
- Never drop a decision, the owner's choices and their dates ("the user's choice, 2026-11-04"), or something learned about Nuki: move it, or ask.
- A page that disagrees with the code is not always the page's mistake: when the code looks wrong instead, or a decision seems to have been reversed without saying why, ask the owner rather than make the page match the code.
- Change only docs. A bug found on the way is reported, not fixed here.
- Run the script again at the end: it must find nothing.

## 4. Commit and report

One commit, `Review the maintainers' docs`, whose message lists what changed by page; step 1 finds the next review's starting point by that subject. Push it to `main` (docs only, so `./gradlew build` is not affected). Then tell the owner, in their language: what was fixed, what was moved or split, and what needs their decision, each with the page it is in.
