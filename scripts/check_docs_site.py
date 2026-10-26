#!/usr/bin/env python3
"""Post-build checks for the docs site: redirect targets and anchors exist.

Usage: check_docs_site.py <site_dir>
Exits 1 with one line per problem; prints "docs site OK" on success.
"""
import re
import sys
from pathlib import Path

import yaml

REDIRECTS = Path(__file__).resolve().parent.parent / "docs" / "redirects.yml"


def page_html(site: Path, md: str) -> Path:
    stem = md[: -len(".md")] if md.endswith(".md") else md
    return site / "index.html" if stem == "index" else site / stem / "index.html"


def main(argv):
    if len(argv) != 2:
        print("usage: check_docs_site.py <site_dir>", file=sys.stderr)
        return 2
    site = Path(argv[1])
    redirects = yaml.safe_load(REDIRECTS.read_text(encoding="utf-8")) or {}
    problems = []
    cache = {}

    def html_of(md):
        path = page_html(site, md)
        if path not in cache:
            cache[path] = path.read_text(encoding="utf-8") if path.is_file() else None
        return cache[path]

    def check_target(target, origin):
        md, _, anchor = target.partition("#")
        html = html_of(md)
        if html is None:
            problems.append(f"{md}: target page missing (from {origin})")
        elif anchor and not re.search(r'(?<![\w-])id=["\']%s["\']' % re.escape(anchor), html):
            problems.append(f"{target}: target anchor missing (from {origin})")

    for stem, entry in redirects.items():
        if not (site / stem / "index.html").is_file():
            problems.append(f"{stem}/index.html: retired page stub missing")
        check_target(entry["default"], f"{stem} default")
        for old, target in (entry.get("anchors") or {}).items():
            check_target(target, f"{stem}#{old}")

    if not (site / "rules" / "index.html").is_file():
        problems.append("rules/index.html: missing")

    if problems:
        print("\n".join(problems))
        return 1
    print("docs site OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
