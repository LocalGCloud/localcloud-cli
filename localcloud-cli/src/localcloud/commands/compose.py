"""Generate a docker-compose.yml with ports for selected services only."""

from pathlib import Path

import click

from localcloud.service_registry import get_registry


@click.command()
@click.option(
    "--services",
    default=None,
    help="Comma-separated list of services to enable (default: from services.yaml)",
)
@click.option(
    "--output",
    "-o",
    default="docker-compose.yml",
    help="Output file path (default: docker-compose.yml)",
)
@click.option(
    "--print",
    "print_only",
    is_flag=True,
    default=False,
    help="Print to stdout instead of writing to file",
)
@click.pass_context
def compose(ctx, services, output, print_only):
    """Generate a docker-compose.yml with only the ports you need.

    Reads services.yaml to determine which ports to expose based on
    the selected services. If no --services flag is given, uses the
    defaultEnabled services from services.yaml.

    \b
    Examples:
      localcloud compose                              # default services
      localcloud compose --services gcs,pubsub        # minimal
      localcloud compose --services gcs,pubsub --print  # preview
    """
    registry = get_registry()
    project_id = ctx.obj.project_id if ctx.obj else "local-project"

    enabled = [s.strip() for s in services.split(",")] if services else None
    yaml_content = registry.generate_compose_yaml(
        enabled_services=enabled,
        project_id=project_id,
    )

    if print_only:
        click.echo(yaml_content)
    else:
        out_path = Path(output)
        out_path.write_text(yaml_content, encoding="utf-8")
        # Show summary
        port_mappings = registry.get_port_mappings(enabled_services=enabled)
        svc_list = enabled or registry.get_default_enabled()
        click.echo(f"Generated {out_path} with {len(svc_list)} services, {len(port_mappings)} ports")
        click.echo(f"  Services: {', '.join(svc_list)}")
        click.echo(f"  Ports: {', '.join(str(p) for p in sorted(port_mappings.keys()))}")
        click.echo("\nRun: docker compose up -d")
