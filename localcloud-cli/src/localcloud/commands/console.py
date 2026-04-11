"""Open LocalCloud web console."""

import click
import subprocess
import sys
import time
import os
import webbrowser
from pathlib import Path


def find_console_dir():
    """Locate the localcloud-console directory.

    Checks in order:
    1. Development layout (relative to source tree)
    2. LOCALCLOUD_CONSOLE_DIR environment variable
    """
    # Try development layout (running from source)
    source_dir = Path(__file__).resolve().parent.parent.parent.parent.parent
    console_dir = source_dir / "localcloud-console"
    if console_dir.exists():
        return console_dir

    # Try environment variable
    env_dir = os.environ.get("LOCALCLOUD_CONSOLE_DIR")
    if env_dir and Path(env_dir).exists():
        return Path(env_dir)

    # Not found
    return None


@click.command()
@click.option(
    '--port',
    type=int,
    default=9090,
    help='Port for console server (default 9090)'
)
@click.option(
    '--open/--no-open',
    default=True,
    help='Automatically open browser (default true)'
)
@click.pass_context
def console(ctx, port, open):
    """Open the LocalCloud web console.

    Starts a lightweight Flask server on port 9090 serving the Solid.js UI.
    """
    try:
        console_dir = find_console_dir()
        if console_dir is None:
            click.echo("Error: LocalCloud Console not found.", err=True)
            click.echo("Set LOCALCLOUD_CONSOLE_DIR environment variable to the console directory.", err=True)
            sys.exit(1)

        backend_app = console_dir / "backend" / "app.py"
        if not backend_app.exists():
            click.echo("Error: Console backend not found. Please build it first.", err=True)
            sys.exit(1)

        click.echo(f"Starting LocalCloud Console on http://localhost:{port}")

        # Open browser if requested (start in background)
        if open:
            # Wait a bit for server to start, then open
            def open_browser():
                time.sleep(1)
                webbrowser.open(f"http://localhost:{port}")

            import threading
            thread = threading.Thread(target=open_browser, daemon=True)
            thread.start()

        # Prepare environment
        env = os.environ.copy()
        env["CONSOLE_PORT"] = str(port)
        env["LOCALCLOUD_PROJECT"] = ctx.obj.project_id if ctx.obj else "local-project"

        # Run the Flask app
        subprocess.run(
            [sys.executable, str(backend_app)],
            env=env,
            cwd=str(console_dir)
        )
    except KeyboardInterrupt:
        click.echo("\nConsole stopped.")
    except Exception as e:
        click.echo(f"Error: {e}", err=True)
        sys.exit(1)
