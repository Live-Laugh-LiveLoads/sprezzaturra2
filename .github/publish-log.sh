#!/bin/bash
# Pushes a failed job's log (and a short error digest) to the ci-logs branch.
# Usage: publish-log.sh <name> <logfile>
set -u
name="$1"; log="$2"
[ -f "$log" ] || { echo "no log"; exit 0; }
dir=$(mktemp -d)
cp "$log" "$dir/$name.log"
grep -nE '^e: |error:|What went wrong|FAILED|Exception|> Task .* FAILED|Caused by' "$log" | head -300 > "$dir/$name-errors.txt" || true
# Emit the first errors as annotations too.
head -20 "$dir/$name-errors.txt" | while IFS= read -r line; do echo "::error title=$name::${line:0:900}"; done
cd "$dir"
git init -q -b ci-logs
git config user.name "ci"
git config user.email "ci@users.noreply.github.com"
git add -A
git commit -qm "$name log for ${GITHUB_SHA:-local} run ${GITHUB_RUN_ID:-0}"
git push -q -f "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" "ci-logs:ci-logs-$name" || echo "log push failed"
