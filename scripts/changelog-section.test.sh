#!/usr/bin/env bash
# Tests for changelog-section.sh. Run: scripts/changelog-section.test.sh
set -u
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail=0

cat > "$tmp/CHANGELOG.md" <<'EOF'
# Changelog

## [Unreleased]

## [1.2.0] - 2026-10-07

### Fixed
- A fix (#2).

## [1.1.0] - 2026-09-01

### Added
- Older entry (#1).

[Unreleased]: https://example.com/compare/v1.2.0...main
EOF

expect() { # name, expected exit, expected stdout, args...
  local name=$1 code=$2 want=$3; shift 3
  local got rc
  got="$("$here/changelog-section.sh" "$@" 2>/dev/null)"; rc=$?
  if [ "$rc" != "$code" ] || [ "$got" != "$want" ]; then
    echo "FAIL: $name (exit $rc, want $code)"; echo "--- got:"; echo "$got"; echo "--- want:"; echo "$want"
    fail=1
  else
    echo "ok: $name"
  fi
}

expect "prints the version's section without its heading or the next section" 0 \
"### Fixed
- A fix (#2)." 1.2.0 "$tmp/CHANGELOG.md"

expect "stops before the link references at the end" 0 \
"### Added
- Older entry (#1)." 1.1.0 "$tmp/CHANGELOG.md"

expect "a missing version fails" 1 "" 9.9.9 "$tmp/CHANGELOG.md"

expect "an empty [Unreleased] section fails" 1 "" Unreleased "$tmp/CHANGELOG.md"

expect "dots in the version are literal, not regex wildcards" 1 "" 1x2x0 "$tmp/CHANGELOG.md"

exit $fail
