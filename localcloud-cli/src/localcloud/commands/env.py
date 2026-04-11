"""Env command - outputs environment variables for connecting to LocalCloud."""

import json
import sys

import click

from localcloud.docker_manager import DockerManager
from localcloud.service_registry import get_registry


def _build_env_vars(host: str = "localhost", project_id: str = "local-project") -> dict[str, str]:
    """Build the mapping of env-var name -> value from the service registry."""
    return get_registry().get_env_vars(host=host, project_id=project_id)


def _format_env_vars(env_vars: dict[str, str], fmt: str) -> str:
    """Format env vars dict into the requested output format."""
    if fmt == "json":
        return json.dumps(env_vars, indent=2)
    elif fmt == "docker-compose":
        lines = ["environment:"]
        for k, v in env_vars.items():
            lines.append(f"  - {k}={v}")
        return "\n".join(lines)
    else:  # shell
        lines = [f'export {k}="{v}"' for k, v in env_vars.items()]
        return "\n".join(lines) + "\n"


@click.command()
@click.option("--format", "fmt", type=click.Choice(["shell", "docker-compose", "json"]), default="shell", help="Output format")
@click.pass_obj
def env(ctx, fmt):
    """Output environment variables for connecting to LocalCloud."""
    dm = DockerManager(container_name=ctx.container_name, gateway_port=ctx.gateway_port)

    if not dm.is_running():
        click.echo("Error: LocalCloud is not running. Start it first with 'localcloud start'.", err=True)
        sys.exit(1)

    # Try to fetch from the running server first; fall back to local generation
    try:
        output = dm.get_env_vars(fmt=fmt)
        click.echo(output, nl=False)
    except Exception:
        # Server endpoint unavailable – generate locally from known ports
        env_vars = _build_env_vars(host="localhost", project_id=ctx.project_id)
        click.echo(_format_env_vars(env_vars, fmt), nl=False)
