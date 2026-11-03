"""The packaged defaults are the LocalCloud image's, as scripts/sync-gateway-defaults.py renders them.

src/localcloud_cli/defaults/localcloud.v1.yaml and .json are generated from the image's
/opt/localcloud/localcloud.defaults.yaml. These tests fail when they are edited by hand, when the
JSON no longer matches the YAML, or when the document stops being a valid localcloud.yaml base.
With LOCALCLOUD_GATEWAY_DEFAULTS set to a defaults file, the packaged copy must also be that file.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import shutil
from importlib.resources import files
from pathlib import Path
from typing import Any

import pytest
import yaml

from localcloud_cli.config import HostPaths, load_config

ROOT = Path(__file__).resolve().parents[1]
# Keys the gateway refuses as retired. None may come back into the packaged document, where users
# would copy them into a localcloud.yaml the gateway then fails to load.
RETIRED = (
    "server.api_key",
    "server.license_server_url",
    "server.feedback_project_url",
    "server.feedback_github_repository",
    "server.feedback_github_token",
    "server.logging",
    "server.gcp_credentials.adc_path",
    "server.gcp_credentials.sa_key_path",
    "server.identity.default_service_account",
    "server.identity.lifetime_extension_accounts",
    "updater.prune_old_image",
    "updater.drain_timeout_seconds",
)
RETIRED_CATALOG = ("gcloudPort", "healthCheck", "usage")


def _sync() -> Any:
    spec = importlib.util.spec_from_file_location(
        "sync_gateway_defaults", ROOT / "scripts" / "sync-gateway-defaults.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _packaged(name: str) -> str:
    return files("localcloud_cli.defaults").joinpath(name).read_text(encoding="utf-8")


def test_packaged_defaults_are_the_sync_script_output() -> None:
    sync = _sync()
    packaged_yaml = _packaged("localcloud.v1.yaml")
    source, digest = sync.provenance(packaged_yaml)

    rendered_yaml, rendered_json = sync.render(packaged_yaml, source, digest)

    assert rendered_yaml == packaged_yaml, "re-run scripts/sync-gateway-defaults.py instead of editing the YAML"
    assert rendered_json == _packaged("localcloud.v1.json"), "re-run scripts/sync-gateway-defaults.py"


def test_packaged_defaults_are_a_valid_overlay_base() -> None:
    assert _sync().validate(_packaged("localcloud.v1.yaml")) == []


def test_packaged_defaults_load_as_a_users_localcloud_yaml(tmp_path: Path) -> None:
    config_path = tmp_path / "localcloud.yaml"
    config_path.write_text(_packaged("localcloud.v1.yaml"), encoding="utf-8")
    home = tmp_path / "home"

    config = load_config(
        config_path, directory=tmp_path, paths=HostPaths(home, home / "locks"), active_runtime=None
    )

    assert config.diagnostics == ()
    assert config.data_volume == "localcloud-data"
    assert config.docker_socket_mode == "auto"
    assert config.project == "local-gcp-project"


def test_packaged_defaults_carry_no_retired_key() -> None:
    document = yaml.safe_load(_packaged("localcloud.v1.yaml"))
    present = []
    for path in RETIRED:
        value: Any = document
        for key in path.split("."):
            value = value.get(key) if isinstance(value, dict) else None
        if value is not None:
            present.append(path)
    for service_id, definition in document["services"]["catalog"].items():
        present.extend(f"services.catalog.{service_id}.{key}" for key in RETIRED_CATALOG if key in definition)
    assert present == []


def test_the_sync_script_drops_internal_comments_and_refuses_unknown_ones() -> None:
    sync = _sync()
    source = (
        "# Gateway header\n"
        "version: 1\n"
        "server:\n"
        "  # The engine listens on loopback (config.EnginePort and OtherPort).\n"
        "  persistence: true\n"
        "  # Read from internal/server/handler.go.\n"
        "  auto_seed: true\n"
    )

    text, data = sync.render(source, "test", "0" * 64)

    assert "Gateway header" not in text
    assert "  # The engine listens on loopback.\n" in text
    assert json.loads(data) == yaml.safe_load(text)
    assert [line.split(":")[0] for line in sync.internal_comments(text)] == [
        "line 14 (source file)",
        "line 14 (source path)",
    ]
    assert any(problem.startswith("internal comment") for problem in sync.validate(text))


@pytest.mark.skipif(
    not os.environ.get("LOCALCLOUD_GATEWAY_DEFAULTS"),
    reason="set LOCALCLOUD_GATEWAY_DEFAULTS to the image's localcloud.defaults.yaml to compare",
)
def test_packaged_defaults_are_the_given_gateway_defaults() -> None:
    sync = _sync()
    source = Path(os.environ["LOCALCLOUD_GATEWAY_DEFAULTS"]).read_bytes()
    packaged_yaml = _packaged("localcloud.v1.yaml")
    label, digest = sync.provenance(packaged_yaml)

    assert digest == hashlib.sha256(source).hexdigest(), (
        "the packaged defaults come from another document; run scripts/sync-gateway-defaults.py"
    )
    assert sync.render(source.decode("utf-8"), label, digest)[0] == packaged_yaml


@pytest.mark.docker
@pytest.mark.skipif(shutil.which("docker") is None, reason="needs the docker CLI")
@pytest.mark.skipif(
    not os.environ.get("LOCALCLOUD_GATEWAY_IMAGE"),
    reason="set LOCALCLOUD_GATEWAY_IMAGE to the image the release ships with",
)
def test_packaged_defaults_are_the_given_images() -> None:
    image = os.environ["LOCALCLOUD_GATEWAY_IMAGE"]
    assert _sync().main(["--image", image, "--check"]) == 0
