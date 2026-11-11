from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import pytest

from localcloud_cli.docker_runtime import DockerRuntime

# The canonical LocalCloud ports and the margin above them: a developer's own runtime lives here.
RESERVED_HOST_PORTS = range(5380, 5411)


def reserved_ports_in(port_range: tuple[int, int] | None) -> bool:
    if port_range is None:
        return True
    start, end = port_range
    return start <= RESERVED_HOST_PORTS[-1] and end >= RESERVED_HOST_PORTS[0]


@pytest.fixture(autouse=True, scope="module")
def _never_publish_on_canonical_ports() -> Iterator[None]:
    """Every runtime an integration test creates in this process has an explicit port range
    clear of 5380-5410, so no code path can fall back to the canonical or fallback ports.

    Runs in subprocesses carry the range in their localcloud.yaml and on `start`."""
    plan = DockerRuntime._port_plan

    def guarded(self: DockerRuntime, config: Any, **kwargs: Any) -> Any:
        if reserved_ports_in(config.port_range):
            pytest.fail(
                f"integration runtime {config.data_volume} has port_range={config.port_range}; "
                "set one clear of 5380-5410"
            )
        planned = plan(self, config, **kwargs)
        bindings, mysql_port, _busy = planned
        host_ports = [
            port for binding in bindings.values() for _ip, port in binding if port is not None
        ]
        if mysql_port is not None:
            host_ports.append(mysql_port)
        reserved = sorted(port for port in host_ports if port in RESERVED_HOST_PORTS)
        if reserved:
            pytest.fail(f"integration runtime {config.data_volume} would publish {reserved}")
        return planned

    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(DockerRuntime, "_port_plan", guarded)
        yield
