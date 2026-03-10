"""Logs command - view LocalCloud container logs."""

import sys

import click

from localcloud.docker_manager import DockerManager


@click.command()
@click.option("--follow", "-f", is_flag=True, help="Follow log output")
@click.option("--tail", default=100, type=int, help="Number of lines from end")
@click.pass_obj
def logs(ctx, follow, tail):
    """View emulator container logs."""
    dm = DockerManager(container_name=ctx.container_name)

    status_info = dm.status()
    if status_info["status"] == "not_found":
        click.echo("Error: LocalCloud container not found.", err=True)
        sys.exit(1)

    try:
        if follow:
            click.echo(f"Following logs for '{ctx.container_name}' (Ctrl+C to stop)...")
            log_stream = dm.logs(follow=True, tail=tail)
            for chunk in log_stream:
                text = chunk.decode("utf-8", errors="replace") if isinstance(chunk, bytes) else chunk
                click.echo(text, nl=False)
        else:
            output = dm.logs(follow=False, tail=tail)
            click.echo(output, nl=False)
    except KeyboardInterrupt:
        click.echo("\nLog streaming stopped.")
    except Exception as exc:
        click.echo(f"Error retrieving logs: {exc}", err=True)
        sys.exit(1)
