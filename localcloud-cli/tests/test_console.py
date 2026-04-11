"""Tests for localcloud.commands.console module."""

from unittest.mock import patch

from click.testing import CliRunner

from localcloud.commands.console import console


class TestConsoleCommand:
    """Tests for the console command."""

    def test_open_browser_default_port(self):
        """Console command opens browser at http://localhost:8080 by default."""
        runner = CliRunner()
        with patch("localcloud.commands.console.webbrowser.open") as mock_open:
            result = runner.invoke(console, [], obj=None, standalone_mode=False)
            assert result.exit_code == 0
            mock_open.assert_called_once_with("http://localhost:8080")

    def test_open_browser_custom_port(self):
        """Console command respects --port option."""
        runner = CliRunner()
        with patch("localcloud.commands.console.webbrowser.open") as mock_open:
            result = runner.invoke(console, ["--port", "9999"], obj=None, standalone_mode=False)
            assert result.exit_code == 0
            mock_open.assert_called_once_with("http://localhost:9999")

    def test_no_open_flag(self):
        """Console command with --no-open does not open browser."""
        runner = CliRunner()
        with patch("localcloud.commands.console.webbrowser.open") as mock_open:
            result = runner.invoke(console, ["--no-open"], obj=None, standalone_mode=False)
            assert result.exit_code == 0
            mock_open.assert_not_called()
            assert "http://localhost:8080" in result.output
