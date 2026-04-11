"""Expanded CLI command tests using Click's CliRunner."""

import pytest
from click.testing import CliRunner

from localcloud.cli import cli


@pytest.fixture
def runner():
    """Return a CliRunner instance."""
    return CliRunner()


# ---------------------------------------------------------------------------
# Top-level CLI
# ---------------------------------------------------------------------------

class TestCLITopLevel:
    """Tests for the top-level localcloud CLI group."""

    def test_help_shows_all_commands(self, runner):
        """localcloud --help lists all registered commands."""
        result = runner.invoke(cli, ["--help"])
        assert result.exit_code == 0
        for cmd in ("start", "stop", "status", "env", "seed", "reset", "logs", "console", "gcloud-setup"):
            assert cmd in result.output, f"Command '{cmd}' missing from --help output"

    def test_all_commands_are_registered(self, runner):
        """All expected commands are registered on the CLI group."""
        expected = {"start", "stop", "status", "env", "seed", "reset", "logs", "console"}
        registered = set(cli.commands.keys())
        assert expected.issubset(registered), f"Missing commands: {expected - registered}"

    def test_port_option_accepted(self, runner):
        """--port global option is accepted without error."""
        result = runner.invoke(cli, ["--port", "9090", "--help"])
        assert result.exit_code == 0

    def test_port_option_with_env_help(self, runner):
        """localcloud --port 9090 env --help still works."""
        result = runner.invoke(cli, ["--port", "9090", "env", "--help"])
        assert result.exit_code == 0


# ---------------------------------------------------------------------------
# env command
# ---------------------------------------------------------------------------

class TestEnvCommand:
    """Tests for the 'env' sub-command."""

    def test_env_help_shows_format_options(self, runner):
        """localcloud env --help shows the --format option."""
        result = runner.invoke(cli, ["env", "--help"])
        assert result.exit_code == 0
        assert "--format" in result.output
        assert "shell" in result.output
        assert "json" in result.output
        assert "docker-compose" in result.output


# ---------------------------------------------------------------------------
# console command
# ---------------------------------------------------------------------------

class TestConsoleCommand:
    """Tests for the 'console' sub-command."""

    def test_console_help_shows_port_option(self, runner):
        """localcloud console --help shows --port option."""
        result = runner.invoke(cli, ["console", "--help"])
        assert result.exit_code == 0
        assert "--port" in result.output

    def test_console_help_shows_open_option(self, runner):
        """localcloud console --help shows --open/--no-open option."""
        result = runner.invoke(cli, ["console", "--help"])
        assert result.exit_code == 0
        assert "--open" in result.output or "--no-open" in result.output


# ---------------------------------------------------------------------------
# seed command
# ---------------------------------------------------------------------------

class TestSeedCommand:
    """Tests for the 'seed' sub-command."""

    def test_seed_help_shows_seed_file_argument(self, runner):
        """localcloud seed --help shows SEED_FILE argument."""
        result = runner.invoke(cli, ["seed", "--help"])
        assert result.exit_code == 0
        assert "SEED_FILE" in result.output


# ---------------------------------------------------------------------------
# reset command
# ---------------------------------------------------------------------------

class TestResetCommand:
    """Tests for the 'reset' sub-command."""

    def test_reset_help_shows_yes_option(self, runner):
        """localcloud reset --help shows --yes option."""
        result = runner.invoke(cli, ["reset", "--help"])
        assert result.exit_code == 0
        assert "--yes" in result.output

    def test_reset_help_shows_seed_option(self, runner):
        """localcloud reset --help shows --seed option."""
        result = runner.invoke(cli, ["reset", "--help"])
        assert result.exit_code == 0
        assert "--seed" in result.output


# ---------------------------------------------------------------------------
# logs command
# ---------------------------------------------------------------------------

class TestLogsCommand:
    """Tests for the 'logs' sub-command."""

    def test_logs_help_shows_follow_option(self, runner):
        """localcloud logs --help shows --follow option."""
        result = runner.invoke(cli, ["logs", "--help"])
        assert result.exit_code == 0
        assert "--follow" in result.output or "-f" in result.output

    def test_logs_help_shows_tail_option(self, runner):
        """localcloud logs --help shows --tail option."""
        result = runner.invoke(cli, ["logs", "--help"])
        assert result.exit_code == 0
        assert "--tail" in result.output
