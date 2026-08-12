from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import yaml

from localcloud_cli.agent_guide import render_agent_guide


SERVICE_LINE = re.compile(
    r"^  (?P<comment># )?- (?P<id>[a-z0-9]+)  # "
    r"(?P<deactivated>deactivated service: )?(?P<display>.+)$"
)


def _canonical_services() -> dict[str, dict[str, Any]]:
    registry_path = Path(__file__).parents[2] / "services.yaml"
    registry = yaml.safe_load(registry_path.read_text(encoding="utf-8"))
    return registry["services"]


def test_guide_inventory_matches_canonical_service_registry() -> None:
    guide = render_agent_guide()
    rendered: dict[str, tuple[bool, bool, str]] = {}
    for line in guide.splitlines():
        match = SERVICE_LINE.fullmatch(line)
        if match is None:
            continue
        service_id = match.group("id")
        assert service_id not in rendered
        rendered[service_id] = (
            match.group("comment") is None,
            match.group("deactivated") is not None,
            match.group("display"),
        )

    canonical = _canonical_services()
    assert set(rendered) == set(canonical)
    for service_id, definition in canonical.items():
        active, marked_deactivated, display_name = rendered[service_id]
        default_enabled = definition["defaultEnabled"]
        assert isinstance(default_enabled, bool)
        assert active is default_enabled
        assert marked_deactivated is (not default_enabled)
        assert display_name == definition["displayName"]


def test_guide_explains_shared_identity_and_catalog_first_workflow() -> None:
    guide = render_agent_guide()

    assert "local-gcp-project" in guide
    assert "local-developer" in guide
    assert "--instance NAME" in guide
    assert "localcloud reset --all-projects" in guide
    assert "localcloud://api/catalog" in guide
    assert "localcloud_get_api_catalog" in guide
    assert "localcloud_call_api" in guide
    assert "mcp.direct_url" in guide
    assert "mcp.headers" in guide
    assert "localcloud.yaml" in guide

    removed_surfaces = (
        "--" + "work" + "space",
        "localcloud-" + "agent.yaml",
        "project:" + " auto",
    )
    assert all(surface not in guide.lower() for surface in removed_surfaces)
