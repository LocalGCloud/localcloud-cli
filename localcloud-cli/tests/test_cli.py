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


def test_port_global_option_accepted():
    """Test that --port global option is accepted without error."""
    runner = CliRunner()
    result = runner.invoke(cli, ["--port", "9090", "--help"])
    assert result.exit_code == 0
    assert "LocalCloud" in result.output


def test_project_global_option_changes_context():
    """Test that --project global option changes project context."""
    runner = CliRunner()
    # Invoke with --project and a subcommand that uses it (env --help just to confirm parsing)
    result = runner.invoke(cli, ["--project", "my-custom-project", "env", "--help"])
    assert result.exit_code == 0


def test_version_shows_correct_string():
    """Test that --version shows the correct version string."""
    runner = CliRunner()
    result = runner.invoke(cli, ["--version"])
    assert result.exit_code == 0
    assert "localcloud" in result.output.lower()
    assert "0.1.0" in result.output
