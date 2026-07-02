"""mkdocs-macros hooks. Exposes the project version so install snippets never go stale."""
import os
import re


def _read_version() -> str:
    # Prefer an explicitly injected version (CI can set SERIALKOMPAT_VERSION); else read gradle.properties.
    env = os.environ.get("SERIALKOMPAT_VERSION")
    if env:
        return env
    try:
        with open("gradle.properties", encoding="utf-8") as fh:
            for line in fh:
                m = re.match(r"\s*version\s*=\s*(\S+)", line)
                if m:
                    return m.group(1)
    except OSError:
        pass
    return "dev"


def define_env(env):
    env.variables["skversion"] = _read_version()
