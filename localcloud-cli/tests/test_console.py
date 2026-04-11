"""Tests for localcloud.commands.console module."""

from localcloud.commands.console import find_console_dir


class TestFindConsoleDir:
    """Tests for the find_console_dir() function."""

    def test_returns_path_when_console_exists_in_source_tree(self, tmp_path, monkeypatch):
        """find_console_dir returns a valid path when localcloud-console exists
        in the expected source-tree-relative location.

        The function walks up from __file__ five levels, then looks for
        localcloud-console.  We simulate this by creating the expected
        directory structure and monkey-patching the module-level __file__.
        """
        # Build a fake source tree:
        # <tmp>/localcloud-console/
        # <tmp>/localcloud-cli/src/localcloud/commands/console.py
        console_pkg = tmp_path / "localcloud-console"
        console_pkg.mkdir()

        commands_dir = tmp_path / "localcloud-cli" / "src" / "localcloud" / "commands"
        commands_dir.mkdir(parents=True)
        fake_module = commands_dir / "console.py"
        fake_module.write_text("", encoding="utf-8")

        # Monkey-patch __file__ inside the console module
        import localcloud.commands.console as console_module
        monkeypatch.setattr(console_module, "__file__", str(fake_module))

        # Ensure env var is NOT set
        monkeypatch.delenv("LOCALCLOUD_CONSOLE_DIR", raising=False)

        result = find_console_dir()
        assert result is not None
        assert result.exists()
        assert result.name == "localcloud-console"

    def test_returns_path_from_env_var(self, tmp_path, monkeypatch):
        """find_console_dir returns the path from LOCALCLOUD_CONSOLE_DIR when set."""
        console_dir = tmp_path / "my-console"
        console_dir.mkdir()

        # Point __file__ somewhere that does NOT have localcloud-console nearby
        fake_file = tmp_path / "nowhere" / "a" / "b" / "c" / "d" / "console.py"
        fake_file.parent.mkdir(parents=True)
        fake_file.write_text("", encoding="utf-8")

        import localcloud.commands.console as console_module
        monkeypatch.setattr(console_module, "__file__", str(fake_file))
        monkeypatch.setenv("LOCALCLOUD_CONSOLE_DIR", str(console_dir))

        result = find_console_dir()
        assert result is not None
        assert result == console_dir

    def test_returns_none_when_nothing_available(self, tmp_path, monkeypatch):
        """find_console_dir returns None when neither source tree nor env var
        points to a valid directory."""
        # Point __file__ somewhere that has no localcloud-console sibling
        fake_file = tmp_path / "nowhere" / "a" / "b" / "c" / "d" / "console.py"
        fake_file.parent.mkdir(parents=True)
        fake_file.write_text("", encoding="utf-8")

        import localcloud.commands.console as console_module
        monkeypatch.setattr(console_module, "__file__", str(fake_file))
        monkeypatch.delenv("LOCALCLOUD_CONSOLE_DIR", raising=False)

        result = find_console_dir()
        assert result is None

    def test_env_var_with_nonexistent_path_returns_none(self, tmp_path, monkeypatch):
        """If LOCALCLOUD_CONSOLE_DIR points to a non-existent path and
        source tree also doesn't have it, returns None."""
        fake_file = tmp_path / "nowhere" / "a" / "b" / "c" / "d" / "console.py"
        fake_file.parent.mkdir(parents=True)
        fake_file.write_text("", encoding="utf-8")

        import localcloud.commands.console as console_module
        monkeypatch.setattr(console_module, "__file__", str(fake_file))
        monkeypatch.setenv("LOCALCLOUD_CONSOLE_DIR", "/tmp/does_not_exist_xyz_123")

        result = find_console_dir()
        assert result is None
