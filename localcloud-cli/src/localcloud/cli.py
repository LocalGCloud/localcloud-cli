"""LocalCloud CLI entry point."""

import click

from localcloud.commands import compose, env, gcloud_setup, logs, reset, seed, start, status, stop, console


class LocalCloudContext:
    """Shared context for all CLI commands."""

    def __init__(self):
        self.project_id = "local-project"
        self.container_name = "localcloud-main"
        self.gateway_port = 8080
        self.image = "localcloud/localcloud:latest"


@click.group()
@click.option(
    "--project",
    envvar="LOCALCLOUD_PROJECT",
    default="local-project",
    help="GCP project ID",
)
@click.option(
    "--name",
    "container_name",
    envvar="LOCALCLOUD_CONTAINER_NAME",
    default="localcloud-main",
    help="Docker container name",
)
@click.option(
    "--port",
    type=int,
    envvar="LOCALCLOUD_PORT",
    default=8080,
    help="Gateway port",
)
@click.version_option(version="0.1.0", prog_name="localcloud")
@click.pass_context
def cli(ctx, project, container_name, port):
    """LocalCloud - Local GCP Emulator.

    Simulate Google Cloud Platform services locally for development and testing.
    """
    ctx.ensure_object(LocalCloudContext)
    ctx.obj.project_id = project
    ctx.obj.container_name = container_name
    ctx.obj.gateway_port = port


cli.add_command(start.start)
cli.add_command(stop.stop)
cli.add_command(status.status)
cli.add_command(env.env)
cli.add_command(seed.seed)
cli.add_command(reset.reset)
cli.add_command(logs.logs)
cli.add_command(console.console)
cli.add_command(compose.compose)
cli.add_command(gcloud_setup.gcloud_setup)
