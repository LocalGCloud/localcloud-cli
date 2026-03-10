"""Status command - shows status of the LocalCloud emulator and services."""

import json
import sys

import click

from localcloud.docker_manager import DockerManager


@click.command()
@click.option("--format", "fmt", type=click.Choice(["table", "json"]), default="table", help="Output format")
@click.pass_obj
def status(ctx, fmt):
    """Show status of all emulated services."""
    from rich.console import Console
    from rich.table import Table

    console = Console()
    dm = DockerManager(container_name=ctx.container_name, gateway_port=ctx.gateway_port)

    # Check container status first
    container_status = dm.status()
    if container_status["status"] == "not_found":
        if fmt == "json":
            click.echo(json.dumps({"status": "not_found", "services": {}}, indent=2))
        else:
            console.print("[yellow]LocalCloud container is not running.[/yellow]")
        return

    if container_status["status"] != "running":
        if fmt == "json":
            click.echo(json.dumps({"status": container_status["status"], "services": {}}, indent=2))
        else:
            console.print(f"[yellow]LocalCloud container exists but is {container_status['status']}.[/yellow]")
        return

    # Query health endpoint
    try:
        health = dm.health_check()
    except Exception as exc:
        if fmt == "json":
            click.echo(json.dumps({"status": "error", "message": str(exc)}, indent=2))
        else:
            console.print(f"[red]Failed to query server health:[/red] {exc}")
        sys.exit(1)

    if fmt == "json":
        click.echo(json.dumps(health, indent=2))
        return

    # Table output
    console.print()
    status_str = health.get("status", "unknown")
    color = "green" if status_str == "healthy" else "red"
    console.print(f"Server status: [bold {color}]{status_str}[/bold {color}]")
    console.print(f"Project ID:    [cyan]{health.get('project_id', 'unknown')}[/cyan]")
    console.print(f"Uptime:        {health.get('uptime_seconds', 0)}s")
    console.print(f"Persistence:   {'enabled' if health.get('persistence') else 'disabled'}")
    console.print()

    services_info = health.get("services", {})
    if not services_info:
        console.print("[dim]No services registered.[/dim]")
        return

    table = Table(title="Services")
    table.add_column("Service", style="cyan")
    table.add_column("Status", style="green")
    table.add_column("Port", justify="right")
    table.add_column("Protocol")
    table.add_column("Requests", justify="right")

    for svc_name, svc_detail in services_info.items():
        svc_status = svc_detail.get("status", "unknown")
        style = "green" if svc_status == "running" else "red"
        table.add_row(
            svc_name,
            f"[{style}]{svc_status}[/{style}]",
            str(svc_detail.get("port", "")),
            svc_detail.get("protocol", ""),
            str(svc_detail.get("request_count", 0)),
        )

    console.print(table)
    console.print()
