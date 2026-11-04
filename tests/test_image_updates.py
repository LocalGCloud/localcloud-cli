from __future__ import annotations

import json

import httpx
import pytest

from localcloud_cli import image_updates
from localcloud_cli.config import parse_image_tag
from localcloud_cli.image_updates import (
    ImageRef,
    Listed,
    RegistryUnavailable,
    newest,
    parse_ref,
    ref_from_tag,
    registry_newest,
)


# --- Tags and versions ------------------------------------------------------


def test_parse_image_tag_names_repository_and_tag() -> None:
    assert parse_image_tag("agentcloud/localcloud:0.1.8-lite") == (
        "agentcloud/localcloud",
        "0.1.8-lite",
    )
    assert parse_image_tag("agentcloud/localcloud") == ("agentcloud/localcloud", "latest")
    assert parse_image_tag("localhost:5000/localcloud") == (
        "localhost:5000/localcloud",
        "latest",
    )
    assert parse_image_tag("agentcloud/localcloud@sha256:abc") is None
    assert parse_image_tag("agentcloud/localcloud:0.1.8@sha256:abc") is None
    assert parse_image_tag("sha256:abc") is None


@pytest.mark.parametrize(
    ("image", "version", "lite"),
    [
        ("agentcloud/localcloud:0.1.8-lite", (0, 1, 8), True),
        ("agentcloud/localcloud:0.1.11", (0, 1, 11), False),
        ("docker.io/agentcloud/localcloud:2.0.0", (2, 0, 0), False),
    ],
)
def test_parse_ref_reads_version_tags(
    image: str, version: tuple[int, int, int], lite: bool
) -> None:
    ref = parse_ref(image)
    assert ref is not None
    assert (ref.version, ref.lite) == (version, lite)
    assert ref.image == image


@pytest.mark.parametrize(
    "image",
    [
        "agentcloud/localcloud:lite",
        "agentcloud/localcloud:latest",
        "agentcloud/localcloud",
        "agentcloud/localcloud:0.1",
        "agentcloud/localcloud:0.1.8-rc1",
        "agentcloud/localcloud:v0.1.8",
        "agentcloud/localcloud:0.1.8-lite@sha256:abc",
        "agentcloud/localcloud@sha256:abc",
        "sha256:abc",
        "example/localcloud:0.1.8-lite",
        "registry.example.com/agentcloud/localcloud:0.1.8",
    ],
)
def test_parse_ref_rejects_what_is_never_offered(image: str) -> None:
    assert parse_ref(image) is None


def test_versions_compare_numerically() -> None:
    current = parse_ref("agentcloud/localcloud:0.1.9-lite")
    assert current is not None
    assert newest(current, ["0.1.10-lite", "0.1.8-lite"]) == ref_from_tag("0.1.10-lite")


def test_newest_stays_on_the_channel() -> None:
    lite = parse_ref("agentcloud/localcloud:0.1.8-lite")
    full = parse_ref("agentcloud/localcloud:0.1.8")
    assert lite is not None and full is not None
    tags = ["0.1.11", "0.1.10-lite", "lite", "latest", "0.1"]
    assert newest(lite, tags) == ref_from_tag("0.1.10-lite")
    assert newest(full, tags) == ref_from_tag("0.1.11")


def test_newest_needs_a_strictly_newer_version() -> None:
    current = parse_ref("agentcloud/localcloud:0.1.11-lite")
    assert current is not None
    assert newest(current, ["0.1.11-lite", "0.1.9-lite"]) is None
    assert newest(current, []) is None


def test_newest_offers_a_major_version_like_any_other() -> None:
    current = parse_ref("agentcloud/localcloud:0.1.11")
    assert current is not None
    assert newest(current, ["1.0.0", "0.2.0"]) == ref_from_tag("1.0.0")


def test_newest_keeps_the_repository_spelling() -> None:
    current = parse_ref("docker.io/agentcloud/localcloud:0.1.8-lite")
    assert current is not None
    offered = newest(current, ["0.1.11-lite"])
    assert offered is not None
    assert offered.image == "docker.io/agentcloud/localcloud:0.1.11-lite"


def test_image_ref_names_its_channel() -> None:
    assert ImageRef(image_updates.REPOSITORY, (0, 1, 8), True).channel == "lite"
    assert ImageRef(image_updates.REPOSITORY, (0, 1, 8), False).channel == "full"


# --- Remembered answers -----------------------------------------------------

NOW = 1_800_000_000.0


def _store(tmp_path) -> image_updates.UpdateStore:
    return image_updates.UpdateStore(tmp_path / "home" / image_updates.STATE_FILE)


def test_store_round_trips_a_registry_answer(tmp_path) -> None:
    store = _store(tmp_path)
    assert store.cached_newest("arm64", NOW) is None
    newest = {"lite": Listed("0.1.11-lite", 373_125_517), "full": Listed("0.1.11")}
    store.remember_registry("arm64", newest, NOW)
    assert store.cached_newest("arm64", NOW + 60) == newest
    saved = json.loads(store.path.read_text())
    assert saved["schema_version"] == image_updates.STATE_SCHEMA_VERSION


def test_registry_answer_expires_and_is_per_architecture(tmp_path) -> None:
    store = _store(tmp_path)
    store.remember_registry("arm64", {"lite": Listed("0.1.11-lite")}, NOW)
    assert store.cached_newest("amd64", NOW) is None
    assert store.cached_newest("arm64", NOW + image_updates.CACHE_SECONDS) is None
    # A clock that moved backwards does not keep an answer forever.
    assert store.cached_newest("arm64", NOW - 1) is None


def test_offline_backoff_lasts_its_window_and_a_success_clears_it(tmp_path) -> None:
    store = _store(tmp_path)
    assert not store.offline(NOW)
    store.remember_offline(NOW)
    assert store.offline(NOW + image_updates.OFFLINE_BACKOFF_SECONDS - 1)
    assert not store.offline(NOW + image_updates.OFFLINE_BACKOFF_SECONDS)
    store.remember_registry("arm64", {}, NOW + 1)
    assert not store.offline(NOW + 2)


def test_declines_are_per_data_volume_and_channel(tmp_path) -> None:
    store = _store(tmp_path)
    store.remember_declined("localcloud-data", "lite", "0.1.11-lite")
    store.remember_declined("other-data", "full", "0.1.11")
    assert store.declined("localcloud-data", "lite") == "0.1.11-lite"
    assert store.declined("localcloud-data", "full") is None
    assert store.declined("other-data", "full") == "0.1.11"
    store.remember_declined("localcloud-data", "lite", "0.1.12-lite")
    assert store.declined("localcloud-data", "lite") == "0.1.12-lite"


@pytest.mark.parametrize(
    "content",
    [
        "{not json",
        "[]",
        json.dumps({"schema_version": 99, "offline_until": "2999-01-01T00:00:00+00:00"}),
        json.dumps(
            {
                "schema_version": 1,
                "registry": {"checked_at": 5, "arch": "arm64", "newest": []},
                "offline_until": None,
            }
        ),
        json.dumps(
            {
                "schema_version": 1,
                "registry": {
                    "checked_at": "not a date",
                    "arch": "arm64",
                    "newest": {"lite": "0.1.11-lite"},
                },
                "offline_until": "yesterday",
                "declined": {"localcloud-data": ["0.1.11-lite"]},
            }
        ),
    ],
)
def test_unreadable_state_reads_as_empty(tmp_path, content: str) -> None:
    store = _store(tmp_path)
    store.path.parent.mkdir(parents=True)
    store.path.write_text(content)
    assert store.cached_newest("arm64", NOW) is None
    assert not store.offline(NOW)
    assert store.declined("localcloud-data", "lite") is None
    # Writing over it works and leaves a valid file.
    store.remember_declined("localcloud-data", "lite", "0.1.11-lite")
    assert store.declined("localcloud-data", "lite") == "0.1.11-lite"


def test_unwritable_home_never_fails(tmp_path) -> None:
    blocker = tmp_path / "home"
    blocker.write_text("a file where the directory should be")
    store = image_updates.UpdateStore(blocker / image_updates.STATE_FILE)
    store.remember_offline(NOW)
    store.remember_registry("arm64", {"lite": Listed("0.1.11-lite")}, NOW)
    store.remember_declined("localcloud-data", "lite", "0.1.11-lite")
    assert not store.offline(NOW)
    assert store.declined("localcloud-data", "lite") is None


def test_writes_leave_no_temporary_files(tmp_path) -> None:
    store = _store(tmp_path)
    store.remember_offline(NOW)
    store.remember_declined("localcloud-data", "lite", "0.1.11-lite")
    assert sorted(path.name for path in store.path.parent.iterdir()) == [
        image_updates.STATE_FILE
    ]


def test_cached_answer_skips_entries_of_another_shape(tmp_path) -> None:
    store = _store(tmp_path)
    store.path.parent.mkdir(parents=True)
    store.path.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "registry": {
                    "checked_at": image_updates._stamp(NOW),
                    "arch": "arm64",
                    "newest": {"lite": "0.1.11-lite", "full": {"tag": "0.1.11", "size": "1"}},
                },
            }
        )
    )
    assert store.cached_newest("arm64", NOW) == {"full": Listed("0.1.11")}


# --- Registry ---------------------------------------------------------------


def _tag(name: str, *architectures: str, size: int = 100) -> dict[str, object]:
    return {
        "name": name,
        "images": [
            {"architecture": arch, "os": "linux", "size": size} for arch in architectures
        ],
    }


def _page(*tags: dict[str, object], next_url: str | None = None) -> dict[str, object]:
    return {"count": len(tags), "next": next_url, "results": list(tags)}


@pytest.fixture
def hub():
    import respx

    with respx.mock(assert_all_called=False) as router:
        yield router


def test_registry_newest_per_channel_for_the_architecture(hub) -> None:
    hub.get(image_updates.REGISTRY_TAGS_URL).respond(
        json=_page(
            _tag("lite", "arm64", "amd64"),
            _tag("latest", "arm64", "amd64"),
            _tag("0.1", "arm64"),
            _tag("0.1.12-lite", "amd64"),
            _tag("0.1.11-lite", "arm64", size=373),
            _tag("0.1.9-lite", "arm64", "amd64"),
            _tag("0.1.11", "arm64", size=506),
            _tag("0.1.10", "arm64", "amd64"),
            {"name": "0.1.13", "images": "not a list"},
            "not an entry",
        )
    )
    assert registry_newest("arm64") == {
        "lite": Listed("0.1.11-lite", 373),
        "full": Listed("0.1.11", 506),
    }
    assert registry_newest("amd64") == {
        "lite": Listed("0.1.12-lite", 100),
        "full": Listed("0.1.10", 100),
    }


def test_registry_newest_follows_pages_up_to_the_limit(hub) -> None:
    base = "https://hub.docker.com/v2/repositories/agentcloud/localcloud/tags"
    hub.get(image_updates.REGISTRY_TAGS_URL).respond(
        json=_page(_tag("0.1.8-lite", "arm64"), next_url=f"{base}?page=2")
    )
    hub.get(f"{base}?page=2").respond(
        json=_page(_tag("0.1.9-lite", "arm64"), next_url=f"{base}?page=3")
    )
    hub.get(f"{base}?page=3").respond(
        json=_page(_tag("0.1.10-lite", "arm64"), next_url=f"{base}?page=4")
    )
    page_four = hub.get(f"{base}?page=4").respond(json=_page(_tag("0.1.11-lite", "arm64")))
    assert registry_newest("arm64") == {"lite": Listed("0.1.10-lite", 100)}
    assert not page_four.called


def test_registry_with_no_matching_tags_is_an_empty_answer(hub) -> None:
    hub.get(image_updates.REGISTRY_TAGS_URL).respond(json=_page())
    assert registry_newest("arm64") == {}


@pytest.mark.parametrize(
    "response",
    [
        {"status_code": 503},
        {"status_code": 404},
        {"content": b"<html>not json</html>"},
        {"json": {"detail": "no results key"}},
        {"json": ["a", "list"]},
    ],
)
def test_registry_without_a_usable_answer_is_unavailable(hub, response) -> None:
    hub.get(image_updates.REGISTRY_TAGS_URL).respond(**response)
    with pytest.raises(RegistryUnavailable):
        registry_newest("arm64")


@pytest.mark.parametrize(
    "error", [httpx.ConnectTimeout("slow"), httpx.ConnectError("dns"), httpx.ReadTimeout("slow")]
)
def test_registry_network_failures_are_unavailable(hub, error) -> None:
    hub.get(image_updates.REGISTRY_TAGS_URL).mock(side_effect=error)
    with pytest.raises(RegistryUnavailable):
        registry_newest("arm64")


def test_registry_lookup_uses_bounded_timeouts(hub) -> None:
    route = hub.get(image_updates.REGISTRY_TAGS_URL).respond(json=_page())
    registry_newest("arm64")
    timeout = route.calls.last.request.extensions["timeout"]
    assert timeout["connect"] == image_updates.REGISTRY_CONNECT_TIMEOUT
    assert timeout["read"] <= image_updates.REGISTRY_TOTAL_TIMEOUT


def test_registry_lookup_stops_at_the_total_deadline(hub) -> None:
    base = "https://hub.docker.com/v2/repositories/agentcloud/localcloud/tags"
    hub.get(image_updates.REGISTRY_TAGS_URL).respond(
        json=_page(_tag("0.1.8-lite", "arm64"), next_url=f"{base}?page=2")
    )
    page_two = hub.get(f"{base}?page=2").respond(json=_page())
    ticks = iter([0.0, 0.1, image_updates.REGISTRY_TOTAL_TIMEOUT + 0.1])
    with pytest.raises(RegistryUnavailable):
        registry_newest("arm64", clock=lambda: next(ticks))
    assert not page_two.called


@pytest.mark.parametrize(
    ("machine", "arch"),
    [("x86_64", "amd64"), ("AMD64", "amd64"), ("aarch64", "arm64"), ("arm64", "arm64"),
     ("riscv64", None), ("", None), (None, None)],
)
def test_hub_architecture_names(machine, arch) -> None:
    assert image_updates.hub_architecture(machine) == arch


def test_registry_answer_is_cached(hub, tmp_path) -> None:
    store = _store(tmp_path)
    route = hub.get(image_updates.REGISTRY_TAGS_URL).respond(
        json=_page(_tag("0.1.11-lite", "arm64"))
    )
    first = image_updates.registry_answer(store, "arm64", now=NOW)
    second = image_updates.registry_answer(store, "arm64", now=NOW + 60)
    assert first == second == {"lite": Listed("0.1.11-lite", 100)}
    assert route.call_count == 1
    # An explicit --pull asks again; an expired answer does too.
    image_updates.registry_answer(store, "arm64", fresh=True, now=NOW + 120)
    image_updates.registry_answer(store, "arm64", now=NOW + 120 + image_updates.CACHE_SECONDS)
    assert route.call_count == 3


def test_failed_lookup_backs_off(hub, tmp_path) -> None:
    store = _store(tmp_path)
    route = hub.get(image_updates.REGISTRY_TAGS_URL).mock(
        side_effect=httpx.ConnectError("offline")
    )
    assert image_updates.registry_answer(store, "arm64", now=NOW) is None
    assert image_updates.registry_answer(store, "arm64", fresh=True, now=NOW + 60) is None
    assert route.call_count == 1
    route.mock(side_effect=None, return_value=httpx.Response(200, json=_page()))
    later = NOW + image_updates.OFFLINE_BACKOFF_SECONDS
    assert image_updates.registry_answer(store, "arm64", now=later) == {}
    assert route.call_count == 2
    assert not store.offline(later)


def test_cached_answer_survives_the_backoff(hub, tmp_path) -> None:
    store = _store(tmp_path)
    store.remember_registry("arm64", {"lite": Listed("0.1.11-lite")}, NOW)
    store.remember_offline(NOW + 1)
    route = hub.get(image_updates.REGISTRY_TAGS_URL).respond(json=_page())
    assert image_updates.registry_answer(store, "arm64", now=NOW + 2) == {
        "lite": Listed("0.1.11-lite")
    }
    assert not route.called


# --- Discovery --------------------------------------------------------------


class FakeEngine:
    def __init__(self, tags=(), arch: str | None = "aarch64") -> None:
        self.tags = set(tags)
        self.arch = arch
        self.listed: list[str] = []

    def local_image_tags(self, repository: str) -> set[str]:
        self.listed.append(repository)
        return set(self.tags)

    def engine_architecture(self) -> str | None:
        return self.arch


def _current(tag: str = "0.1.8-lite") -> ImageRef:
    ref = parse_ref(f"agentcloud/localcloud:{tag}")
    assert ref is not None
    return ref


def test_discover_offers_the_registry_newest_and_says_it_needs_a_pull(hub, tmp_path) -> None:
    hub.get(image_updates.REGISTRY_TAGS_URL).respond(
        json=_page(_tag("0.1.11-lite", "arm64", size=373), _tag("0.1.11", "arm64"))
    )
    engine = FakeEngine(tags={"0.1.8-lite", "0.1.9-lite"})
    candidate = image_updates.discover(_current(), engine, _store(tmp_path), now=NOW)
    assert candidate == image_updates.Candidate(
        ref_from_tag("0.1.11-lite"), "registry", local=False, size=373
    )


def test_discover_marks_a_registry_newest_already_local(hub, tmp_path) -> None:
    hub.get(image_updates.REGISTRY_TAGS_URL).respond(json=_page(_tag("0.1.11-lite", "arm64")))
    engine = FakeEngine(tags={"0.1.8-lite", "0.1.11-lite"})
    candidate = image_updates.discover(_current(), engine, _store(tmp_path), now=NOW)
    assert candidate is not None
    assert (candidate.source, candidate.local) == ("registry", True)


def test_discover_does_not_search_locally_when_the_registry_answered(hub, tmp_path) -> None:
    # The registry is the authority: a newer local tag it does not list (a
    # local build, or a tag without this engine's architecture) is not offered.
    hub.get(image_updates.REGISTRY_TAGS_URL).respond(json=_page(_tag("0.1.8-lite", "arm64")))
    engine = FakeEngine(tags={"0.1.12-lite"})
    assert image_updates.discover(_current(), engine, _store(tmp_path), now=NOW) is None
    assert engine.listed == []


def test_discover_skips_a_registry_tag_without_the_engine_architecture(hub, tmp_path) -> None:
    hub.get(image_updates.REGISTRY_TAGS_URL).respond(
        json=_page(_tag("0.1.11-lite", "arm64"), _tag("0.1.9-lite", "amd64", "arm64"))
    )
    engine = FakeEngine(arch="x86_64")
    candidate = image_updates.discover(_current(), engine, _store(tmp_path), now=NOW)
    assert candidate is not None and candidate.ref.tag == "0.1.9-lite"


def test_discover_falls_back_to_local_images_when_offline(hub, tmp_path) -> None:
    hub.get(image_updates.REGISTRY_TAGS_URL).mock(side_effect=httpx.ConnectError("offline"))
    engine = FakeEngine(tags={"0.1.8-lite", "0.1.10-lite", "0.1.12", "lite"})
    store = _store(tmp_path)
    candidate = image_updates.discover(_current(), engine, store, now=NOW)
    assert candidate == image_updates.Candidate(ref_from_tag("0.1.10-lite"), "local", local=True)
    assert store.offline(NOW + 1)


def test_discover_with_registry_off_never_calls_it(hub, tmp_path) -> None:
    route = hub.get(image_updates.REGISTRY_TAGS_URL).respond(json=_page())
    engine = FakeEngine(tags={"0.1.10-lite"})
    candidate = image_updates.discover(
        _current(), engine, _store(tmp_path), registry=False, now=NOW
    )
    assert candidate is not None and candidate.source == "local"
    assert not route.called


def test_discover_without_a_known_architecture_searches_locally(
    hub, tmp_path, monkeypatch
) -> None:
    monkeypatch.setattr(image_updates.platform, "machine", lambda: "riscv64")
    route = hub.get(image_updates.REGISTRY_TAGS_URL).respond(json=_page())
    engine = FakeEngine(tags={"0.1.10-lite"}, arch=None)
    candidate = image_updates.discover(_current(), engine, _store(tmp_path), now=NOW)
    assert candidate is not None and candidate.source == "local"
    assert not route.called


def test_discover_uses_the_host_architecture_when_docker_cannot_say(
    hub, tmp_path, monkeypatch
) -> None:
    monkeypatch.setattr(image_updates.platform, "machine", lambda: "x86_64")
    hub.get(image_updates.REGISTRY_TAGS_URL).respond(
        json=_page(_tag("0.1.11-lite", "arm64"), _tag("0.1.9-lite", "amd64"))
    )
    candidate = image_updates.discover(_current(), FakeEngine(arch=None), _store(tmp_path), now=NOW)
    assert candidate is not None and candidate.ref.tag == "0.1.9-lite"


def test_discover_nothing_newer(hub, tmp_path) -> None:
    hub.get(image_updates.REGISTRY_TAGS_URL).respond(json=_page(_tag("0.1.11-lite", "arm64")))
    assert (
        image_updates.discover(_current("0.1.11-lite"), FakeEngine(), _store(tmp_path), now=NOW)
        is None
    )
    # A current tag newer than anything the registry lists is not downgraded.
    assert (
        image_updates.discover(_current("0.1.12-lite"), FakeEngine(), _store(tmp_path), now=NOW)
        is None
    )
    # A channel the registry has no tag for offers nothing.
    assert image_updates.discover(_current("0.1.8"), FakeEngine(), _store(tmp_path), now=NOW) is None


# --- Decision ---------------------------------------------------------------

OFFERED = "agentcloud/localcloud:0.1.11-lite"


@pytest.fixture
def check_on(monkeypatch) -> None:
    monkeypatch.delenv(image_updates.CHECK_ENV, raising=False)


def _config(tmp_path, image="agentcloud/localcloud:0.1.8-lite", source="runtime", **changes):
    from dataclasses import replace

    from localcloud_cli.config import HostPaths, load_config

    home = tmp_path / "lc-home"
    config = load_config(
        directory=tmp_path, paths=HostPaths(home, home / "locks"), active_runtime=None
    )
    return replace(config, image=image, image_source=source, **changes)


class Prompt:
    def __init__(self, answer: str) -> None:
        self.answer = answer
        self.asked: list[tuple[list[str], str]] = []

    def __call__(self, lines: list[str], question: str) -> str:
        self.asked.append((lines, question))
        return self.answer


def _consider(tmp_path, config, *, engine=None, store=None, **kwargs):
    return image_updates.consider(
        config,
        engine or FakeEngine(tags={"0.1.8-lite"}),
        store=store or _store(tmp_path),
        now=NOW,
        **kwargs,
    )


@pytest.fixture
def newer_on_hub(hub):
    return hub.get(image_updates.REGISTRY_TAGS_URL).respond(
        json=_page(_tag("0.1.11-lite", "arm64", size=1_500_000_000), _tag("0.1.11", "arm64"))
    )


@pytest.mark.usefixtures("check_on")
@pytest.mark.parametrize(
    ("image", "source", "kwargs"),
    [
        # --dry-run: read-only and offline.
        ("agentcloud/localcloud:0.1.8-lite", "runtime", {"dry_run": True}),
        # An image chosen this run, or none chosen at all, is not second-guessed.
        ("agentcloud/localcloud:0.1.8-lite", "flag", {}),
        ("agentcloud/localcloud:0.1.8-lite", "default", {}),
        ("agentcloud/localcloud:0.1.8-lite", "upgrade", {}),
        # Floating tags, digests and other repositories are never touched.
        ("agentcloud/localcloud:lite", "runtime", {}),
        ("agentcloud/localcloud:latest", "runtime", {}),
        ("agentcloud/localcloud:0.1.8-lite@sha256:abc", "runtime", {}),
        ("example/localcloud:0.1.8-lite", "runtime", {}),
    ],
)
def test_consider_makes_no_offer(tmp_path, newer_on_hub, image, source, kwargs) -> None:
    prompt = Prompt("y")
    config = _config(tmp_path, image=image, source=source)
    assert _consider(tmp_path, config, ask=prompt, **kwargs) == (config, None)
    assert not prompt.asked
    assert not newer_on_hub.called


@pytest.mark.parametrize(
    "environment",
    [{"host": {image_updates.CHECK_ENV: "false"}}, {"config": {image_updates.CHECK_ENV: "off"}}],
)
def test_consider_can_be_turned_off(tmp_path, monkeypatch, newer_on_hub, environment) -> None:
    monkeypatch.delenv(image_updates.CHECK_ENV, raising=False)
    for name, value in environment.get("host", {}).items():
        monkeypatch.setenv(name, value)
    config = _config(tmp_path, environment=environment.get("config", {}))
    engine = FakeEngine(tags={"0.1.11-lite"})
    assert _consider(tmp_path, config, engine=engine, ask=Prompt("y")) == (config, None)
    assert not newer_on_hub.called
    assert engine.listed == []


@pytest.mark.usefixtures("check_on")
def test_consider_asks_and_switches_on_yes(tmp_path, newer_on_hub) -> None:
    prompt = Prompt("y")
    config = _config(tmp_path)
    chosen, update = _consider(tmp_path, config, ask=prompt)
    assert (chosen.image, chosen.image_source) == (OFFERED, "upgrade")
    assert chosen.config_hash != config.config_hash
    assert update is not None
    assert update["action"] == "accepted"
    assert update["current"] == "agentcloud/localcloud:0.1.8-lite"
    assert update["available"] == OFFERED
    assert (update["source"], update["local"]) == ("registry", False)
    [(lines, question)] = prompt.asked
    assert question == "Use 0.1.11-lite now? [y/N] "
    text = "\n".join(lines)
    assert "agentcloud/localcloud:0.1.8-lite" in text and OFFERED in text
    assert "will be pulled, 1.5 GB" in text
    assert "migrated forward, so going back may not be possible" in text
    assert "data volume 'localcloud-data' last ran" in text


@pytest.mark.usefixtures("check_on")
def test_consider_says_when_the_offered_image_is_already_local(tmp_path, newer_on_hub) -> None:
    prompt = Prompt("yes")
    engine = FakeEngine(tags={"0.1.8-lite", "0.1.11-lite"})
    _chosen, update = _consider(tmp_path, _config(tmp_path), engine=engine, ask=prompt)
    assert update is not None and update["local"] is True
    assert "Docker Hub; already available locally" in "\n".join(prompt.asked[0][0])


@pytest.mark.usefixtures("check_on")
@pytest.mark.parametrize("answer", ["", "n", "no", "maybe"])
def test_consider_keeps_the_image_on_no_and_remembers(tmp_path, newer_on_hub, answer) -> None:
    store = _store(tmp_path)
    config = _config(tmp_path)
    chosen, update = _consider(tmp_path, config, store=store, ask=Prompt(answer))
    assert chosen == config
    assert update is not None and update["action"] == "declined"
    assert store.declined("localcloud-data", "lite") == "0.1.11-lite"
    # Not asked again for the same version on that data volume...
    again = Prompt("y")
    assert _consider(tmp_path, config, store=store, ask=again) == (config, None)
    assert not again.asked
    # ...but asked on another data volume.
    other = _config(tmp_path, data_volume="other-data")
    _consider(tmp_path, other, store=store, ask=again)
    assert len(again.asked) == 1


@pytest.mark.usefixtures("check_on")
def test_consider_asks_again_when_something_newer_appears(tmp_path, hub) -> None:
    store = _store(tmp_path)
    store.remember_declined("localcloud-data", "lite", "0.1.11-lite")
    hub.get(image_updates.REGISTRY_TAGS_URL).respond(json=_page(_tag("0.1.12-lite", "arm64")))
    prompt = Prompt("n")
    _chosen, update = _consider(tmp_path, _config(tmp_path), store=store, ask=prompt)
    assert prompt.asked and update is not None
    assert store.declined("localcloud-data", "lite") == "0.1.12-lite"


@pytest.mark.usefixtures("check_on")
def test_explicit_pull_adopts_without_asking_and_asks_the_registry_afresh(
    tmp_path, newer_on_hub
) -> None:
    store = _store(tmp_path)
    store.remember_registry("arm64", {}, NOW)  # A fresh answer listing nothing newer.
    store.remember_declined("localcloud-data", "lite", "0.1.11-lite")
    prompt = Prompt("n")
    chosen, update = _consider(
        tmp_path, _config(tmp_path), store=store, ask=prompt, pull_explicit=True
    )
    assert (chosen.image, chosen.image_source) == (OFFERED, "upgrade")
    assert update is not None and update["action"] == "adopted"
    assert "--pull takes the newest lite image" in update["summary"]
    assert not prompt.asked
    assert newer_on_hub.called


@pytest.mark.usefixtures("check_on")
def test_consider_only_reports_when_it_cannot_ask(tmp_path, newer_on_hub) -> None:
    config = _config(tmp_path)
    chosen, update = _consider(tmp_path, config, ask=None)
    assert chosen == config
    assert update is not None
    assert update["action"] == "reported"
    assert update["switch"] == f"lc restart --image {OFFERED}"
    assert update["summary"] == (
        "0.1.11-lite is available (this runtime runs 0.1.8-lite); "
        f"switch with: lc restart --image {OFFERED}"
    )


@pytest.mark.usefixtures("check_on")
@pytest.mark.parametrize("pull_explicit", [False, True])
def test_a_config_or_environment_pin_is_only_reported(
    tmp_path, newer_on_hub, pull_explicit
) -> None:
    prompt = Prompt("y")
    pinned = _config(tmp_path, source="config", config_path=tmp_path / "localcloud.yaml")
    chosen, update = _consider(
        tmp_path, pinned, ask=prompt, pull_explicit=pull_explicit
    )
    assert chosen == pinned and not prompt.asked
    assert update is not None and update["action"] == "reported"
    assert update["reason"] == f"host.image in {tmp_path / 'localcloud.yaml'} pins 0.1.8-lite"
    assert update["switch"] == f"set host.image to {OFFERED} in {tmp_path / 'localcloud.yaml'}"

    environment = _config(tmp_path, source="environment")
    chosen, update = _consider(tmp_path, environment, ask=prompt, pull_explicit=pull_explicit)
    assert chosen == environment and not prompt.asked
    assert update is not None
    assert update["reason"] == "LOCALCLOUD_IMAGE pins 0.1.8-lite"
    assert update["switch"] == f"set LOCALCLOUD_IMAGE={OFFERED}"
    assert update["summary"].startswith("0.1.11-lite is available, but LOCALCLOUD_IMAGE pins")


@pytest.mark.usefixtures("check_on")
def test_no_pull_offers_local_images_without_the_registry(tmp_path, newer_on_hub) -> None:
    prompt = Prompt("y")
    engine = FakeEngine(tags={"0.1.8-lite", "0.1.10-lite"})
    chosen, update = _consider(tmp_path, _config(tmp_path), engine=engine, ask=prompt, no_pull=True)
    assert chosen.image == "agentcloud/localcloud:0.1.10-lite"
    assert update is not None and (update["source"], update["local"]) == ("local", True)
    assert "found locally; --no-pull skips Docker Hub" in "\n".join(prompt.asked[0][0])
    assert not newer_on_hub.called


@pytest.mark.usefixtures("check_on")
def test_offline_offers_a_newer_local_image(tmp_path, hub) -> None:
    hub.get(image_updates.REGISTRY_TAGS_URL).mock(side_effect=httpx.ConnectTimeout("slow"))
    prompt = Prompt("y")
    engine = FakeEngine(tags={"0.1.8-lite", "0.1.10-lite"})
    chosen, _update = _consider(tmp_path, _config(tmp_path), engine=engine, ask=prompt)
    assert chosen.image == "agentcloud/localcloud:0.1.10-lite"
    assert "found locally; Docker Hub could not be reached" in "\n".join(prompt.asked[0][0])


@pytest.mark.usefixtures("check_on")
def test_nothing_newer_says_nothing(tmp_path, newer_on_hub) -> None:
    prompt = Prompt("y")
    config = _config(tmp_path, image=OFFERED)
    assert _consider(tmp_path, config, ask=prompt) == (config, None)
    assert not prompt.asked


@pytest.mark.usefixtures("check_on")
def test_a_failing_engine_never_fails_the_command(tmp_path, newer_on_hub) -> None:
    class Broken:
        def engine_architecture(self):
            raise RuntimeError("daemon gone")

    config = _config(tmp_path)
    assert _consider(tmp_path, config, engine=Broken(), ask=Prompt("y")) == (config, None)


def test_sizes_read_naturally() -> None:
    assert image_updates._format_size(512) == "512 B"
    assert image_updates._format_size(373_125_517) == "373.1 MB"
    assert image_updates._format_size(1_500_000_000) == "1.5 GB"
