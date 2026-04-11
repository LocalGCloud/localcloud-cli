"""Open LocalCloud web console."""

import click
import webbrowser


@click.command()
@click.option(
    '--port',
    type=int,
    default=8080,
    help='Gateway port where console is served (default 8080)'
)
@click.option(
    '--open/--no-open',
    default=True,
    help='Automatically open browser (default true)'
)
@click.pass_context
def console(ctx, port, open):
    """Open the LocalCloud web console.

    Opens the console UI served by the LocalCloud gateway on port 8080.
    The console is bundled into the Docker container and served directly
    by the Armeria gateway — no separate server process is needed.
    """
    url = f"http://localhost:{port}"

    if open:
        click.echo(f"Opening LocalCloud Console at {url}")
        webbrowser.open(url)
    else:
        click.echo(f"LocalCloud Console available at {url}")
