"""Basic CLI tests."""

from click.testing import CliRunner

from localcloud.cli import cli


def test_cli_help():
    """Test that the CLI shows help text."""
    runner = CliRunner()
    result = runner.invoke(cli, ["--help"])
    assert result.exit_code == 0
    assert "LocalCloud" in result.output


def test_cli_version():
    """Test that the CLI shows version."""
    runner = CliRunner()
    result = runner.invoke(cli, ["--version"])
    assert result.exit_code == 0
    assert "0.1.0" in result.output


def test_start_help():
    """Test that the start command shows help."""
    runner = CliRunner()
    result = runner.invoke(cli, ["start", "--help"])
    assert result.exit_code == 0
    assert "Start the LocalCloud emulator" in result.output


def test_stop_help():
    """Test that the stop command shows help."""
    runner = CliRunner()
    result = runner.invoke(cli, ["stop", "--help"])
    assert result.exit_code == 0
    assert "Stop the LocalCloud emulator" in result.output


def test_status_help():
    """Test that the status command shows help."""
    runner = CliRunner()
    result = runner.invoke(cli, ["status", "--help"])
    assert result.exit_code == 0
    assert "Show status of all emulated services" in result.output
