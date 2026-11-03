from __future__ import annotations

from localcloud_cli.service_sources import (
    describe_service_sources,
    unpublished_services,
)

_VOLUME = "localcloud-data"


def _state(service: str, source: str, *, enabled: bool = True, **extra: object) -> dict:
    return {"id": service, "enabled": enabled, "source": source, **extra}


def test_enabled_services_are_grouped_by_the_layer_that_decided_them() -> None:
    states = [
        _state("gcs", "profile-env"),
        _state("bigquery", "persisted"),
        _state("firestore", "persisted"),
        _state("pubsub", "yaml"),
        _state("kms", "default"),
        _state("spanner", "profile-env", enabled=False),
    ]

    rows = describe_service_sources(
        states, data_volume=_VOLUME, config_path="/work/localcloud.yaml"
    )

    # Highest rank first, so the first entry is what the command line decided.
    assert rows["service_sources"] == (
        "--services: gcs; "
        "saved setting (data volume localcloud-data): bigquery, firestore; "
        "services.enabled (localcloud.yaml): pubsub; "
        "image default: kms"
    )
    assert "saved_settings" not in rows


def test_a_saved_setting_that_the_command_line_overrides_is_named() -> None:
    states = [
        _state("gcs", "profile-env"),
        _state("bigquery", "profile-env", enabled=False, overridden_saved_enabled=True),
        _state("kms", "profile-env", enabled=True, overridden_saved_enabled=False),
    ]

    rows = describe_service_sources(states, data_volume=_VOLUME, config_path=None)

    assert rows["service_sources"] == "--services: gcs, kms"
    assert rows["saved_settings"] == (
        "not applied, bigquery (enabled), kms (disabled) overridden by --services; "
        "a start without that choice restores them"
    )


def test_nothing_is_described_when_the_runtime_did_not_say() -> None:
    assert describe_service_sources(None, data_volume=_VOLUME, config_path=None) == {}
    assert describe_service_sources([], data_volume=_VOLUME, config_path=None) == {}


def test_services_enabled_beyond_the_published_set_are_reported() -> None:
    states = [
        _state("gcs", "profile-env"),
        _state("bigquery", "persisted"),
    ]

    warning = unpublished_services(
        states, ("gcs",), data_volume=_VOLUME, config_path=None
    )

    assert warning is not None
    assert "bigquery (saved setting (data volume localcloud-data))" in warning
    assert "(gcs)" in warning
    assert unpublished_services(
        [_state("gcs", "profile-env")], ("gcs",), data_volume=_VOLUME, config_path=None
    ) is None
