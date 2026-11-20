#!/bin/sh
# Says whether this commit's tests have already passed on main, as "already=true" or "already=false" in
# $GITHUB_OUTPUT. A version tag is put on a commit of main, which the workflow has just run as a push to main: if
# that run is still under way this waits for it, up to half an hour. No run of the commit there, as when it only
# changed documentation, or one that did not pass, is "false": the tag's run then tests everything itself.
# Needs GH_TOKEN with actions: read.
set -eu

already=false
for attempt in $(seq 1 90); do
    run=$(gh run list --workflow image.yml --commit "$GITHUB_SHA" --event push --branch main --limit 1 \
        --json status,conclusion --jq '.[0] | "\(.status) \(.conclusion)"')
    case "$run" in
        "completed success") already=true; break ;;
        "" | "null null" | completed*) break ;;
        *) echo "The run of this commit on main is $run: waiting"; sleep 20 ;;
    esac
done
echo "already=$already" >> "$GITHUB_OUTPUT"
echo "This commit's tests had already passed on main: $already"
