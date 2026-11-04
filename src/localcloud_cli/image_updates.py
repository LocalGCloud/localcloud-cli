"""Offer a newer version of the image a runtime is pinned to.

A runtime keeps the image its data volume last ran, so one pinned to
`agentcloud/localcloud:0.1.8-lite` never moves on its own. Before `start` and
`restart`, this module looks for a newer `X.Y.Z[-lite]` tag on the runtime's
channel (lite or full) and either switches to it (the user said yes, or passed
`--pull`) or reports it. It never changes an image without that consent.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from dataclasses import dataclass
from typing import Literal

from .config import parse_image_tag
from .constants import LITE_TAG_SUFFIX

REPOSITORY = "agentcloud/localcloud"
# The same repository as Docker spells it when the registry is named.
_REPOSITORY_ALIASES = frozenset(
    {REPOSITORY, f"docker.io/{REPOSITORY}", f"index.docker.io/{REPOSITORY}"}
)
_VERSION_TAG = re.compile(r"^(\d+)\.(\d+)\.(\d+)(-lite)?$")

Channel = Literal["lite", "full"]


@dataclass(frozen=True)
class ImageRef:
    """An `agentcloud/localcloud` image named by a version tag."""

    repository: str
    version: tuple[int, int, int]
    lite: bool

    @property
    def tag(self) -> str:
        return ".".join(map(str, self.version)) + (LITE_TAG_SUFFIX if self.lite else "")

    @property
    def image(self) -> str:
        return f"{self.repository}:{self.tag}"

    @property
    def channel(self) -> Channel:
        return "lite" if self.lite else "full"


def ref_from_tag(tag: str, repository: str = REPOSITORY) -> ImageRef | None:
    """The version a tag names; None for anything but `X.Y.Z` or `X.Y.Z-lite`,
    such as floating tags (`lite`, `latest`, `0.1`) and local builds."""
    match = _VERSION_TAG.match(tag)
    if match is None:
        return None
    major, minor, patch, lite = match.groups()
    return ImageRef(repository, (int(major), int(minor), int(patch)), lite is not None)


def parse_ref(image: str) -> ImageRef | None:
    """The version of an `agentcloud/localcloud` image named by a version tag;
    None for a digest pin, an image ID, another repository or a floating tag."""
    parts = parse_image_tag(image)
    if parts is None:
        return None
    repository, tag = parts
    if repository not in _REPOSITORY_ALIASES:
        return None
    return ref_from_tag(tag, repository)


def newest(current: ImageRef, tags: Iterable[str]) -> ImageRef | None:
    """The highest version among `tags` that is on `current`'s channel and
    strictly newer than it, in `current`'s repository spelling."""
    best: ImageRef | None = None
    for tag in tags:
        ref = ref_from_tag(tag, current.repository)
        if ref is None or ref.lite != current.lite or ref.version <= current.version:
            continue
        if best is None or ref.version > best.version:
            best = ref
    return best
