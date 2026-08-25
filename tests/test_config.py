from __future__ import annotations

import hashlib
import json
import os
import threading
import time
from pathlib import Path

import pytest

import localcloud_cli.config as config_module
from localcloud_cli.config import (
    ACTIVE_RUNTIME_SCHEMA_VERSION,
    DEFAULT_DATA_VOLUME,
    DEFAULT_IMAGE,
    DEFAULT_PROJECT,
    DEFAULT_USER,
    ActiveRuntime,
    HostPaths,
    data_volume_lock,
    default_resource_names,
    load_active_runtime,
    load_config,
    save_active_runtime,
    validate_data_volume,
)
from localcloud_cli.errors import HostError


def _paths(tmp_path: Path) -> HostPaths:
    home = tmp_path / "home"
    return HostPaths(home, home / "locks")


def _active(
    data_volume: str = "localcloud-data-active",
    image: str = "registry.example/localcloud:active",
    container_id: str = "container-active",
    container_name: str | None = None,
    network_name: str | None = None,
) -> ActiveRuntime:
    names = default_resource_names(data_volume)
    return ActiveRuntime(
        schema_version=ACTIVE_RUNTIME_SCHEMA_VERSION,
        data_volume=data_volume,
        image=image,
        container_id=container_id,
        container_name=container_name or names["container"],
        network_name=network_name or names["network"],
    )


def test_zero_config_uses_shared_data_volume_defaults(tmp_path: Path) -> None:
    selected = load_config(directory=tmp_path, paths=_paths(tmp_path))

    assert selected.data_volume == DEFAULT_DATA_VOLUME
    assert selected.project == DEFAULT_PROJECT
    assert selected.user == DEFAULT_USER
    assert selected.container_name == "localcloud"
    assert selected.network_name == "localcloud"
    assert selected.config_path is None
    assert selected.diagnostics == ()


def test_default_image_uses_public_latest_channel(tmp_path: Path) -> None:
    selected = load_config(directory=tmp_path, paths=_paths(tmp_path))

    assert DEFAULT_IMAGE == "jaysen2apache/localcloud:latest"
    assert selected.image == DEFAULT_IMAGE


def test_data_volume_naming_is_stable_and_collision_resistant() -> None:
    assert default_resource_names(DEFAULT_DATA_VOLUME) == {
        "container": "localcloud",
        "network": "localcloud",
    }
    assert default_resource_names("localcloud-data-team-a") == {
        "container": "localcloud-team-a",
        "network": "localcloud-team-a",
    }

    custom = "customer-state"
    digest = hashlib.sha256(custom.encode()).hexdigest()[:12]
    assert default_resource_names(custom) == {
        "container": f"localcloud-volume-{digest}",
        "network": f"localcloud-volume-{digest}",
    }

    long_volume = f"localcloud-data-{'a' * 150}"
    long_digest = hashlib.sha256(long_volume.encode()).hexdigest()[:12]
    assert default_resource_names(long_volume)["container"] == (
        f"localcloud-volume-{long_digest}"
    )


def test_resource_creation_overrides_do_not_change_data_volume(
    tmp_path: Path,
) -> None:
    (tmp_path / "localcloud.yaml").write_text(
        "data_volume: localcloud-data-team-a\n"
        "container_name: custom-container\n"
        "network_name: custom-network\n",
        encoding="utf-8",
    )

    selected = load_config(directory=tmp_path, paths=_paths(tmp_path))

    assert selected.data_volume == "localcloud-data-team-a"
    assert selected.container_name == "custom-container"
    assert selected.network_name == "custom-network"



def test_active_runtime_restores_managed_resource_names(tmp_path: Path) -> None:
    active = ActiveRuntime(
        schema_version=ACTIVE_RUNTIME_SCHEMA_VERSION,
        data_volume="lc-pr-run-c0-data",
        image="registry.example/localcloud@sha256:" + "a" * 64,
        container_id="container-id",
        container_name="lc-pr-run-c0",
        network_name="lc-pr-run-c0",
    )

    selected = load_config(
        directory=tmp_path,
        paths=_paths(tmp_path),
        active_runtime=active,
    )

    assert selected.container_name == "lc-pr-run-c0"
    assert selected.network_name == "lc-pr-run-c0"
def test_data_volume_precedence_is_cli_yaml_active_then_default(
    tmp_path: Path,
) -> None:
    paths = _paths(tmp_path)
    save_active_runtime(paths, _active())

    assert load_config(directory=tmp_path, paths=paths).data_volume == (
        "localcloud-data-active"
    )

    config = tmp_path / "localcloud.yaml"
    config.write_text("data_volume: localcloud-data-yaml\n", encoding="utf-8")
    assert load_config(directory=tmp_path, paths=paths).data_volume == (
        "localcloud-data-yaml"
    )
    assert load_config(
        directory=tmp_path,
        paths=paths,
        data_volume="localcloud-data-cli",
    ).data_volume == "localcloud-data-cli"

    config.unlink()
    paths.active_runtime.unlink()
    assert load_config(directory=tmp_path, paths=paths).data_volume == (
        DEFAULT_DATA_VOLUME
    )


def test_image_precedence_is_yaml_environment_scoped_active_then_default(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = _paths(tmp_path)
    save_active_runtime(paths, _active())

    selected = load_config(directory=tmp_path, paths=paths)
    assert selected.image == "registry.example/localcloud:active"

    selected = load_config(
        directory=tmp_path,
        paths=paths,
        data_volume="localcloud-data-other",
    )
    assert selected.image == DEFAULT_IMAGE

    monkeypatch.setenv("LOCALCLOUD_IMAGE", "registry.example/localcloud:env")
    assert load_config(directory=tmp_path, paths=paths).image == (
        "registry.example/localcloud:env"
    )

    (tmp_path / "localcloud.yaml").write_text(
        "image: registry.example/localcloud:yaml\n",
        encoding="utf-8",
    )
    assert load_config(directory=tmp_path, paths=paths).image == (
        "registry.example/localcloud:yaml"
    )


def test_config_path_precedence_is_explicit_then_local_then_remembered(
    tmp_path: Path,
) -> None:
    explicit = tmp_path / "explicit.yaml"
    local = tmp_path / "localcloud.yaml"
    remembered = tmp_path / "remembered.yaml"
    explicit.write_text("project: explicit-project-1\n", encoding="utf-8")
    local.write_text("project: current-project-1\n", encoding="utf-8")
    remembered.write_text("project: remembered-project-1\n", encoding="utf-8")
    paths = _paths(tmp_path)

    selected = load_config(
        explicit=explicit,
        remembered=str(remembered),
        directory=tmp_path,
        paths=paths,
    )
    assert selected.project == "explicit-project-1"
    assert selected.config_path == explicit.resolve()

    selected = load_config(
        remembered=str(remembered), directory=tmp_path, paths=paths
    )
    assert selected.project == "current-project-1"
    assert selected.config_path == local.resolve()

    local.unlink()
    selected = load_config(
        remembered=str(remembered), directory=tmp_path, paths=paths
    )
    assert selected.project == "remembered-project-1"
    assert selected.config_path == remembered.resolve()


def test_request_context_and_seed_do_not_change_runtime_hash(
    tmp_path: Path,
) -> None:
    path = tmp_path / "localcloud.yaml"
    path.write_text(
        "project: yaml-project-1\nuser: yaml-user\nseed: seed.yaml\n",
        encoding="utf-8",
    )
    seed = tmp_path / "seed.yaml"
    seed.write_text("projects: []\n", encoding="utf-8")
    paths = _paths(tmp_path)
    yaml_selected = load_config(directory=tmp_path, paths=paths)
    cli_selected = load_config(
        directory=tmp_path,
        paths=paths,
        project="other-project-1",
        user="other-user",
    )
    seed.write_text("projects:\n  - id: changed-project-1\n", encoding="utf-8")
    changed_seed = load_config(directory=tmp_path, paths=paths)

    assert yaml_selected.project == "yaml-project-1"
    assert yaml_selected.user == "yaml-user"
    assert cli_selected.project == "other-project-1"
    assert cli_selected.user == "other-user"
    assert yaml_selected.config_hash == cli_selected.config_hash
    assert yaml_selected.config_hash == changed_seed.config_hash


def test_runtime_settings_change_hash(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    first = load_config(directory=tmp_path, paths=paths)
    named = load_config(
        directory=tmp_path,
        paths=paths,
        data_volume="localcloud-data-team-a",
    )
    (tmp_path / "localcloud.yaml").write_text("memory: 8g\n", encoding="utf-8")
    memory = load_config(directory=tmp_path, paths=paths)

    assert first.config_hash != named.config_hash
    assert first.config_hash != memory.config_hash


def test_seed_auto_resolves_beside_selected_config(tmp_path: Path) -> None:
    config_dir = tmp_path / "config"
    config_dir.mkdir()
    config = config_dir / "custom.yaml"
    config.write_text("seed: auto\n", encoding="utf-8")
    seed = config_dir / "seed.yaml"
    seed.write_text("projects: []\n", encoding="utf-8")

    selected = load_config(
        explicit=config, directory=tmp_path, paths=_paths(tmp_path)
    )
    assert selected.seed_path == seed.resolve()
    assert selected.seed_yaml == "projects: []\n"


def test_local_seed_auto_and_null_disable(tmp_path: Path) -> None:
    seed = tmp_path / "seed.yaml"
    seed.write_text("projects: []\n", encoding="utf-8")
    paths = _paths(tmp_path)
    assert load_config(directory=tmp_path, paths=paths).seed_path == seed.resolve()

    (tmp_path / "localcloud.yaml").write_text("seed: null\n", encoding="utf-8")
    selected = load_config(directory=tmp_path, paths=paths)
    assert selected.seed_path is None
    assert selected.seed_yaml is None


@pytest.mark.parametrize(
    "value",
    ["", "-bad", "bad/name", "has space", "a" * 256],
)
def test_invalid_data_volume_fails_before_use(value: str) -> None:
    with pytest.raises(HostError) as caught:
        validate_data_volume(value)
    assert caught.value.code == "invalid_data_volume"


def test_invalid_project_user_and_docker_name_are_rejected(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    for kwargs in (
        {"project": "short"},
        {"user": "has space"},
        {"container_name": "bad/name"},
        {"network_name": "bad/name"},
    ):
        with pytest.raises(HostError) as caught:
            load_config(directory=tmp_path, paths=paths, **kwargs)
        assert caught.value.code == "invalid_config"


def test_data_volume_lock_uses_digest_and_serializes(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    entered: list[str] = []
    first_holds = threading.Event()

    def first() -> None:
        with data_volume_lock(paths, "localcloud-data-team-a"):
            entered.append("first")
            first_holds.set()
            time.sleep(0.05)

    def second() -> None:
        first_holds.wait()
        with data_volume_lock(paths, "localcloud-data-team-a"):
            entered.append("second")

    threads = [threading.Thread(target=first), threading.Thread(target=second)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    other = "localcloud-data-team-b"
    with data_volume_lock(paths, other):
        pass

    first_name = (
        f"runtime-{hashlib.sha256('localcloud-data-team-a'.encode()).hexdigest()}.lock"
    )
    other_name = f"runtime-{hashlib.sha256(other.encode()).hexdigest()}.lock"
    assert entered == ["first", "second"]
    assert first_name != other_name
    assert {path.name for path in paths.locks.iterdir()} == {
        first_name,
        other_name,
    }


def test_data_volume_lock_wraps_filesystem_errors_as_host_error(
    tmp_path: Path,
) -> None:
    home = tmp_path / "home"
    home.write_text("occupies the path LocalCloud wants as its home directory")
    paths = HostPaths(home=home, locks=home / "locks")

    with pytest.raises(HostError) as caught:
        with data_volume_lock(paths, "localcloud-data-team-a"):
            pass

    assert caught.value.code == "host_lock_failed"


def test_save_active_runtime_wraps_filesystem_errors_as_host_error(
    tmp_path: Path,
) -> None:
    home = tmp_path / "home"
    home.write_text("occupies the path LocalCloud wants as its home directory")
    paths = HostPaths(home=home, locks=tmp_path / "locks")

    with pytest.raises(HostError) as caught:
        save_active_runtime(paths, _active())

    assert caught.value.code in {"host_lock_failed", "active_runtime_write_failed"}


def test_active_runtime_missing_round_trip_and_atomic_replace(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = _paths(tmp_path)
    assert load_active_runtime(paths) is None
    replacements: list[tuple[Path, Path]] = []
    replace = os.replace

    def capture_replace(source: str | os.PathLike[str], target: str | os.PathLike[str]) -> None:
        source_path = Path(source)
        target_path = Path(target)
        assert source_path.exists()
        assert source_path != target_path
        replacements.append((source_path, target_path))
        replace(source, target)

    monkeypatch.setattr(config_module.os, "replace", capture_replace)
    runtime = _active()
    save_active_runtime(paths, runtime)

    assert load_active_runtime(paths) == runtime
    assert len(replacements) == 1
    assert replacements[0][1] == paths.active_runtime
    assert json.loads(paths.active_runtime.read_text(encoding="utf-8")) == {
        "schema_version": ACTIVE_RUNTIME_SCHEMA_VERSION,
        "last_active": runtime.data_volume,
        "runtimes": {
            runtime.data_volume: {
                "data_volume": runtime.data_volume,
                "image": runtime.image,
                "container_id": runtime.container_id,
                "container_name": runtime.container_name,
                "network_name": runtime.network_name,
            }
        },
    }


def test_active_runtime_names_are_persisted_per_data_volume(
    tmp_path: Path,
) -> None:
    paths = _paths(tmp_path)
    first = _active(
        data_volume="lc-pr-run-a-data",
        container_id="container-a",
        container_name="lc-pr-run-a",
        network_name="lc-pr-run-a",
    )
    second = _active(
        data_volume="lc-pr-run-b-data",
        container_id="container-b",
        container_name="lc-pr-run-b",
        network_name="lc-pr-run-b",
    )
    save_active_runtime(paths, first)
    save_active_runtime(paths, second)
    assert load_active_runtime(
        paths, data_volume=first.data_volume
    ) == first
    assert load_active_runtime(
        paths, data_volume=second.data_volume
    ) == second
    assert load_config(
        directory=tmp_path,
        paths=paths,
        data_volume=first.data_volume,
    ).container_name == first.container_name


@pytest.mark.parametrize(
    "contents",
    [
        "{not-json",
        '{"schema_version":99,"data_volume":"localcloud-data","image":"x","container_id":"y"}',
        '{"schema_version":1,"data_volume":"bad/name","image":"x","container_id":"y"}',
    ],
)
def test_invalid_active_state_is_diagnostic_and_falls_back(
    tmp_path: Path,
    contents: str,
) -> None:
    paths = _paths(tmp_path)
    paths.home.mkdir(parents=True)
    paths.active_runtime.write_text(contents, encoding="utf-8")

    selected = load_config(directory=tmp_path, paths=paths)

    assert selected.data_volume == DEFAULT_DATA_VOLUME
    assert selected.image == DEFAULT_IMAGE
    assert selected.diagnostics[0]["code"] == "invalid_active_runtime"


@pytest.mark.parametrize(
    ("source", "replacement"),
    [
        ("instance: team-a\n", "localcloud-data-team-a"),
        ("volume_name: customer-volume\n", "customer-volume"),
        (
            "instance: team-a\nvolume_name: explicit-volume\n",
            "explicit-volume",
        ),
    ],
)
def test_legacy_runtime_selectors_have_exact_migration_guidance(
    tmp_path: Path,
    source: str,
    replacement: str,
) -> None:
    (tmp_path / "localcloud.yaml").write_text(source, encoding="utf-8")

    with pytest.raises(HostError) as caught:
        load_config(directory=tmp_path, paths=_paths(tmp_path))

    assert caught.value.code == "legacy_runtime_selector"
    assert caught.value.details["replacement"] == {"data_volume": replacement}
    assert set(caught.value.details["fields"]).issubset(
        {"instance", "volume_name"}
    )
