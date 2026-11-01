#!/usr/bin/env bash
# Pysanka: Android refuses string resources with an unescaped apostrophe (the build then fails
# late, deep in resource merging). Catch it early, with the exact line.
set -euo pipefail
bad=$(grep -nP "<(string|item)[^>]*>[^<]*(?<!\\\\)'[^<]*</(string|item)>" app/src/main/res/values*/strings*.xml || true)
if [ -n "$bad" ]; then
  echo "::error::Unescaped apostrophe in strings (write \\' ):"
  echo "$bad"
  exit 1
fi
echo "Strings OK"
