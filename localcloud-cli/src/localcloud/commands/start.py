"""Start command - launches the LocalCloud Docker container."""

import sys

import click

from localcloud.docker_manager import DockerManager
from localcloud.service_registry import get_registry


@click.command()
@click.option("--services", "-s", multiple=True, help="Services to start (default: all)")
@click.option("--seed", "seed_file", type=click.Path(exists=True), help="Seed file to load on startup")
@click.option("--detach/--no-detach", "-d", default=True, help="Run in background")
@click.option("--port", "-p", default=8080, type=int, help="Gateway port")
@click.option("--data-dir", default="./localcloud-data", type=click.Path(), help="Data directory")
@click.option("--image", default="localcloud/localcloud:latest", help="Docker image")
@click.pass_obj
def start(ctx, services, seed_file, detach, port, data_dir, image):
    """Start the LocalCloud emulator."""
    from rich.console import Console
    from rich.table import Table

    console = Console()

    dm = DockerManager(
        image=image,
        container_name=ctx.container_name,
        gateway_port=port,
    )

    # --- Pull image if needed ---
    console.print("[bold]Checking Docker image...[/bold]")
    try:
        if not dm.image_exists():
            console.print(f"Pulling image [cyan]{image}[/cyan] ...")
            dm.pull_image()
            console.print("[green]Image pulled successfully.[/green]")
        else:
            console.print(f"Image [cyan]{image}[/cyan] already available.")
    except Exception as exc:
        console.print(f"[red]Failed to pull image:[/red] {exc}")
        sys.exit(1)

    # --- Start container ---
    console.print("[bold]Starting LocalCloud container...[/bold]")
    try:
        dm.start(
            project_id=ctx.project_id,
            services=list(services) if services else None,
            data_dir=data_dir,
            detach=detach,
        )
    except RuntimeError as exc:
        console.print(f"[red]{exc}[/red]")
        sys.exit(1)
    except Exception as exc:
        console.print(f"[red]Failed to start container:[/red] {exc}")
        sys.exit(1)

    # --- Wait for health check ---
    console.print("Waiting for server to become healthy (up to 60s)...")
    try:
        health = dm.wait_until_healthy(max_wait=60)
    except TimeoutError as exc:
        console.print(f"[red]{exc}[/red]")
        console.print("Container started but server is not responding. Check logs with 'localcloud logs'.")
        sys.exit(1)

    # --- Print service table ---
    console.print()
    console.print(f"[bold green]LocalCloud is running![/bold green]  Project: [cyan]{ctx.project_id}[/cyan]")
    console.print()

    table = Table(title="Emulated Services")
    table.add_column("Service", style="cyan")
    table.add_column("Status", style="green")
    table.add_column("Port", justify="right")
    table.add_column("Env Var", style="dim")

    # Build a lookup: service_name -> env_var from the registry
    registry = get_registry()
    env_var_lookup = {}
    for svc in registry.all_services().values():
        key = "storage" if svc.id == "gcs" else svc.id
        env_var_lookup[key] = svc.env_var

    services_info = health.get("services", {})
    if services_info:
        for svc_name, svc_detail in services_info.items():
            svc_key = svc_name.lower()
            table.add_row(
                svc_name,
                svc_detail.get("status", "unknown"),
                str(svc_detail.get("port", "")),
                env_var_lookup.get(svc_key, ""),
            )
    else:
        table.add_row("(none registered yet)", "-", "-", "-")

    console.print(table)
    console.print()
    console.print("Tip: Run [bold]eval $(localcloud env)[/bold] to set environment variables.")
    console.print()

    # --- Seed data if requested ---
    if seed_file:
        console.print(f"Loading seed data from [cyan]{seed_file}[/cyan] ...")
        try:
            from localcloud.seed_processor import SeedProcessor

            processor = SeedProcessor(f"http://localhost:{port}")
            result = processor.load_seed_file(seed_file)
            console.print(f"[green]{result.get('message', 'Seed data loaded successfully.')}[/green]")
        except Exception as exc:
            console.print(f"[red]Failed to load seed data:[/red] {exc}")
