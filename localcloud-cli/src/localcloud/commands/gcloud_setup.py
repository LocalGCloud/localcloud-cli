"""gcloud-setup command - configures gcloud CLI to work with LocalCloud."""

import shutil
import subprocess
import sys

import click

from localcloud.service_registry import get_registry


CONFIG_NAME = "localcloud"


def _run_gcloud(*args: str) -> subprocess.CompletedProcess:
    """Run a gcloud command and return the result (does not raise on failure)."""
    return subprocess.run(
        ["gcloud", *args],
        capture_output=True,
        text=True,
    )


def _get_active_config() -> str:
    """Return the name of the currently active gcloud configuration."""
    result = _run_gcloud("config", "configurations", "list",
                         "--filter=is_active=true", "--format=value(name)")
    return result.stdout.strip() if result.returncode == 0 else "default"


def _config_exists() -> bool:
    """Check if the 'localcloud' gcloud configuration already exists."""
    result = _run_gcloud("config", "configurations", "list", "--format=value(name)")
    if result.returncode != 0:
        return False
    return CONFIG_NAME in result.stdout.strip().splitlines()


@click.command("gcloud-setup")
@click.option("--activate/--no-activate", default=True,
              help="Activate the localcloud configuration after creating it")
@click.option("--remove", is_flag=True, default=False,
              help="Remove the localcloud gcloud configuration")
@click.pass_obj
def gcloud_setup(ctx, activate, remove):
    """Configure gcloud CLI to work with LocalCloud.

    Creates a named gcloud configuration 'localcloud' with endpoint overrides
    pointing to the local emulators, auth bypass, and project set.

    Usage:
      localcloud gcloud-setup          # Create and activate
      localcloud gcloud-setup --remove # Remove configuration

    After setup:
      gcloud storage ls                # Works against LocalCloud
      gcloud secrets list              # Works against LocalCloud
      gcloud config configurations activate default  # Switch back to real GCP
    """
    # Check gcloud is installed
    if not shutil.which("gcloud"):
        click.echo("Error: gcloud CLI is not installed. Install it from https://cloud.google.com/sdk/docs/install", err=True)
        sys.exit(1)

    if remove:
        _remove_config()
        return

    _create_config(ctx, activate)


def _remove_config():
    """Remove the localcloud gcloud configuration."""
    if not _config_exists():
        click.echo("No 'localcloud' gcloud configuration found.")
        return

    # Switch away from localcloud if it's the active config
    if _get_active_config() == CONFIG_NAME:
        _run_gcloud("config", "configurations", "activate", "default")

    result = _run_gcloud("config", "configurations", "delete", CONFIG_NAME, "--quiet")
    if result.returncode == 0:
        click.echo("Removed 'localcloud' gcloud configuration.")
    else:
        click.echo(f"Error removing configuration: {result.stderr}", err=True)


def _create_config(ctx, activate):
    """Create and configure the localcloud gcloud configuration."""
    project_id = ctx.project_id
    registry = get_registry()

    # Remember the currently active config so we can restore it if --no-activate
    previous_config = _get_active_config()

    # Create configuration if it doesn't exist
    if not _config_exists():
        result = _run_gcloud("config", "configurations", "create", CONFIG_NAME)
        if result.returncode != 0:
            click.echo(f"Error creating configuration: {result.stderr}", err=True)
            sys.exit(1)
        click.echo(f"Created gcloud configuration '{CONFIG_NAME}'.")
    else:
        click.echo(f"Updating existing gcloud configuration '{CONFIG_NAME}'.")

    # Activate it temporarily to set properties
    _run_gcloud("config", "configurations", "activate", CONFIG_NAME)

    # Set project
    result = _run_gcloud("config", "set", "project", project_id)
    if result.returncode != 0:
        click.echo(f"Warning: failed to set project: {result.stderr}", err=True)

    # Set endpoint overrides for each service
    for svc in registry.all_services().values():
        if not svc.gcloud_api_name:
            continue
        endpoint = svc.gcloud_endpoint("localhost")
        prop_name = f"api_endpoint_overrides/{svc.gcloud_api_name}"
        result = _run_gcloud("config", "set", prop_name, endpoint)
        if result.returncode == 0:
            click.echo(f"  {svc.gcloud_api_name}: {endpoint}")
        else:
            click.echo(f"  {svc.gcloud_api_name}: FAILED ({result.stderr.strip()})", err=True)

    if not activate:
        # Restore the previously active config
        _run_gcloud("config", "configurations", "activate", previous_config)
        click.echo(f"\nConfiguration '{CONFIG_NAME}' created but not activated.")
        click.echo(f"Activate with: gcloud config configurations activate {CONFIG_NAME}")
    else:
        click.echo(f"\nConfiguration '{CONFIG_NAME}' is now active.")
        click.echo("gcloud commands will target LocalCloud emulators.")
        click.echo("\nNote: You also need to set the auth bypass in your shell:")
        click.echo("  export CLOUDSDK_AUTH_ACCESS_TOKEN=localcloud-dev-token")

    click.echo("\nTo switch back to real GCP:")
    click.echo(f"  gcloud config configurations activate {previous_config}")
    click.echo("  unset CLOUDSDK_AUTH_ACCESS_TOKEN")
    click.echo("\nAlternatively, use environment variables instead (no config changes):")
    click.echo("  eval $(localcloud env)")
