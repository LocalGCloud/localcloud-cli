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
        "host:\n"
        "  data_volume: localcloud-data-team-a\n"
        "  container_name: custom-container\n"
        "  network_name: custom-network\n",
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
    config.write_text(
        "host:\n  data_volume: localcloud-data-yaml\n", encoding="utf-8"
    )
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
        "host:\n  image: registry.example/localcloud:yaml\n",
        encoding="utf-8",
    )
    assert load_config(directory=tmp_path, paths=paths).image == (
        "registry.example/localcloud:yaml"
    )


def test_config_path_precedence_is_explicit_local_remembered_then_home(
    tmp_path: Path,
) -> None:
    explicit = tmp_path / "explicit.yaml"
    local = tmp_path / "localcloud.yaml"
    remembered = tmp_path / "remembered.yaml"
    explicit.write_text(
        "context:\n  project: explicit-project-1\n", encoding="utf-8"
    )
    local.write_text(
        "context:\n  project: current-project-1\n", encoding="utf-8"
    )
    remembered.write_text(
        "context:\n  project: remembered-project-1\n", encoding="utf-8"
    )
    paths = _paths(tmp_path)
    paths.home.mkdir(parents=True)
    home = paths.home / "localcloud.yaml"
    home.write_text(
        "context:\n  project: home-project-1\n", encoding="utf-8"
    )

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

    remembered.unlink()
    selected = load_config(directory=tmp_path, paths=paths)
    assert selected.project == "home-project-1"
    assert selected.config_path == home.resolve()


def test_localcloud_tls_and_mcp_sections_pass_through_validation(
    tmp_path: Path,
) -> None:
    config = tmp_path / "localcloud.yaml"
    config.write_text(
        "tls:\n"
        "  enabled: false\n"
        "mcp:\n"
        "  write: false\n"
        "  destructive: false\n"
        "  allow_remote: false\n",
        encoding="utf-8",
    )

    selected = load_config(directory=tmp_path, paths=_paths(tmp_path))

    assert selected.config_path == config.resolve()


def test_mcp_permission_values_must_be_boolean(tmp_path: Path) -> None:
    (tmp_path / "localcloud.yaml").write_text(
        "mcp:\n  write: \"false\"\n",
        encoding="utf-8",
    )

    with pytest.raises(HostError) as caught:
        load_config(directory=tmp_path, paths=_paths(tmp_path))

    assert caught.value.code == "invalid_config"


def test_request_context_and_seed_do_not_change_runtime_hash(
    tmp_path: Path,
) -> None:
    path = tmp_path / "localcloud.yaml"
    path.write_text(
        "context:\n"
        "  project: yaml-project-1\n"
        "  user: yaml-user\n"
        "host:\n"
        "  seed: seed.yaml\n",
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
    (tmp_path / "localcloud.yaml").write_text(
        "host:\n  memory: 8g\n", encoding="utf-8"
    )
    memory = load_config(directory=tmp_path, paths=paths)

    services = load_config(directory=tmp_path, paths=paths, services=["gcs"])

    assert first.config_hash != named.config_hash
    assert first.config_hash != memory.config_hash
    assert first.config_hash != services.config_hash


def test_seed_auto_resolves_beside_selected_config(tmp_path: Path) -> None:
    config_dir = tmp_path / "config"
    config_dir.mkdir()
    config = config_dir / "custom.yaml"
    config.write_text("host:\n  seed: auto\n", encoding="utf-8")
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

    (tmp_path / "localcloud.yaml").write_text(
        "host:\n  seed: disabled\n", encoding="utf-8"
    )
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
    assert caught.value.details["replacement"] == {
        "host.data_volume": replacement
    }
    assert set(caught.value.details["fields"]).issubset(
        {"instance", "volume_name"}
    )


def test_removed_flat_schema_returns_exact_namespace_migration_map(
    tmp_path: Path,
) -> None:
    source = (
        "project: project-flat-1\n"
        "user: flat-user\n"
        "services: [gcs]\n"
        "data_volume: flat-data\n"
        "seed: null\n"
        "memory: 8g\n"
    )
    (tmp_path / "localcloud.yaml").write_text(source, encoding="utf-8")

    with pytest.raises(HostError) as caught:
        load_config(directory=tmp_path, paths=_paths(tmp_path))

    assert caught.value.code == "removed_flat_config"
    assert caught.value.details["replacement"] == {
        "data_volume": "host.data_volume",
        "memory": "host.memory",
        "project": "context.project",
        "seed": "host.seed",
        "services": "services.enabled",
        "user": "context.user",
    }
    assert caught.value.details["seed_null_migration"] == {
        "from": "seed: null",
        "replacement": "host.seed: disabled",
    }


def test_namespaced_schema_preserves_host_model_and_context_precedence(
    tmp_path: Path,
) -> None:
    config = tmp_path / "localcloud.yaml"
    config.write_text(
        "version: 1\n"
        "context:\n"
        "  project: namespaced-project-1\n"
        "  user: namespaced-user\n"
        "host:\n"
        "  data_volume: namespaced-data\n"
        "  seed: disabled\n"
        "  data: ephemeral\n"
        "  memory: 6g\n"
        "  docker_socket: true\n"
        "  transparent_network: true\n"
        "services:\n"
        "  enabled: [PUBSUB, gcs, pubsub]\n"
        "server:\n"
        "  logging:\n"
        "    verbosity: debug\n"
        "infrastructure: {}\n",
        encoding="utf-8",
    )

    selected = load_config(
        directory=tmp_path,
        paths=_paths(tmp_path),
        active_runtime=None,
    )

    assert selected.project == "namespaced-project-1"
    assert selected.user == "namespaced-user"
    assert selected.data_volume == "namespaced-data"
    assert selected.services == ("pubsub", "gcs")
    assert selected.seed_path is None
    assert selected.data == "ephemeral"
    assert selected.memory == "6g"
    assert selected.docker_socket is True
    assert selected.transparent_network is True

    overridden = load_config(
        directory=tmp_path,
        paths=_paths(tmp_path),
        active_runtime=None,
        project="cli-project-1",
        user="cli-user",
    )
    assert overridden.project == "cli-project-1"
    assert overridden.user == "cli-user"


def test_null_host_and_members_fall_back_to_cli_defaults(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    seed = tmp_path / "seed.yaml"
    seed.write_text("services: {}\n", encoding="utf-8")
    (tmp_path / "localcloud.yaml").write_text("host: null\n", encoding="utf-8")
    selected = load_config(
        directory=tmp_path, paths=paths, active_runtime=None
    )
    assert selected.data_volume == DEFAULT_DATA_VOLUME
    assert selected.memory == "4g"
    assert selected.seed_path == seed
    assert selected.seed_yaml == "services: {}\n"

    (tmp_path / "localcloud.yaml").write_text(
        "host:\n"
        "  data_volume: null\n"
        "  seed: null\n"
        "  image: null\n"
        "  memory: null\n"
        "  docker_socket: null\n"
        "  environment: null\n",
        encoding="utf-8",
    )
    selected = load_config(
        directory=tmp_path, paths=paths, active_runtime=None
    )
    assert selected.data_volume == DEFAULT_DATA_VOLUME
    assert selected.image == DEFAULT_IMAGE
    assert selected.memory == "4g"
    assert selected.docker_socket is False
    assert selected.environment == {}
    assert selected.seed_path == seed
    assert selected.seed_yaml == "services: {}\n"


def test_non_null_empty_owned_values_are_rejected(tmp_path: Path) -> None:
    invalid = (
        'context:\n  user: ""\n',
        'host:\n  container_name: ""\n',
        'host:\n  network_name: ""\n',
    )
    for source in invalid:
        (tmp_path / "localcloud.yaml").write_text(source, encoding="utf-8")
        with pytest.raises(HostError):
            load_config(
                directory=tmp_path,
                paths=_paths(tmp_path),
                active_runtime=None,
            )


def test_null_services_enabled_and_invalid_passthrough_shapes_are_rejected(
    tmp_path: Path,
) -> None:
    invalid = (
        "services:\n  enabled: null\n",
        "server: null\n",
        "services:\n  catalog: null\n",
        "infrastructure: null\n",
    )
    for source in invalid:
        (tmp_path / "localcloud.yaml").write_text(source, encoding="utf-8")
        with pytest.raises(HostError):
            load_config(directory=tmp_path, paths=_paths(tmp_path))


@pytest.mark.parametrize(
    "name",
    [
        "LOCALCLOUD_CONFIG",
        "LOCALCLOUD_PROJECT",
        "LOCALCLOUD_DATA_DIR",
        "LOCALCLOUD_SERVICES",
    ],
)
def test_host_environment_rejects_controller_owned_config_keys(
    tmp_path: Path, name: str
) -> None:
    (tmp_path / "localcloud.yaml").write_text(
        f"host:\n  environment:\n    {name}: forbidden\n",
        encoding="utf-8",
    )
    with pytest.raises(HostError) as caught:
        load_config(directory=tmp_path, paths=_paths(tmp_path))
    assert name in caught.value.message


def test_tls_disabled_by_default_without_config_or_override(tmp_path: Path) -> None:
    selected = load_config(directory=tmp_path, paths=_paths(tmp_path))
    assert selected.environment == {}


def test_tls_override_false_disables_regardless_of_config(tmp_path: Path) -> None:
    (tmp_path / "localcloud.yaml").write_text(
        "host:\n  environment:\n    LOCALCLOUD_TLS_ENABLED: \"true\"\n",
        encoding="utf-8",
    )
    selected = load_config(directory=tmp_path, paths=_paths(tmp_path), tls=False)
    assert selected.environment == {"LOCALCLOUD_TLS_ENABLED": "false"}


def test_tls_override_true_wins_over_config_disabling_it(tmp_path: Path) -> None:
    (tmp_path / "localcloud.yaml").write_text(
        "host:\n  environment:\n    LOCALCLOUD_TLS_ENABLED: \"false\"\n",
        encoding="utf-8",
    )
    selected = load_config(directory=tmp_path, paths=_paths(tmp_path), tls=True)
    assert selected.environment == {"LOCALCLOUD_TLS_ENABLED": "true"}


def test_tls_config_value_respected_without_cli_override(tmp_path: Path) -> None:
    (tmp_path / "localcloud.yaml").write_text(
        "host:\n  environment:\n    LOCALCLOUD_TLS_ENABLED: \"false\"\n",
        encoding="utf-8",
    )
    selected = load_config(directory=tmp_path, paths=_paths(tmp_path))
    assert selected.environment == {"LOCALCLOUD_TLS_ENABLED": "false"}


def test_memory_override_wins_over_config(tmp_path: Path) -> None:
    (tmp_path / "localcloud.yaml").write_text(
        "host:\n  memory: 2g\n", encoding="utf-8"
    )
    selected = load_config(directory=tmp_path, paths=_paths(tmp_path), memory="8g")
    assert selected.memory == "8g"


def test_memory_defaults_to_config_without_cli_override(tmp_path: Path) -> None:
    (tmp_path / "localcloud.yaml").write_text(
        "host:\n  memory: 2g\n", encoding="utf-8"
    )
    selected = load_config(directory=tmp_path, paths=_paths(tmp_path))
    assert selected.memory == "2g"


def test_image_override_wins_over_config(tmp_path: Path) -> None:
    (tmp_path / "localcloud.yaml").write_text(
        "host:\n  image: yamlrepo/localcloud:yaml\n", encoding="utf-8"
    )
    selected = load_config(
        directory=tmp_path, paths=_paths(tmp_path), image="clirepo/localcloud:dev"
    )
    assert selected.image == "clirepo/localcloud:dev"


def test_services_override_wins_over_config(tmp_path: Path) -> None:
    (tmp_path / "localcloud.yaml").write_text(
        "services:\n  enabled:\n    - firestore\n", encoding="utf-8"
    )
    selected = load_config(
        directory=tmp_path, paths=_paths(tmp_path), services=["gcs", "pubsub"]
    )
    assert selected.services == ("gcs", "pubsub")


def test_services_override_default_resets_config_list(tmp_path: Path) -> None:
    (tmp_path / "localcloud.yaml").write_text(
        "services:\n  enabled:\n    - firestore\n", encoding="utf-8"
    )
    selected = load_config(
        directory=tmp_path, paths=_paths(tmp_path), services="default"
    )
    assert selected.services is None


def test_host_localcloud_config_selector_precedes_local_and_remembered(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    explicit = tmp_path / "explicit.yaml"
    configured = tmp_path / "configured.yaml"
    local = tmp_path / "localcloud.yaml"
    remembered = tmp_path / "remembered.yaml"
    for path, project in (
        (explicit, "explicit-project-1"),
        (configured, "configured-project-1"),
        (local, "local-project-1"),
        (remembered, "remembered-project-1"),
    ):
        path.write_text(
            f"context:\n  project: {project}\n", encoding="utf-8"
        )
    monkeypatch.setenv("LOCALCLOUD_CONFIG", str(configured))

    assert load_config(
        explicit=explicit,
        remembered=str(remembered),
        directory=tmp_path,
        paths=_paths(tmp_path),
    ).project == "explicit-project-1"
    selected = load_config(
        remembered=str(remembered),
        directory=tmp_path,
        paths=_paths(tmp_path),
    )
    assert selected.project == "configured-project-1"
    assert selected.config_path == configured.resolve()


def test_config_path_presence_and_identity_change_runtime_hash(
    tmp_path: Path,
) -> None:
    paths = _paths(tmp_path)
    defaults = load_config(
        directory=tmp_path, paths=paths, active_runtime=None
    )
    local = tmp_path / "localcloud.yaml"
    local.write_text("# empty overlay\n", encoding="utf-8")
    selected = load_config(
        directory=tmp_path, paths=paths, active_runtime=None
    )
    other = tmp_path / "other.yaml"
    other.write_text("# same empty overlay\n", encoding="utf-8")
    other_selected = load_config(
        explicit=other,
        directory=tmp_path,
        paths=paths,
        active_runtime=None,
    )

    assert defaults.config_hash != selected.config_hash
    assert selected.config_hash != other_selected.config_hash


def test_strict_yaml_rejects_duplicates_merge_keys_and_ambiguous_scalars(
    tmp_path: Path,
) -> None:
    invalid = (
        "context:\n  project: first-project\n  project: second-project\n",
        "context:\n  <<: {project: inherited-project}\n",
        "context:\n  project: on\n",
        "context:\n  1: value\n",
        "host: &host\n  environment: *host\n",
    )
    for source in invalid:
        (tmp_path / "localcloud.yaml").write_text(source, encoding="utf-8")
        with pytest.raises(HostError):
            load_config(directory=tmp_path, paths=_paths(tmp_path))


def test_skip_validation_bypasses_unknown_top_level_fields_with_diagnostic(
    tmp_path: Path,
) -> None:
    (tmp_path / "localcloud.yaml").write_text(
        "version: 1\nfuture_top_level_section:\n  new_thing: true\n",
        encoding="utf-8",
    )

    with pytest.raises(HostError):
        load_config(directory=tmp_path, paths=_paths(tmp_path))

    selected = load_config(
        directory=tmp_path, paths=_paths(tmp_path), skip_validation=True
    )

    assert any(
        entry["code"] == "config_validation_skipped"
        and entry["bypassed_code"] == "invalid_config"
        for entry in selected.diagnostics
    )


def test_skip_validation_bypasses_removed_flat_schema_with_diagnostic(
    tmp_path: Path,
) -> None:
    (tmp_path / "localcloud.yaml").write_text(
        "project: project-flat-1\n", encoding="utf-8"
    )

    selected = load_config(
        directory=tmp_path, paths=_paths(tmp_path), skip_validation=True
    )

    assert selected.project == DEFAULT_PROJECT
    assert any(
        entry["code"] == "config_validation_skipped"
        and entry["bypassed_code"] == "removed_flat_config"
        for entry in selected.diagnostics
    )


def test_skip_config_validation_env_var_enables_bypass_without_flag(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    (tmp_path / "localcloud.yaml").write_text(
        "host:\n  unknown_future_field: true\n", encoding="utf-8"
    )
    monkeypatch.setenv("LOCALCLOUD_SKIP_CONFIG_VALIDATION", "1")

    selected = load_config(directory=tmp_path, paths=_paths(tmp_path))

    assert any(
        entry["code"] == "config_validation_skipped"
        for entry in selected.diagnostics
    )


def test_skip_validation_never_bypasses_yaml_syntax_or_host_value_shape(
    tmp_path: Path,
) -> None:
    (tmp_path / "localcloud.yaml").write_text(
        "context:\n  project: dup\n  project: dup2\n", encoding="utf-8"
    )
    with pytest.raises(HostError):
        load_config(
            directory=tmp_path, paths=_paths(tmp_path), skip_validation=True
        )

    (tmp_path / "localcloud.yaml").write_text(
        "host:\n  data: not-a-real-mode\n", encoding="utf-8"
    )
    with pytest.raises(HostError):
        load_config(
            directory=tmp_path, paths=_paths(tmp_path), skip_validation=True
        )


def test_cli_runtime_modules_embed_no_java_owned_service_catalog() -> None:
    java_catalog_schema_markers = (
        "gatewayObservable",
        "minTier",
        "envValuePrefix",
        "terraformEnvVar",
        "gcloudApiName",
        "healthCheck",
        "gatewayApiName",
    )
    src_root = Path(config_module.__file__).parent
    for module_name in ("config.py", "docker_runtime.py", "controller.py"):
        source = (src_root / module_name).read_text(encoding="utf-8")
        for marker in java_catalog_schema_markers:
            assert marker not in source, (
                f"{module_name} embeds Java-owned catalog schema field {marker!r}; "
                "the CLI must not carry a copy of LocalCloud's service catalog"
            )


def test_cli_package_has_no_java_or_image_execution_dependency() -> None:
    project_root = Path(config_module.__file__).parent.parent.parent
    pyproject = (project_root / "pyproject.toml").read_text(encoding="utf-8")
    for forbidden in ("jdk", "jre", "openjdk", "jpype", "py4j"):
        assert forbidden not in pyproject.lower(), (
            f"pyproject.toml declares a Java runtime dependency ({forbidden!r}); "
            "the CLI must remain installable and usable without a local JVM"
        )

    docker_runtime_source = (
        Path(config_module.__file__).parent / "docker_runtime.py"
    ).read_text(encoding="utf-8")
    assert "subprocess" not in docker_runtime_source, (
        "docker_runtime.py must not shell out to run the LocalCloud image "
        "before container creation to resolve configuration"
    )
