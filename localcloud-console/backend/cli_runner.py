"""Wrapper for LocalCloud CLI commands."""

import logging
import subprocess
from typing import Dict, Any, List, Optional

logger = logging.getLogger(__name__)


class CLIRunner:
    """Execute LocalCloud CLI commands."""

    def __init__(self, project_id: str = "local-project") -> None:
        """Initialize CLI runner.

        Args:
            project_id: GCP project ID to use for all CLI commands
        """
        self.project_id = project_id

    def run_command(self, *args) -> Dict[str, Any]:
        """Run a localcloud CLI command and return output.

        Args:
            *args: Command arguments to pass to localcloud CLI

        Returns:
            Dict with returncode, stdout, stderr, success, and error fields
        """
        cmd = ["localcloud", "--project", self.project_id] + list(args)
        logger.info(f"Running CLI command: {' '.join(cmd)}")
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
            logger.error(f"CLI command timed out: {' '.join(cmd)}")
            return {
                "success": False,
                "error": "Command timed out"
            }
        except Exception as e:
            logger.error(f"Error running CLI command: {e}", exc_info=True)
            return {
                "success": False,
                "error": str(e)
            }

    def status(self) -> Dict[str, Any]:
        """Get LocalCloud status.

        Returns:
            Dict with status information in JSON format
        """
        return self.run_command("status", "--format", "json")

    def logs(self, lines: int = 100, follow: bool = False) -> Dict[str, Any]:
        """Get container logs.

        Args:
            lines: Number of log lines to retrieve (default: 100)
            follow: Whether to follow log output (default: False)

        Returns:
            Dict with stdout, stderr, and success status
        """
        args = ["logs", "--tail", str(lines)]
        if follow:
            args.append("--follow")
        return self.run_command(*args)

    def start(self, services: Optional[List[str]] = None) -> Dict[str, Any]:
        """Start LocalCloud or specific services.

        Args:
            services: Optional list of service names to start.
                     If None, starts all services.

        Returns:
            Dict with success status and output
        """
        args = ["start"]
        if services:
            args.extend(["--services", ",".join(services)])
        return self.run_command(*args)

    def stop(self) -> Dict[str, Any]:
        """Stop LocalCloud.

        Returns:
            Dict with success status and output
        """
        return self.run_command("stop")

    def reset(self) -> Dict[str, Any]:
        """Reset all services.

        Returns:
            Dict with success status and output
        """
        return self.run_command("reset", "--yes")
