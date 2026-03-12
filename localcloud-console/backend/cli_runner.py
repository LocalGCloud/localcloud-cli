"""Wrapper for LocalCloud CLI commands."""

import subprocess
import json
import os

class CLIRunner:
    """Execute LocalCloud CLI commands."""

    def __init__(self, project_id="local-project"):
        self.project_id = project_id

    def run_command(self, *args):
        """Run a localcloud CLI command and return output."""
        cmd = ["localcloud", "--project", self.project_id] + list(args)
        try:
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=30
            )
            return {
                "returncode": result.returncode,
                "stdout": result.stdout,
                "stderr": result.stderr,
                "success": result.returncode == 0
            }
        except subprocess.TimeoutExpired:
            return {
                "success": False,
                "error": "Command timed out"
            }
        except Exception as e:
            return {
                "success": False,
                "error": str(e)
            }

    def status(self):
        """Get LocalCloud status."""
        return self.run_command("status", "--format", "json")

    def logs(self, lines=100, follow=False):
        """Get container logs."""
        args = ["logs", "--tail", str(lines)]
        if follow:
            args.append("--follow")
        return self.run_command(*args)

    def start(self, services=None):
        """Start LocalCloud."""
        args = ["start"]
        if services:
            args.extend(["--services", ",".join(services)])
        return self.run_command(*args)

    def stop(self):
        """Stop LocalCloud."""
        return self.run_command("stop")

    def reset(self):
        """Reset all services."""
        return self.run_command("reset", "--yes")
