#!/usr/bin/env bash
# Prints the body of the `## [VERSION]` section of a Keep-a-Changelog file: everything after
# that heading up to the next `## [` heading or the trailing link references, trimmed.
# Exits 1 if the section is missing or empty. Used by the Release workflow.
#
# Usage: scripts/changelog-section.sh <version> [CHANGELOG.md]
set -euo pipefail
version="${1:?usage: changelog-section.sh <version> [file]}"
file="${2:-CHANGELOG.md}"

body="$(awk -v heading="## [${version}]" '
  index($0, "## [") == 1 { if (inside) exit; inside = (index($0, heading) == 1); next }
  inside && /^\[[^]]+\]: / { exit }
  inside { print }
' "$file" | sed -e '/./,$!d' | sed -e ':a' -e '/^\n*$/{$d;N;ba' -e '}')"

if [ -z "$body" ]; then
  echo "changelog-section: no '## [${version}]' section with content in ${file}" >&2
  exit 1
fi
printf '%s\n' "$body"
