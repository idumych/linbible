#!/usr/bin/env bash
# Builds And Bible's BibleView (Vue.js) frontend for the desktop shell.
#
# The upstream sources are copied (not modified in place) and patched so that
# in-page navigation (`window.location.assign(<custom-scheme url>)`), which Android
# intercepts through shouldOverrideUrlLoading, is routed to the desktop bridge instead.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/upstream/app/bibleview-js"
WORK="$ROOT/build-frontend/bibleview-js"
OUT="$ROOT/build-frontend/dist"

mkdir -p "$WORK"
rsync -a --delete --exclude node_modules --exclude dist "$SRC/" "$WORK/"

grep -rl 'window\.location\.assign(' "$WORK/src" | while read -r f; do
    sed -i 's/window\.location\.assign(/window.android.openLink(/g' "$f"
done

cd "$WORK"
if [ ! -d node_modules ] || [ package-lock.json -nt node_modules ]; then
    npm ci --no-audit --no-fund
fi
NODE_ENV=production npx vite build --mode production --outDir "$OUT" --emptyOutDir
echo "Frontend built into $OUT"
