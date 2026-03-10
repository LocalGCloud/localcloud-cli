"""Seed command - loads seed data from a YAML file into LocalCloud."""

import sys

import click

from localcloud.docker_manager import DockerManager
from localcloud.seed_processor import SeedProcessor


@click.command()
@click.argument("seed_file", type=click.Path(exists=True))
@click.option("--name", "container_name", default=None, help="Container name (default: localcloud-main)")
@click.option("--clear-first", is_flag=True, help="Clear existing data before seeding")
@click.pass_obj
def seed(ctx, seed_file, container_name, clear_first):
    """Load seed data from a YAML file.

    SEED_FILE is the path to the seed YAML file to load.
    """
    from rich.console import Console

    console = Console()

    name = container_name or ctx.container_name
    dm = DockerManager(container_name=name, gateway_port=ctx.gateway_port)

    # Ensure the container is running before we try to seed.
    if not dm.is_running():
        console.print("[red]LocalCloud is not running.[/red] Start it first with 'localcloud start'.")
        sys.exit(1)

    base_url = f"http://localhost:{dm.gateway_port}"
    processor = SeedProcessor(base_url)

    # Optionally clear existing data first by calling the reset endpoint.
    if clear_first:
        console.print("[bold]Clearing existing data...[/bold]")
        try:
            import requests

            resp = requests.post(f"{base_url}/_localcloud/reset", timeout=30.0)
            resp.raise_for_status()
            console.print("[green]Data cleared.[/green]")
        except Exception as exc:
            console.print(f"[red]Failed to clear data:[/red] {exc}")
            sys.exit(1)

    # Validate and load the seed file.
    console.print(f"[bold]Loading seed data from[/bold] [cyan]{seed_file}[/cyan] ...")

    try:
        result = processor.load_seed_file(seed_file)
    except FileNotFoundError as exc:
        console.print(f"[red]File not found:[/red] {exc}")
        sys.exit(1)
    except ValueError as exc:
        console.print(f"[red]Validation error:[/red] {exc}")
        sys.exit(1)
    except Exception as exc:
        console.print(f"[red]Failed to load seed data:[/red] {exc}")
        sys.exit(1)

    console.print(f"[green]{result.get('message', 'Seed data loaded successfully.')}[/green]")
