"""mkdocs-macros hooks. Exposes the project version so install snippets never go stale."""
import os
import re
import subprocess

_STABLE_TAG = re.compile(r"v(\d+\.\d+\.\d+)$")


def _latest_release_tag():
    # The newest stable `vX.Y.Z` tag is the version readers can actually resolve from Maven
    # Central. Needs the tags in the checkout (docs.yml uses fetch-depth: 0).
    try:
        out = subprocess.run(
            ["git", "tag", "--list", "v*.*.*", "--sort=-v:refname"],
            capture_output=True, text=True, check=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        return None
    for tag in out.split():
        m = _STABLE_TAG.match(tag)
        if m:
            return m.group(1)
    return None


def _gradle_version():
    try:
        with open("gradle.properties", encoding="utf-8") as fh:
            for line in fh:
                m = re.match(r"\s*version\s*=\s*(\S+)", line)
                if m:
                    return m.group(1)
    except OSError:
        pass
    return None


def _read_version() -> str:
    # 1. An explicitly injected version (CI can set SERIALKOMPAT_VERSION).
    env = os.environ.get("SERIALKOMPAT_VERSION")
    if env:
        return env
    # 2. The latest release. Never a -SNAPSHOT: mavenCentral() can't serve those.
    tag = _latest_release_tag()
    if tag:
        return tag
    # 3. Before the first release: the upcoming version (0.1.0-SNAPSHOT -> 0.1.0).
    version = _gradle_version()
    if version:
        return version.removesuffix("-SNAPSHOT")
    return "dev"


def define_env(env):
    env.variables["skversion"] = _read_version()
