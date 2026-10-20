"""Release notes from a release pull request's description, read on stdin and printed on stdout.

The description's first paragraph says what the pull request does ("Sets the project's version to ..."), and
its last ones are Claude Code's attribution: neither belongs in the notes. Everything else stays as written.
"""
import sys

ABOUT_THE_PULL_REQUEST = ("Sets the project's version", "First release.")
ATTRIBUTION = ("🤖 Generated with", "https://claude.ai/code/")


def notes(description: str) -> str:
    paragraphs = [p.strip() for p in description.replace("\r\n", "\n").strip().split("\n\n") if p.strip()]
    if paragraphs and paragraphs[0].startswith(ABOUT_THE_PULL_REQUEST):
        paragraphs = paragraphs[1:]
    return "\n\n".join(p for p in paragraphs if not p.startswith(ATTRIBUTION))


if __name__ == "__main__":
    print(notes(sys.stdin.read()))
