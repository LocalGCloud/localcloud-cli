"""Offer a newer version of the image a runtime is pinned to.

A runtime keeps the image its data volume last ran, so one pinned to
`agentcloud/localcloud:0.1.8-lite` never moves on its own. Before `start` and
`restart`, this module looks for a newer `X.Y.Z[-lite]` tag on the runtime's
channel (lite or full) and either switches to it (the user said yes, or passed
`--pull`) or reports it. It never changes an image without that consent.
"""

from __future__ import annotations

import json
import os
import re
import tempfile
import time
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Literal

import httpx

from .config import parse_image_tag
from .constants import LITE_TAG_SUFFIX

REPOSITORY = "agentcloud/localcloud"
# The same repository as Docker spells it when the registry is named.
_REPOSITORY_ALIASES = frozenset(
    {REPOSITORY, f"docker.io/{REPOSITORY}", f"index.docker.io/{REPOSITORY}"}
)
_VERSION_TAG = re.compile(r"^(\d+)\.(\d+)\.(\d+)(-lite)?$")

STATE_FILE = "image-updates.json"
STATE_SCHEMA_VERSION = 1
# A registry answer is reused for this long, so most commands make no request.
CACHE_SECONDS = 6 * 60 * 60
# After a failed lookup, commands skip the registry for this long instead of
# each paying the timeout again.
OFFLINE_BACKOFF_SECONDS = 10 * 60

REGISTRY_TAGS_URL = (
    f"https://hub.docker.com/v2/repositories/{REPOSITORY}/tags"
    "?page_size=100&ordering=last_updated"
)
REGISTRY_CONNECT_TIMEOUT = 1.5
# The whole lookup, every page included, ends within this many seconds.
REGISTRY_TOTAL_TIMEOUT = 3.0
REGISTRY_MAX_PAGES = 3
# Docker reports the engine's machine name; Docker Hub lists Go's names.
_ARCHITECTURES = {"x86_64": "amd64", "amd64": "amd64", "aarch64": "arm64", "arm64": "arm64"}

Channel = Literal["lite", "full"]


@dataclass(frozen=True)
class Listed:
    """A tag the registry lists, with the download size of its image for the
    engine's architecture when the registry gives one."""

    tag: str
    size: int | None = None


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


# --- Remembered answers -------------------------------------------------------


class UpdateStore:
    """`image-updates.json` in the CLI home: the registry's last answer, the
    offline backoff, and the versions a user declined per data volume.

    Every read tolerates a missing, unreadable or foreign file (it reads as
    empty) and every write is best-effort, so this state never fails a command.
    """

    def __init__(self, path: Path) -> None:
        self.path = path

    # Registry answers

    def cached_newest(self, arch: str, now: float) -> dict[str, Listed] | None:
        """The newest tag per channel the registry listed for `arch`, while
        that answer is fresh; None when there is no fresh answer."""
        registry = self._load().get("registry")
        if not isinstance(registry, dict) or registry.get("arch") != arch:
            return None
        checked_at = _seconds(registry.get("checked_at"))
        if checked_at is None or not 0 <= now - checked_at < CACHE_SECONDS:
            return None
        newest = registry.get("newest")
        if not isinstance(newest, dict):
            return None
        listed: dict[str, Listed] = {}
        for channel, entry in newest.items():
            if channel not in {"lite", "full"} or not isinstance(entry, dict):
                continue
            tag, size = entry.get("tag"), entry.get("size")
            if isinstance(tag, str):
                listed[channel] = Listed(tag, size if isinstance(size, int) else None)
        return listed

    def remember_registry(self, arch: str, newest: dict[str, Listed], now: float) -> None:
        state = self._load()
        state["registry"] = {
            "checked_at": _stamp(now),
            "arch": arch,
            "newest": {
                channel: {"tag": entry.tag, "size": entry.size}
                for channel, entry in newest.items()
            },
        }
        state["offline_until"] = None
        self._save(state)

    def offline(self, now: float) -> bool:
        """Whether a recent failed lookup says to skip the registry for now."""
        until = _seconds(self._load().get("offline_until"))
        return until is not None and now < until

    def remember_offline(self, now: float) -> None:
        state = self._load()
        state["offline_until"] = _stamp(now + OFFLINE_BACKOFF_SECONDS)
        self._save(state)

    # Declined offers

    def declined(self, data_volume: str, channel: str) -> str | None:
        """The tag last declined on this data volume and channel."""
        declined = self._load().get("declined")
        volume = declined.get(data_volume) if isinstance(declined, dict) else None
        tag = volume.get(channel) if isinstance(volume, dict) else None
        return tag if isinstance(tag, str) else None

    def remember_declined(self, data_volume: str, channel: str, tag: str) -> None:
        state = self._load()
        declined = state.get("declined")
        if not isinstance(declined, dict):
            declined = state["declined"] = {}
        volume = declined.get(data_volume)
        if not isinstance(volume, dict):
            volume = declined[data_volume] = {}
        volume[channel] = tag
        self._save(state)

    # File

    def _load(self) -> dict[str, Any]:
        try:
            state = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {"schema_version": STATE_SCHEMA_VERSION}
        if not isinstance(state, dict) or state.get("schema_version") != STATE_SCHEMA_VERSION:
            return {"schema_version": STATE_SCHEMA_VERSION}
        return state

    def _save(self, state: dict[str, Any]) -> bool:
        try:
            self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            descriptor, temporary = tempfile.mkstemp(
                dir=self.path.parent, prefix=f".{self.path.name}.", suffix=".tmp"
            )
        except OSError:
            return False
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as state_file:
                json.dump(state, state_file, indent=2, sort_keys=True)
            os.replace(temporary, self.path)
        except OSError:
            try:
                os.unlink(temporary)
            except OSError:
                pass
            return False
        return True


def _stamp(seconds: float) -> str:
    return datetime.fromtimestamp(seconds, UTC).isoformat()


def _seconds(value: object) -> float | None:
    if not isinstance(value, str):
        return None
    try:
        moment = datetime.fromisoformat(value)
    except ValueError:
        return None
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    return moment.timestamp()


# --- Registry -----------------------------------------------------------------


class RegistryUnavailable(Exception):
    """The registry gave no usable answer: offline, DNS, TLS, timeout, an HTTP
    error status or a body that is not the tag listing."""


def hub_architecture(machine: str | None) -> str | None:
    """Docker Hub's name for an engine or host architecture."""
    if not machine:
        return None
    return _ARCHITECTURES.get(machine.strip().lower())


def registry_newest(
    arch: str,
    *,
    transport: httpx.BaseTransport | None = None,
    clock: Any = time.monotonic,
) -> dict[str, Listed]:
    """The newest `X.Y.Z` and `X.Y.Z-lite` tag Docker Hub lists with an image
    for `arch`, by channel. Raises RegistryUnavailable."""
    deadline = clock() + REGISTRY_TOTAL_TIMEOUT
    newest: dict[str, tuple[tuple[int, int, int], Listed]] = {}
    url: str | None = REGISTRY_TAGS_URL
    try:
        with httpx.Client(transport=transport, follow_redirects=True) as client:
            for _page in range(REGISTRY_MAX_PAGES):
                if url is None:
                    break
                remaining = deadline - clock()
                if remaining <= 0:
                    raise RegistryUnavailable("the registry lookup timed out")
                response = client.get(
                    url,
                    timeout=httpx.Timeout(
                        remaining, connect=min(REGISTRY_CONNECT_TIMEOUT, remaining)
                    ),
                )
                response.raise_for_status()
                page = response.json()
                if not isinstance(page, dict) or not isinstance(page.get("results"), list):
                    raise RegistryUnavailable("the registry answered without a tag list")
                for entry in page["results"]:
                    listed = _listed_for(entry, arch)
                    if listed is None:
                        continue
                    ref = ref_from_tag(listed.tag)
                    if ref is None:
                        continue
                    best = newest.get(ref.channel)
                    if best is None or ref.version > best[0]:
                        newest[ref.channel] = (ref.version, listed)
                following = page.get("next")
                url = following if isinstance(following, str) and following else None
    except (httpx.HTTPError, ValueError) as error:
        raise RegistryUnavailable(str(error) or type(error).__name__) from error
    return {channel: listed for channel, (_version, listed) in newest.items()}


def registry_answer(
    store: UpdateStore,
    arch: str,
    *,
    fresh: bool = False,
    now: float | None = None,
    transport: httpx.BaseTransport | None = None,
) -> dict[str, Listed] | None:
    """The registry's newest tags for `arch`: a cached answer under 6 hours old
    unless `fresh` asks for a new one, otherwise a lookup. None when the
    registry gave no answer, now or within the offline backoff window."""
    now = time.time() if now is None else now
    if not fresh:
        cached = store.cached_newest(arch, now)
        if cached is not None:
            return cached
    if store.offline(now):
        return None
    try:
        answer = registry_newest(arch, transport=transport)
    except RegistryUnavailable:
        store.remember_offline(now)
        return None
    store.remember_registry(arch, answer, now)
    return answer


def _listed_for(entry: object, arch: str) -> Listed | None:
    """A tag-listing entry as a version tag with an image for `arch`."""
    if not isinstance(entry, dict):
        return None
    tag = entry.get("name")
    if not isinstance(tag, str) or ref_from_tag(tag) is None:
        return None
    images = entry.get("images")
    if not isinstance(images, list):
        return None
    for image in images:
        if isinstance(image, dict) and image.get("architecture") == arch:
            size = image.get("size")
            return Listed(tag, size if isinstance(size, int) and size > 0 else None)
    return None
