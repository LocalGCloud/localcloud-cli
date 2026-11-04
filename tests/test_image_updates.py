from __future__ import annotations

import json

import pytest

from localcloud_cli import image_updates
from localcloud_cli.config import parse_image_tag
from localcloud_cli.image_updates import ImageRef, newest, parse_ref, ref_from_tag


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
    store.remember_registry("arm64", {"lite": "0.1.11-lite", "full": "0.1.11"}, NOW)
    assert store.cached_newest("arm64", NOW + 60) == {"lite": "0.1.11-lite", "full": "0.1.11"}
    saved = json.loads(store.path.read_text())
    assert saved["schema_version"] == image_updates.STATE_SCHEMA_VERSION


def test_registry_answer_expires_and_is_per_architecture(tmp_path) -> None:
    store = _store(tmp_path)
    store.remember_registry("arm64", {"lite": "0.1.11-lite"}, NOW)
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
    store.remember_registry("arm64", {"lite": "0.1.11-lite"}, NOW)
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
