from __future__ import annotations

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
