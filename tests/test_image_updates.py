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
