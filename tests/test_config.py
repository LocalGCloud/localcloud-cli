from __future__ import annotations

import threading
import time
from pathlib import Path

import pytest

from localcloud_cli.config import (
    DEFAULT_INSTANCE,
    DEFAULT_PROJECT,
    DEFAULT_USER,
    HostPaths,
    default_resource_names,
    instance_lock,
    load_config,
    validate_instance,
)
from localcloud_cli.errors import HostError


def test_zero_config_uses_shared_defaults(tmp_path: Path) -> None:
    selected = load_config(directory=tmp_path)

    assert selected.instance == DEFAULT_INSTANCE
    assert selected.project == DEFAULT_PROJECT
    assert selected.user == DEFAULT_USER
    assert selected.container_name == "localcloud"
    assert selected.network_name == "localcloud"
    assert selected.volume_name == "localcloud-data"
    assert selected.config_path is None


def test_default_named_and_custom_resource_names(tmp_path: Path) -> None:
    assert default_resource_names("default") == {
        "container": "localcloud",
        "network": "localcloud",
        "volume": "localcloud-data",
    }
    assert default_resource_names("team-a") == {
        "container": "localcloud-team-a",
        "network": "localcloud-team-a",
        "volume": "localcloud-data-team-a",
    }

    path = tmp_path / "localcloud.yaml"
    path.write_text(
        "instance: team-a\n"
        "container_name: custom-container\n"
        "network_name: custom-network\n"
        "volume_name: custom-volume\n",
        encoding="utf-8",
    )
    selected = load_config(directory=tmp_path)
    assert (
        selected.container_name,
        selected.network_name,
        selected.volume_name,
    ) == ("custom-container", "custom-network", "custom-volume")


def test_config_precedence_is_explicit_then_local_then_remembered(
    tmp_path: Path,
) -> None:
    explicit = tmp_path / "explicit.yaml"
    local = tmp_path / "localcloud.yaml"
    remembered = tmp_path / "remembered.yaml"
    explicit.write_text("project: explicit-project-1\n", encoding="utf-8")
    local.write_text("project: current-project-1\n", encoding="utf-8")
    remembered.write_text("project: remembered-project-1\n", encoding="utf-8")

    selected = load_config(
        explicit=explicit, remembered=str(remembered), directory=tmp_path
    )
    assert selected.project == "explicit-project-1"
    assert selected.config_path == explicit.resolve()

    selected = load_config(remembered=str(remembered), directory=tmp_path)
    assert selected.project == "current-project-1"
    assert selected.config_path == local.resolve()

    local.unlink()
    selected = load_config(remembered=str(remembered), directory=tmp_path)
    assert selected.project == "remembered-project-1"
    assert selected.config_path == remembered.resolve()


def test_cli_context_overrides_yaml_and_never_changes_instance_hash(
    tmp_path: Path,
) -> None:
    path = tmp_path / "localcloud.yaml"
    path.write_text(
        "project: yaml-project-1\nuser: yaml-user\nseed: seed.yaml\n",
        encoding="utf-8",
    )
    seed = tmp_path / "seed.yaml"
    seed.write_text("projects: []\n", encoding="utf-8")
    yaml_selected = load_config(directory=tmp_path)
    cli_selected = load_config(
        directory=tmp_path,
        project="other-project-1",
        user="other-user",
    )
    seed.write_text("projects:\n  - id: changed-project-1\n", encoding="utf-8")
    changed_seed = load_config(directory=tmp_path)

    assert yaml_selected.project == "yaml-project-1"
    assert yaml_selected.user == "yaml-user"
    assert cli_selected.project == "other-project-1"
    assert cli_selected.user == "other-user"
    assert yaml_selected.config_hash == cli_selected.config_hash
    assert yaml_selected.config_hash == changed_seed.config_hash


def test_instance_settings_but_not_context_change_hash(tmp_path: Path) -> None:
    first = load_config(directory=tmp_path)
    named = load_config(directory=tmp_path, instance="team-a")
    path = tmp_path / "localcloud.yaml"
    path.write_text("memory: 8g\n", encoding="utf-8")
    memory = load_config(directory=tmp_path)

    assert first.config_hash != named.config_hash
    assert first.config_hash != memory.config_hash


def test_seed_auto_resolves_beside_selected_config(tmp_path: Path) -> None:
    config_dir = tmp_path / "config"
    config_dir.mkdir()
    config = config_dir / "custom.yaml"
    config.write_text("seed: auto\n", encoding="utf-8")
    seed = config_dir / "seed.yaml"
    seed.write_text("projects: []\n", encoding="utf-8")

    selected = load_config(explicit=config, directory=tmp_path)
    assert selected.seed_path == seed.resolve()
    assert selected.seed_yaml == "projects: []\n"


def test_local_seed_auto_and_null_disable(tmp_path: Path) -> None:
    seed = tmp_path / "seed.yaml"
    seed.write_text("projects: []\n", encoding="utf-8")
    assert load_config(directory=tmp_path).seed_path == seed.resolve()

    (tmp_path / "localcloud.yaml").write_text("seed: null\n", encoding="utf-8")
    selected = load_config(directory=tmp_path)
    assert selected.seed_path is None
    assert selected.seed_yaml is None


@pytest.mark.parametrize("value", ["", "UPPER", "-bad", "bad/name", "a" * 64])
def test_invalid_instance_fails_before_use(value: str) -> None:
    with pytest.raises(HostError) as caught:
        validate_instance(value)
    assert caught.value.code == "invalid_instance"


def test_invalid_project_user_and_docker_name_are_rejected(tmp_path: Path) -> None:
    for kwargs in (
        {"project": "short"},
        {"user": "has space"},
        {"container_name": "bad/name"},
    ):
        with pytest.raises(HostError) as caught:
            load_config(directory=tmp_path, **kwargs)
        assert caught.value.code == "invalid_config"


def test_instance_lock_uses_fixed_and_named_files_and_serializes(
    tmp_path: Path,
) -> None:
    paths = HostPaths(tmp_path / "home", tmp_path / "home" / "locks")
    entered: list[str] = []
    first_holds = threading.Event()

    def first() -> None:
        with instance_lock(paths, "team-a"):
            entered.append("first")
            first_holds.set()
            time.sleep(0.05)

    def second() -> None:
        first_holds.wait()
        with instance_lock(paths, "team-a"):
            entered.append("second")

    threads = [threading.Thread(target=first), threading.Thread(target=second)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    with instance_lock(paths, "default"):
        pass
    assert entered == ["first", "second"]
    assert sorted(path.name for path in paths.locks.iterdir()) == [
        "localcloud-team-a.lock",
        "localcloud.lock",
    ]
