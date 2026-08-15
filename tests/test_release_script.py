from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SCRIPT = PROJECT_ROOT / "scripts" / "release.sh"


def run_script(
    *args: str,
    script: Path = SCRIPT,
    cwd: Path = PROJECT_ROOT,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["/bin/sh", str(script), *args],
        cwd=cwd,
        env=env,
        text=True,
        capture_output=True,
        check=False,
    )


def test_help_documents_build_and_release_modes() -> None:
    result = run_script("--help")

    assert result.returncode == 0
    assert "--build-only" in result.stdout
    assert "--release VERSION" in result.stdout
    assert "default: build for the current platform" in result.stdout


@pytest.mark.parametrize(
    "args",
    [
        ("--release",),
        ("--release", "v1.2.3"),
        ("--release", "1.2"),
        ("--release", "1.2.3-rc.1"),
        ("--release", "01.2.3"),
        ("--release", "1.02.3"),
        ("--release", "1.2.03"),
        ("--release", "1.2.3", "extra"),
        ("--build-only", "extra"),
        ("--unknown",),
    ],
)
def test_invalid_arguments_fail_with_usage(args: tuple[str, ...]) -> None:
    result = run_script(*args)

    assert result.returncode == 2
    assert "Usage:" in result.stderr


def write_executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


def native_build_project(tmp_path: Path) -> tuple[Path, dict[str, str], Path]:
    project = tmp_path / "project"
    scripts = project / "scripts"
    scripts.mkdir(parents=True)
    script = scripts / "release.sh"
    shutil.copy2(SCRIPT, script)
    (project / "THIRD_PARTY_NOTICES").write_text("locked notices\n", encoding="utf-8")
    (project / "localcloud.spec").write_text("# test spec\n", encoding="utf-8")

    command_log = tmp_path / "commands.log"
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    write_executable(
        bin_dir / "uv",
        """#!/bin/sh
set -eu
printf 'uv %s\\n' "$*" >> "$COMMAND_LOG"
if [ -n "${UV_FAIL_PATTERN:-}" ]; then
    case "$*" in
        *"$UV_FAIL_PATTERN"*) exit 9 ;;
    esac
fi

previous=
for argument in "$@"; do
    if [ "$previous" = "--output" ]; then
        cp THIRD_PARTY_NOTICES "$argument"
    fi
    previous=$argument
done

case " $* " in
    *" -m PyInstaller "*)
        mkdir -p dist
        cat > dist/localcloud <<'EOF'
#!/bin/sh
printf 'binary %s\\n' "$*" >> "$COMMAND_LOG"
if [ "${1:-}" = "--version" ]; then
    printf 'localcloud %s\n' "${CLI_VERSION:-0.1.0}"
fi
EOF
        chmod +x dist/localcloud
        ;;
esac
""",
    )
    env = os.environ.copy()
    env["COMMAND_LOG"] = str(command_log)
    env["PATH"] = f"{bin_dir}{os.pathsep}{env['PATH']}"
    return script, env, command_log


@pytest.mark.parametrize("args", [(), ("--build-only",)])
def test_native_build_modes_validate_build_and_smoke(
    tmp_path: Path, args: tuple[str, ...]
) -> None:
    script, env, command_log = native_build_project(tmp_path)
    project = script.parents[1]
    notices_before = (project / "THIRD_PARTY_NOTICES").read_bytes()

    result = run_script(*args, script=script, cwd=tmp_path, env=env)

    assert result.returncode == 0, result.stderr
    assert (project / "dist" / "localcloud").is_file()
    assert (project / "THIRD_PARTY_NOTICES").read_bytes() == notices_before
    commands = command_log.read_text(encoding="utf-8").splitlines()
    assert commands[0] == "uv lock --check"
    assert commands[1].startswith(
        "uv run --frozen --extra release python "
        "scripts/generate-third-party-notices.py --output "
    )
    assert commands[2] == (
        "uv run --frozen --extra release python -m PyInstaller "
        "--clean --noconfirm localcloud.spec"
    )
    assert commands[3:] == [
        "binary --version",
        "binary --help",
        "binary guide",
    ]


def git(project: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=project,
        text=True,
        capture_output=True,
        check=True,
    )


def release_project(
    tmp_path: Path, *, source_version: str = "1.2.3"
) -> tuple[Path, dict[str, str], Path]:
    script, env, command_log = native_build_project(tmp_path)
    project = script.parents[1]
    package = project / "src" / "localcloud_cli"
    package.mkdir(parents=True)
    (package / "__init__.py").write_text(
        f'__version__ = "{source_version}"\n', encoding="utf-8"
    )
    (project / "uv.lock").write_text("test lock\n", encoding="utf-8")
    (project / ".gitignore").write_text("build/\ndist/\n", encoding="utf-8")

    bin_dir = Path(env["PATH"].split(os.pathsep, maxsplit=1)[0])
    write_executable(
        bin_dir / "gh",
        """#!/bin/sh
set -eu
printf 'gh %s\\n' "$*" >> "$COMMAND_LOG"

case "${1:-} ${2:-}" in
    "auth status")
        exit 0
        ;;
    "api graphql")
        [ "${GH_API_FAIL:-0}" = "0" ] || exit 8
        if [ "${GH_RELEASE_EXISTS:-0}" = "1" ]; then
            printf 'v%s\n' "$CLI_VERSION"
        fi
        ;;
    "release view")
        case " $* " in
            *" --json assets "*)
                cat "$GH_ASSETS"
                exit 0
                ;;
            *" --json url "*)
                printf 'https://github.com/LocalGCloud/localcloud-cli/releases/tag/v%s\\n' "$CLI_VERSION"
                exit 0
                ;;
        esac
        exit 1
        ;;
    "workflow run")
        case ${3:-} in
            cli-release.yml)
                printf 'https://github.com/LocalGCloud/localcloud-cli/actions/runs/101\\n'
                ;;
            publish-formula.yml)
                printf 'https://github.com/LocalGCloud/homebrew-tap/actions/runs/202\\n'
                ;;
            *)
                exit 1
                ;;
        esac
        ;;
    "run watch")
        case ${3:-} in
            101)
                [ "${GH_FAIL_CLI_RUN:-0}" = "0" ]
                ;;
            202)
                [ "${GH_FAIL_TAP_RUN:-0}" = "0" ]
                ;;
            *)
                exit 1
                ;;
        esac
        ;;
    *)
        exit 1
        ;;
esac
""",
    )
    assets = tmp_path / "assets.txt"
    assets.write_text(
        "\n".join(
            [
                "SHA256SUMS",
                "SHA256SUMS.sigstore.json",
                "localcloud-darwin-amd64.tar.gz",
                "localcloud-darwin-amd64.tar.gz.sigstore.json",
                "localcloud-darwin-arm64.tar.gz",
                "localcloud-darwin-arm64.tar.gz.sigstore.json",
                "localcloud-linux-amd64.tar.gz",
                "localcloud-linux-amd64.tar.gz.sigstore.json",
                "localcloud-linux-arm64.tar.gz",
                "localcloud-linux-arm64.tar.gz.sigstore.json",
                "localcloud.rb",
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    env["CLI_VERSION"] = source_version
    env["GH_ASSETS"] = str(assets)

    git(project, "init", "-b", "main")
    git(project, "config", "user.name", "Release Test")
    git(project, "config", "user.email", "release@example.invalid")
    git(project, "config", "commit.gpgsign", "false")
    git(project, "config", "tag.gpgsign", "false")
    git(project, "add", ".")
    git(project, "commit", "-m", "Prepare release")
    origin = tmp_path / "origin.git"
    subprocess.run(
        ["git", "init", "--bare", str(origin)],
        text=True,
        capture_output=True,
        check=True,
    )
    git(project, "remote", "add", "origin", str(origin))
    git(project, "push", "-u", "origin", "main")
    return script, env, command_log


def assert_no_release_mutation(command_log: Path) -> None:
    if command_log.exists():
        commands = command_log.read_text(encoding="utf-8")
        assert "workflow run" not in commands


def test_release_refuses_dirty_working_tree(tmp_path: Path) -> None:
    script, env, command_log = release_project(tmp_path)
    project = script.parents[1]
    (project / "untracked.txt").write_text("dirty\n", encoding="utf-8")

    result = run_script("--release", "1.2.3", script=script, cwd=tmp_path, env=env)

    assert result.returncode == 1
    assert "working tree must be clean" in result.stderr
    assert_no_release_mutation(command_log)


def test_release_requires_main_branch(tmp_path: Path) -> None:
    script, env, command_log = release_project(tmp_path)
    project = script.parents[1]
    git(project, "switch", "-c", "feature")

    result = run_script("--release", "1.2.3", script=script, cwd=tmp_path, env=env)

    assert result.returncode == 1
    assert "branch main" in result.stderr
    assert_no_release_mutation(command_log)


def test_release_requires_head_at_origin_main(tmp_path: Path) -> None:
    script, env, command_log = release_project(tmp_path)
    project = script.parents[1]
    (project / "committed.txt").write_text("local only\n", encoding="utf-8")
    git(project, "add", "committed.txt")
    git(project, "commit", "-m", "Local commit")

    result = run_script("--release", "1.2.3", script=script, cwd=tmp_path, env=env)

    assert result.returncode == 1
    assert "origin/main" in result.stderr
    assert_no_release_mutation(command_log)


def test_release_requires_matching_source_version(tmp_path: Path) -> None:
    script, env, command_log = release_project(tmp_path, source_version="1.2.2")

    result = run_script("--release", "1.2.3", script=script, cwd=tmp_path, env=env)

    assert result.returncode == 1
    assert "source version 1.2.2 does not match 1.2.3" in result.stderr
    assert_no_release_mutation(command_log)


def test_release_tags_dispatches_verifies_and_publishes_tap(tmp_path: Path) -> None:
    script, env, command_log = release_project(tmp_path)
    project = script.parents[1]

    result = run_script("--release", "1.2.3", script=script, cwd=tmp_path, env=env)

    assert result.returncode == 0, result.stderr
    head = git(project, "rev-parse", "HEAD").stdout.strip()
    assert git(project, "rev-parse", "v1.2.3^{}").stdout.strip() == head
    origin = git(project, "remote", "get-url", "origin").stdout.strip()
    remote_tag = subprocess.run(
        ["git", "--git-dir", origin, "rev-parse", "v1.2.3^{}"],
        text=True,
        capture_output=True,
        check=True,
    ).stdout.strip()
    assert remote_tag == head

    commands = command_log.read_text(encoding="utf-8").splitlines()
    cli_dispatch = commands.index(
        "gh workflow run cli-release.yml "
        "--repo LocalGCloud/localcloud-cli --ref v1.2.3"
    )
    cli_watch = commands.index(
        "gh run watch 101 --repo LocalGCloud/localcloud-cli --exit-status"
    )
    asset_check = commands.index(
        "gh release view v1.2.3 --repo LocalGCloud/localcloud-cli "
        "--json assets --jq .assets[].name"
    )
    tap_dispatch = commands.index(
        "gh workflow run publish-formula.yml "
        "--repo LocalGCloud/homebrew-tap -f version=1.2.3"
    )
    tap_watch = commands.index(
        "gh run watch 202 --repo LocalGCloud/homebrew-tap --exit-status"
    )
    assert cli_dispatch < cli_watch < asset_check < tap_dispatch < tap_watch
    assert "Release 1.2.3 completed." in result.stdout
    assert (
        "https://github.com/LocalGCloud/localcloud-cli/releases/tag/v1.2.3"
        in result.stdout
    )


def test_release_reuses_matching_published_tag(tmp_path: Path) -> None:
    script, env, _ = release_project(tmp_path)
    project = script.parents[1]
    git(project, "tag", "-a", "v1.2.3", "-m", "Release LocalCloud CLI 1.2.3")
    git(project, "push", "origin", "refs/tags/v1.2.3")
    tag_object = git(project, "rev-parse", "refs/tags/v1.2.3").stdout.strip()

    result = run_script("--release", "1.2.3", script=script, cwd=tmp_path, env=env)

    assert result.returncode == 0, result.stderr
    assert "Reusing tag v1.2.3" in result.stdout
    assert git(project, "rev-parse", "refs/tags/v1.2.3").stdout.strip() == tag_object


def test_release_refuses_conflicting_tag(tmp_path: Path) -> None:
    script, env, command_log = release_project(tmp_path)
    project = script.parents[1]
    git(project, "tag", "-a", "v1.2.3", "-m", "Wrong release commit")
    git(project, "push", "origin", "refs/tags/v1.2.3")
    (project / "committed.txt").write_text("new release commit\n", encoding="utf-8")
    git(project, "add", "committed.txt")
    git(project, "commit", "-m", "Advance release source")
    git(project, "push", "origin", "main")

    result = run_script("--release", "1.2.3", script=script, cwd=tmp_path, env=env)

    assert result.returncode == 1
    assert "tag v1.2.3 does not point to the release commit" in result.stderr
    assert_no_release_mutation(command_log)


def test_release_validation_failure_does_not_create_tag(tmp_path: Path) -> None:
    script, env, command_log = release_project(tmp_path)
    project = script.parents[1]
    env["UV_FAIL_PATTERN"] = "python -m pytest"

    result = run_script("--release", "1.2.3", script=script, cwd=tmp_path, env=env)

    assert result.returncode == 9
    tag_lookup = subprocess.run(
        ["git", "rev-parse", "-q", "--verify", "refs/tags/v1.2.3"],
        cwd=project,
        text=True,
        capture_output=True,
        check=False,
    )
    assert tag_lookup.returncode != 0
    assert_no_release_mutation(command_log)


def test_release_refuses_existing_github_release(tmp_path: Path) -> None:
    script, env, command_log = release_project(tmp_path)
    project = script.parents[1]
    env["GH_RELEASE_EXISTS"] = "1"

    result = run_script("--release", "1.2.3", script=script, cwd=tmp_path, env=env)

    assert result.returncode == 1
    assert "already exists" in result.stderr
    tag_lookup = subprocess.run(
        ["git", "rev-parse", "-q", "--verify", "refs/tags/v1.2.3"],
        cwd=project,
        text=True,
        capture_output=True,
        check=False,
    )
    assert tag_lookup.returncode != 0
    assert_no_release_mutation(command_log)


def test_release_lookup_failure_does_not_create_tag(tmp_path: Path) -> None:
    script, env, command_log = release_project(tmp_path)
    project = script.parents[1]
    env["GH_API_FAIL"] = "1"

    result = run_script("--release", "1.2.3", script=script, cwd=tmp_path, env=env)

    assert result.returncode == 8
    tag_lookup = subprocess.run(
        ["git", "rev-parse", "-q", "--verify", "refs/tags/v1.2.3"],
        cwd=project,
        text=True,
        capture_output=True,
        check=False,
    )
    assert tag_lookup.returncode != 0
    assert_no_release_mutation(command_log)


def test_failed_cli_workflow_stops_before_tap_publication(tmp_path: Path) -> None:
    script, env, command_log = release_project(tmp_path)
    env["GH_FAIL_CLI_RUN"] = "1"

    result = run_script("--release", "1.2.3", script=script, cwd=tmp_path, env=env)

    assert result.returncode != 0
    commands = command_log.read_text(encoding="utf-8")
    assert "gh run watch 101" in commands
    assert "publish-formula.yml" not in commands


def test_invalid_release_assets_stop_before_tap_publication(tmp_path: Path) -> None:
    script, env, command_log = release_project(tmp_path)
    assets = Path(env["GH_ASSETS"])
    assets.write_text(
        assets.read_text(encoding="utf-8").replace("localcloud.rb\n", ""),
        encoding="utf-8",
    )

    result = run_script("--release", "1.2.3", script=script, cwd=tmp_path, env=env)

    assert result.returncode == 1
    assert "asset set is incomplete or unexpected" in result.stderr
    commands = command_log.read_text(encoding="utf-8")
    assert "gh run watch 101" in commands
    assert "publish-formula.yml" not in commands
