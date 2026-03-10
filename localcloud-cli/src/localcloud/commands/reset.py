"""Reset command - resets all LocalCloud services to empty or seed state."""

import sys

import click
import requests

from localcloud.docker_manager import DockerManager
from localcloud.seed_processor import SeedProcessor


@click.command()
@click.option("--seed", "seed_file", type=click.Path(exists=True), help="Seed file to restore after reset")
@click.option("--name", "container_name", default=None, help="Container name (default: localcloud-main)")
@click.option("--yes", "confirmed", is_flag=True, help="Skip confirmation prompt")
@click.pass_obj
def reset(ctx, seed_file, container_name, confirmed):
    """Reset all services to seed state or empty.

    This will delete ALL data in the running LocalCloud instance. If --seed
    is provided, the seed file will be loaded immediately after the reset.
    """
    from rich.console import Console

    console = Console()

    name = container_name or ctx.container_name
    dm = DockerManager(container_name=name, gateway_port=ctx.gateway_port)

    # Ensure the container is running.
    if not dm.is_running():
        console.print("[red]LocalCloud is not running.[/red] Start it first with 'localcloud start'.")
        sys.exit(1)

    # Confirm unless --yes was passed.
    if not confirmed:
        if not click.confirm("This will delete all data. Continue?"):
            console.print("[yellow]Reset cancelled.[/yellow]")
            return

    base_url = f"http://localhost:{dm.gateway_port}"

    # Call the reset endpoint.
    console.print("[bold]Resetting all services...[/bold]")
    try:
        resp = requests.post(f"{base_url}/_localcloud/reset", timeout=30.0)
        resp.raise_for_status()
    except Exception as exc:
        console.print(f"[red]Failed to reset:[/red] {exc}")
        sys.exit(1)

    console.print("[green]All services have been reset.[/green]")

    # Optionally re-seed after reset.
    if seed_file:
        console.print(f"[bold]Loading seed data from[/bold] [cyan]{seed_file}[/cyan] ...")
        processor = SeedProcessor(base_url)
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
