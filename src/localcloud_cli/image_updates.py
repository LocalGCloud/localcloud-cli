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
import platform
import re
import tempfile
import time
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Literal, Protocol

import httpx

from .config import HostPaths, LocalCloudConfig, parse_image_tag
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

CHECK_ENV = "LOCALCLOUD_IMAGE_UPDATE_CHECK"
_FALSE = {"0", "false", "no", "off"}

Channel = Literal["lite", "full"]
Action = Literal["accepted", "adopted", "declined", "reported"]
# Shows lines and a question, returns the answer typed.
Ask = Callable[[list[str], str], str]


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
    notify: Callable[[str], None] | None = None,
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
    if notify is not None:
        notify("Checking Docker Hub for a newer LocalCloud image…")
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


# --- Discovery ----------------------------------------------------------------


class Engine(Protocol):
    """What discovery needs from Docker (DockerRuntime provides it)."""

    def local_image_tags(self, repository: str) -> set[str]: ...

    def engine_architecture(self) -> str | None: ...


@dataclass(frozen=True)
class Candidate:
    """A newer image to offer: where it was found, whether the engine already
    holds it (then accepting it pulls nothing), and its download size."""

    ref: ImageRef
    source: Literal["registry", "local"]
    local: bool
    size: int | None = None


def discover(
    current: ImageRef,
    engine: Engine,
    store: UpdateStore,
    *,
    registry: bool = True,
    fresh: bool = False,
    now: float | None = None,
    transport: httpx.BaseTransport | None = None,
    notify: Callable[[str], None] | None = None,
) -> Candidate | None:
    """The newest image on `current`'s channel, if it is newer than `current`.

    The registry is asked first (unless `registry` is False, for --no-pull);
    the engine's image store is searched only when the registry gave no
    answer, so an offline user who loaded a newer image still finds it."""
    arch = hub_architecture(engine.engine_architecture()) or hub_architecture(
        platform.machine()
    )
    answer = (
        registry_answer(store, arch, fresh=fresh, now=now, transport=transport, notify=notify)
        if registry and arch is not None
        else None
    )
    if answer is not None:
        listed = answer.get(current.channel)
        ref = newest(current, [listed.tag]) if listed is not None else None
        if ref is None:
            return None
        local = ref.tag in engine.local_image_tags(current.repository)
        return Candidate(ref, "registry", local, listed.size if listed else None)
    ref = newest(current, engine.local_image_tags(current.repository))
    return Candidate(ref, "local", True) if ref is not None else None


# --- Decision -----------------------------------------------------------------


def enabled(environment: Mapping[str, str] | None = None) -> bool:
    """Off when LOCALCLOUD_IMAGE_UPDATE_CHECK is false in the host environment
    or in host.environment."""
    for source in (os.environ, environment or {}):
        if str(source.get(CHECK_ENV, "")).strip().lower() in _FALSE:
            return False
    return True


def consider(
    config: LocalCloudConfig,
    engine: Engine,
    *,
    pull_explicit: bool = False,
    no_pull: bool = False,
    dry_run: bool = False,
    ask: Ask | None = None,
    store: UpdateStore | None = None,
    now: float | None = None,
    transport: httpx.BaseTransport | None = None,
    notify: Callable[[str], None] | None = None,
) -> tuple[LocalCloudConfig, dict[str, Any] | None]:
    """Before start or restart: `config`, switched to a newer image when the
    user consents, and the `image_update` result field (None: nothing to say).

    `pull_explicit` is an explicit --pull (it adopts the newest image without
    asking), `no_pull` an explicit --no-pull (local images only), and `ask`
    prompts the user; None means this run cannot prompt, so a newer image is
    only reported. `notify` hears about a registry request before it is made.
    Never raises: a failed lookup means nothing to offer.
    """
    if dry_run or not enabled(config.environment):
        return config, None
    if config.image_source not in {"runtime", "config", "environment"}:
        return config, None
    current = parse_ref(config.image)
    if current is None:
        return config, None
    store = store or UpdateStore(HostPaths.from_environment().home / STATE_FILE)
    try:
        candidate = discover(
            current,
            engine,
            store,
            registry=not no_pull,
            fresh=pull_explicit,
            now=now,
            transport=transport,
            notify=notify,
        )
    except Exception:  # The offer is a convenience; it never fails a command.
        return config, None
    if candidate is None:
        return config, None

    offered = candidate.ref
    if config.image_source != "runtime":
        return config, _update(config, current, candidate, "reported")
    if pull_explicit:
        return _switched(config, offered), _update(
            config, current, candidate, "adopted"
        )
    if store.declined(config.data_volume, offered.channel) == offered.tag:
        return config, None
    if ask is None:
        return config, _update(config, current, candidate, "reported")
    answer = ask(
        offer_lines(config, current, candidate, no_pull=no_pull),
        f"Use {offered.tag} now? [y/N] ",
    )
    if answer.strip().lower() in {"y", "yes"}:
        return _switched(config, offered), _update(
            config, current, candidate, "accepted"
        )
    store.remember_declined(config.data_volume, offered.channel, offered.tag)
    return config, _update(config, current, candidate, "declined")


def offer_lines(
    config: LocalCloudConfig, current: ImageRef, candidate: Candidate, *, no_pull: bool = False
) -> list[str]:
    """The prompt's explanation, above its question."""
    width = max(len(current.image), len(candidate.ref.image))
    return [
        "A newer LocalCloud image is available for this runtime.",
        f"  Current  {current.image:<{width}}  "
        f"(the image data volume {config.data_volume!r} last ran)",
        f"  Offered  {candidate.ref.image:<{width}}  ({_whereabouts(candidate, no_pull)})",
        "The runtime is recreated on the new image and its data volume is migrated "
        "forward, so going back may not be possible.",
    ]


def _switched(config: LocalCloudConfig, offered: ImageRef) -> LocalCloudConfig:
    # "upgrade" is not "default", so the controller keeps it instead of the
    # running container's image, and the run records it for later runs.
    return replace(config, image=offered.image, image_source="upgrade")


def _whereabouts(candidate: Candidate, no_pull: bool) -> str:
    if candidate.source == "local":
        reason = "--no-pull skips Docker Hub" if no_pull else "Docker Hub could not be reached"
        return f"found locally; {reason}"
    if candidate.local:
        return "Docker Hub; already available locally"
    size = f", {_format_size(candidate.size)}" if candidate.size else ""
    return f"Docker Hub; will be pulled{size}"


def _update(
    config: LocalCloudConfig, current: ImageRef, candidate: Candidate, action: Action
) -> dict[str, Any]:
    offered = candidate.ref
    switch = f"lc restart --image {offered.image}"
    reason: str | None = None
    if config.image_source == "config":
        where = f" in {config.config_path}" if config.config_path is not None else ""
        reason = f"host.image{where} pins {current.tag}"
        switch = f"set host.image to {offered.image}{where}"
    elif config.image_source == "environment":
        reason = f"LOCALCLOUD_IMAGE pins {current.tag}"
        switch = f"set LOCALCLOUD_IMAGE={offered.image}"
    if action == "adopted":
        summary = (
            f"switched from {current.tag} to {offered.tag}: --pull takes the newest "
            f"{offered.channel} image"
        )
    elif action == "accepted":
        summary = f"switched from {current.tag} to {offered.tag}"
    elif action == "declined":
        summary = (
            f"kept {current.tag}; {offered.tag} is not offered again for data volume "
            f"{config.data_volume!r}"
        )
    elif reason is not None:
        summary = f"{offered.tag} is available, but {reason}; {switch}"
    else:
        summary = (
            f"{offered.tag} is available (this runtime runs {current.tag}); "
            f"switch with: {switch}"
        )
    return {
        "current": current.image,
        "available": offered.image,
        "source": candidate.source,
        "local": candidate.local,
        "action": action,
        "reason": reason,
        "switch": switch,
        "summary": summary,
    }


def _format_size(size: int) -> str:
    amount = float(size)
    for unit in ("B", "KB", "MB", "GB"):
        if amount < 1000 or unit == "GB":
            break
        amount /= 1000
    return f"{amount:.0f} {unit}" if unit in {"B", "KB"} else f"{amount:.1f} {unit}"
