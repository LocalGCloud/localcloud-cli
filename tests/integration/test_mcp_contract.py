"""The runtime's own MCP replies, checked against the MCP schema.

MCP clients validate every reply, and Claude Code refuses a server whose
initialize result has one malformed capability. Runs the image `lc start`
would (LOCALCLOUD_IMAGE picks another) on a throwaway runtime; never pulls."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import mcp.types as mcp_types
import pytest

from integration._support import throwaway_runtime
from localcloud_cli.constants import DEFAULT_IMAGE
from localcloud_cli.java_client import JavaMcpClient

pytestmark = pytest.mark.docker

_REPLIES: tuple[tuple[str, type[Any]], ...] = (
    ("tools/list", mcp_types.ListToolsResult),
    ("resources/list", mcp_types.ListResourcesResult),
    ("resources/templates/list", mcp_types.ListResourceTemplatesResult),
    ("prompts/list", mcp_types.ListPromptsResult),
)


def test_gateway_mcp_replies_follow_the_mcp_schema(tmp_path: Path) -> None:
    image = os.environ.get("LOCALCLOUD_IMAGE", DEFAULT_IMAGE)
    with throwaway_runtime(
        tmp_path, "services:\n  enabled:\n    - gcs\n", images=(image,)
    ) as (controller, runtime, config, _paths):
        controller.start(
            config(), pull=False, tail=0, confirm_port_mapping=lambda _plan: True
        )
        current = runtime.resolve(config(), require=True)
        assert current is not None and current.url is not None
        client = JavaMcpClient(current.url, config().project, config().user)

        def reply(request_id: int, method: str, **params: Any) -> Any:
            response = client.forward(
                {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}
            )
            assert response is not None and "result" in response, response
            return response["result"]

        initialized = reply(
            1,
            "initialize",
            protocolVersion="2025-11-25",
            capabilities={},
            clientInfo={"name": "localcloud-contract-test", "version": "0"},
        )
        mcp_types.InitializeResult.model_validate(initialized)
        client.forward({"jsonrpc": "2.0", "method": "notifications/initialized"})
        for request_id, (method, model) in enumerate(_REPLIES, start=2):
            model.model_validate(reply(request_id, method))
