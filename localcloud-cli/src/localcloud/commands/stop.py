"""Stop command - stops the LocalCloud Docker container."""

import sys

import click

from localcloud.docker_manager import DockerManager


@click.command()
@click.option("--rm", "remove", is_flag=True, help="Remove container after stopping")
@click.pass_obj
def stop(ctx, remove):
    """Stop the LocalCloud emulator."""
    dm = DockerManager(container_name=ctx.container_name)

    if not dm.is_running():
        # Check if container exists but is already stopped
        status_info = dm.status()
        if status_info["status"] == "not_found":
            click.echo("LocalCloud container is not running (not found).")
        else:
            click.echo(f"LocalCloud container is already stopped (status: {status_info['status']}).")
            if remove:
                try:
                    dm.stop(remove=True)
                    click.echo("Container removed.")
                except Exception as exc:
                    click.echo(f"Error removing container: {exc}", err=True)
                    sys.exit(1)
        return

    click.echo("Stopping LocalCloud...")
    try:
        dm.stop(remove=remove)
        if remove:
            click.echo("LocalCloud stopped and container removed.")
        else:
            click.echo("LocalCloud stopped.")
    except Exception as exc:
        click.echo(f"Error stopping LocalCloud: {exc}", err=True)
        sys.exit(1)
