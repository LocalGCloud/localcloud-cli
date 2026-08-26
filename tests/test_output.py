from __future__ import annotations

import io
import json
from typing import Any

import pytest  # pyright: ignore[reportMissingImports]

from localcloud_cli.errors import HostError
from localcloud_cli.output import (
    ColorMode,
    LifecycleReporter,
    PanelContext,
    parse_fields,
    render_cloud,
    render_error,
    render_json,
    render_panel,
    render_summary,
    strip_ansi,
    terminal_capabilities,
    visible_width,
)


class TtyBuffer(io.StringIO):
    def isatty(self) -> bool:
        return True


PAYLOAD: dict[str, Any] = {
    "status": "started",
    "config": "localcloud.yaml",
    "data_volume": "localcloud-data",
    "origin": "managed",
    "project": "local-gcp-project",
    "user": "local-developer",
    "container": {
        "name": "localcloud",
        "state": "running",
        "url": "http://127.0.0.1:49080",
    },
    "services": "default",
    "data": "persistent",
    "network": {"name": "localcloud"},
    "mount": {"source": "localcloud-data"},
    "mcp": {"direct_url": "http://127.0.0.1:49080/mcp"},
}


def test_summary_uses_relevant_fields_in_stable_order() -> None:
    assert render_summary("start", PAYLOAD).splitlines() == [
        "Status       Running",
        "Config       localcloud.yaml",
        "Data volume  localcloud-data",
        "Origin       managed",
        "Project      local-gcp-project",
        "User         local-developer",
        "URL          http://127.0.0.1:49080",
        "Services     default",
    ]


def test_summary_humanizes_runtime_status_values() -> None:
    payload = {
        "status": "not_running",
        "data_volume": "localcloud-data",
        "config": "localcloud.yaml",
        "container": {"state": "stopped", "url": "http://127.0.0.1:49080"},
    }
    lines = render_summary("status", payload).splitlines()
    assert lines[0] == "Status       Not Running"
    assert "State" not in lines


def test_stop_summary_includes_stopped_container_identity() -> None:
    payload = {
        "status": "stopped",
        "data_volume": "localcloud-data",
        "container": {
            "name": "localcloud",
            "id": "a1b2c3d4e5f6",
            "state": "exited",
        },
    }

    lines = render_summary("stop", payload).splitlines()

    assert "Container     localcloud" in lines
    assert "Container ID  a1b2c3d4e5f6" in lines


def test_stop_summary_omits_container_identity_when_nothing_was_stopped() -> None:
    payload = {
        "status": "not_running",
        "data_volume": "localcloud-data",
        "container": {
            "name": "localcloud",
            "id": "a1b2c3d4e5f6",
            "state": "exited",
        },
    }

    lines = render_summary("stop", payload).splitlines()

    assert not any(line.startswith("Container ") for line in lines)
    assert not any(line.startswith("Container ID") for line in lines)


def test_summary_adds_nested_fields_in_requested_order_and_deduplicates() -> None:
    requested = parse_fields(["container.name,mcp.direct_url", "container.name"])
    lines = render_summary("start", PAYLOAD, requested).splitlines()
    assert lines[-2:] == [
        "Container    localcloud",
        "MCP URL      http://127.0.0.1:49080/mcp",
    ]


def test_summary_rejects_unknown_field_with_valid_choices() -> None:
    with pytest.raises(HostError) as caught:
        render_summary("start", PAYLOAD, ["container.missing"])
    assert caught.value.code == "invalid_output_field"
    assert "container.name" in caught.value.details["valid_fields"]


def test_parse_fields_rejects_empty_path() -> None:
    with pytest.raises(ValueError, match="must not be empty"):
        parse_fields(["network,"])


def test_colored_summary_preserves_visible_text() -> None:
    plain = render_summary("start", PAYLOAD)
    colored = render_summary("start", PAYLOAD, color=ColorMode.TRUECOLOR)
    assert "\x1b[" in colored
    assert strip_ansi(colored) == plain


def test_json_color_preserves_parseable_payload_after_stripping() -> None:
    colored = render_json(PAYLOAD, color=ColorMode.TRUECOLOR)
    assert json.loads(strip_ansi(colored)) == PAYLOAD
    assert json.loads(render_json(PAYLOAD)) == PAYLOAD


def test_error_is_concise_and_keeps_scalar_details() -> None:
    rendered = render_error(
        HostError("runtime_not_running", "Start it first", {"data_volume": "team-data"})
    )
    assert rendered == "Error [runtime_not_running] Start it first\nData Volume: team-data"


def test_error_omits_verbose_logs_and_nested_diagnostics() -> None:
    rendered = render_error(
        HostError(
            "container_start_failed",
            "Container failed",
            {
                "data_volume": "team-data",
                "logs": "very long\ncontainer logs",
                "rollback_failures": [{"resource": "volume"}],
            },
        )
    )
    assert rendered == "Error [container_start_failed] Container failed\nData Volume: team-data"


def test_cloud_has_equal_visible_width_and_animation_changes_color() -> None:
    expected = (
        "       ╭────╮       ",
        "   ╭───╯    ╰───╮   ",
        " ╭──╯          ╰──╮ ",
        "╭╯                ╰╮",
        "╰──────────────────╯",
    )
    resting = render_cloud(phase=0.0, progress=1.0, color=ColorMode.TRUECOLOR)
    moving = render_cloud(phase=0.0, progress=0.25, color=ColorMode.TRUECOLOR)
    assert render_cloud(color=ColorMode.NONE) == expected
    assert {visible_width(line) for line in resting} == {20}
    assert tuple(strip_ansi(line) for line in resting) == expected
    assert tuple(strip_ansi(line) for line in moving) == expected
    assert resting != moving


@pytest.mark.parametrize("width", [42, 60, 100])
def test_panel_rows_are_aligned_at_all_breakpoints(width: int) -> None:
    context = PanelContext(
        data_volume="volume-with-a-name-that-must-be-truncated",
        project="local-gcp-project-with-extra-content",
        user="local-developer",
        services=("storage", "bigquery", "pubsub"),
        data="persistent",
        config="/tmp/localcloud.yaml",
    )
    lines = render_panel(context, width, color=ColorMode.TRUECOLOR)
    assert len({visible_width(line) for line in lines}) == 1
    assert visible_width(lines[0]) == max(28, min(100, width - 2))


def test_unicode_width_and_truncation_keep_panel_aligned() -> None:
    context = PanelContext(
        data_volume="团队-data-volume",
        project="本地-cloud-project-with-extra-content",
        user="local-developer",
        services="default",
        data="persistent",
        config=None,
    )
    lines = render_panel(context, 60, color=ColorMode.ANSI256)
    assert len({visible_width(line) for line in lines}) == 1
    assert visible_width(lines[0]) == 58


def test_panel_stays_within_very_narrow_terminal() -> None:
    context = PanelContext(
        data_volume="localcloud-data",
        project="local-gcp-project",
        user="local-developer",
        services="default",
        data="persistent",
        config=None,
    )
    lines = render_panel(context, 20, color=ColorMode.ANSI256)
    assert {visible_width(line) for line in lines} == {18}


def test_narrow_lifecycle_lines_do_not_wrap() -> None:
    stream = TtyBuffer()
    reporter = LifecycleReporter(
        stream=stream,
        environ={"TERM": "xterm-256color"},
        clock=lambda: 0.0,
        width=20,
        fps=1,
    )
    frame = reporter._frame(1.0, 0.0)
    assert all(visible_width(line) <= 20 for line in frame)
    assert visible_width(
        reporter._status_line("Done", "LocalCloud is ready", "success", 0.0)
    ) <= 20


def test_no_color_keeps_static_panel_without_ansi() -> None:
    context = PanelContext("default", "local-gcp-project", "local-developer", "default", "persistent", None)
    panel = render_panel(context, 100, color=ColorMode.NONE)
    assert all("\x1b[" not in line for line in panel)
    assert any("LocalCloud" in line for line in panel)


def test_terminal_capabilities_respect_no_color_and_redirects() -> None:
    tty = TtyBuffer()
    assert terminal_capabilities(tty, {"TERM": "xterm-256color"}).color is ColorMode.ANSI256
    no_color = terminal_capabilities(tty, {"TERM": "xterm-256color", "NO_COLOR": "1"})
    assert no_color.interactive
    assert no_color.cursor
    assert no_color.color is ColorMode.NONE
    redirected = terminal_capabilities(io.StringIO(), {"TERM": "xterm-256color"})
    assert not redirected.interactive
    assert not redirected.cursor
    assert redirected.color is ColorMode.NONE


def test_non_tty_reporter_emits_stable_lifecycle_lines() -> None:
    stream = io.StringIO()
    times = iter([10.0, 12.5])
    reporter = LifecycleReporter(stream=stream, clock=lambda: next(times))
    reporter.start("Inspecting data volume 'localcloud-data'…")
    reporter.succeed("LocalCloud runtime is running")
    assert stream.getvalue().splitlines() == [
        "Processing   Inspecting data volume 'localcloud-data'…",
        "Done         LocalCloud runtime is running  2.5s",
    ]


def test_verbose_non_tty_reporter_is_silent_for_json_errors() -> None:
    stream = io.StringIO()
    reporter = LifecycleReporter(stream=stream, verbose=True)
    reporter.start("Inspecting runtime…")
    reporter.fail("not running")
    assert stream.getvalue() == ""


def test_interactive_reporter_draws_panel_and_restores_cursor() -> None:
    stream = TtyBuffer()
    times = iter([0.0, 0.2, 0.3])
    reporter = LifecycleReporter(
        stream=stream,
        environ={"TERM": "xterm-256color"},
        clock=lambda: next(times),
        phase_selector=lambda values: values[1],
        width=100,
        fps=1,
    )
    reporter.start("Preparing LocalCloud start…")
    reporter.update(
        "Starting instance 'default' and preparing project 'local-gcp-project'…",
        PanelContext("default", "local-gcp-project", "local-developer", "default", "persistent", None),
    )
    reporter.succeed("LocalCloud is ready")
    output = stream.getvalue()
    assert "\x1b[?25l" in output
    assert "LocalCloud" in strip_ansi(output)
    assert "Done" in strip_ansi(output)
    assert output.endswith("\x1b[?25h")


def test_no_color_reporter_keeps_panel_static() -> None:
    stream = TtyBuffer()
    times = iter([0.0, 0.2, 0.3])
    reporter = LifecycleReporter(
        stream=stream,
        environ={"TERM": "xterm-256color", "NO_COLOR": "1"},
        clock=lambda: next(times),
        phase_selector=lambda values: values[0],
        width=100,
        fps=1,
    )
    reporter.start("Preparing LocalCloud start…")
    reporter.update(
        "Starting instance 'default' and preparing project 'local-gcp-project'…",
        PanelContext(
            "default",
            "local-gcp-project",
            "local-developer",
            "default",
            "persistent",
            None,
        ),
    )
    reporter.succeed("LocalCloud is ready")
    output = stream.getvalue()
    assert "\x1b[38;" not in output
    assert strip_ansi(output).count("LocalCloud v") == 1


def test_wide_panel_structure_and_color_coding() -> None:
    context = PanelContext(
        data_volume="localcloud-data",
        project="local-gcp-project",
        user="local-developer",
        services="default",
        data="persistent",
        config=None,
    )
    lines = render_panel(context, 100, color=ColorMode.TRUECOLOR)
    plain_text = "\n".join(strip_ansi(line) for line in lines)
    assert "Welcome back!" in plain_text
    assert "Tips & Commands" in plain_text
    assert "localcloud (or lc)" in plain_text
    assert "status | start | stop | restart" in plain_text
    assert "eval $(lc env)" in plain_text
    assert "Google Cloud Services" in plain_text
    assert "● Storage" in plain_text
    assert "● Firestore" in plain_text
    assert "● Pub/Sub" in plain_text
    assert "● BigQuery" in plain_text
    assert "● Secrets" in plain_text
    assert "Config" in plain_text
    assert "Data Volume:" in plain_text
    assert "localcloud-data" in plain_text
    assert "Project:" in plain_text
    assert "local-gcp-project" in plain_text
    assert "User:" in plain_text
    assert "local-developer" in plain_text
    assert "Tip: Run localcloud guide" in plain_text
    assert all(visible_width(line) == 98 for line in lines)


def test_custom_services_rendering_in_panel() -> None:
    context = PanelContext(
        data_volume="localcloud-data",
        project="local-gcp-project",
        user="local-developer",
        services=("storage", "bigquery", "pubsub"),
        data="persistent",
        config=None,
    )
    lines = render_panel(context, 100, color=ColorMode.TRUECOLOR)
    plain_text = "\n".join(strip_ansi(line) for line in lines)
    assert "● Storage" in plain_text
    assert "● BigQuery" in plain_text
    assert "● Pub/Sub" in plain_text
    assert all(visible_width(line) == 98 for line in lines)


def test_cloud_color_modes_emit_appropriate_escape_codes() -> None:
    tc = render_cloud(color=ColorMode.TRUECOLOR)
    ansi256 = render_cloud(color=ColorMode.ANSI256)
    ansi16 = render_cloud(color=ColorMode.ANSI16)
    none = render_cloud(color=ColorMode.NONE)

    assert all("\x1b[38;2;" in line for line in tc)
    assert all("\x1b[38;5;" in line for line in ansi256)
    assert all("\x1b[" in line for line in ansi16)
    assert all("\x1b[" not in line for line in none)

    assert tuple(strip_ansi(line) for line in tc) == none
    assert tuple(strip_ansi(line) for line in ansi256) == none
    assert tuple(strip_ansi(line) for line in ansi16) == none


@pytest.mark.parametrize("width", [120, 100, 80, 70, 50, 42, 20])
def test_all_breakpoints_have_uniform_line_widths(width: int) -> None:
    context = PanelContext(
        data_volume="my-volume-data",
        project="my-gcp-project",
        user="developer",
        services="default",
        data="persistent",
        config="/tmp/localcloud.yaml",
    )
    for mode in (ColorMode.NONE, ColorMode.TRUECOLOR, ColorMode.ANSI256, ColorMode.ANSI16):
        lines = render_panel(context, width, color=mode)
        widths = {visible_width(line) for line in lines}
        assert len(widths) == 1
        expected_box_width = min(100, max(4, width - 2))
        assert next(iter(widths)) == expected_box_width

def test_reporter_write_line_emits_lines_in_plain_and_interactive_mode() -> None:
    plain_stream = io.StringIO()
    plain_reporter = LifecycleReporter(stream=plain_stream, environ={"TERM": "dumb"})
    plain_reporter.start("Starting LocalCloud…")
    plain_reporter.write_line("2026-08-18T10:00:00Z [INFO] Service starting")
    plain_reporter.write_line("2026-08-18T10:00:01Z [INFO] Ready")
    plain_reporter.succeed("LocalCloud is ready")
    plain_output = plain_stream.getvalue()
    assert "2026-08-18T10:00:00Z [INFO] Service starting\n" in plain_output
    assert "2026-08-18T10:00:01Z [INFO] Ready\n" in plain_output
    assert "Done" in plain_output

    interactive_stream = TtyBuffer()
    times = iter([0.0, 0.1, 0.2, 0.3])
    interactive_reporter = LifecycleReporter(
        stream=interactive_stream,
        environ={"TERM": "xterm-256color"},
        clock=lambda: next(times),
        width=80,
        fps=1,
    )
    interactive_reporter.start("Starting LocalCloud…")
    interactive_reporter.write_line("Log line 1")
    interactive_reporter.write_line("Log line 2")
    interactive_reporter.succeed("LocalCloud is ready")
    interactive_output = interactive_stream.getvalue()
    assert "Log line 1" in interactive_output
    assert "Log line 2" in interactive_output
    assert "Done" in strip_ansi(interactive_output)
    assert interactive_output.endswith("\x1b[?25h")


def test_reporter_panel_settles_above_log_stream() -> None:
    stream = TtyBuffer()
    times = iter([0.0, 0.1, 0.2, 0.3, 0.4])
    reporter = LifecycleReporter(
        stream=stream,
        environ={"TERM": "xterm-256color"},
        clock=lambda: next(times),
        width=80,
        fps=1,
    )
    panel = PanelContext(
        data_volume="localcloud-data",
        project="local-gcp-project",
        user="local-developer",
        services="default",
        data="persistent",
        config=None,
    )
    reporter.start("Starting LocalCloud…")
    reporter.update("Starting…", panel)
    reporter.write_line("2026-08-18T14:30:01Z [INFO] Service starting")
    reporter.write_line("2026-08-18T14:30:02Z [INFO] Ready")
    reporter.succeed("LocalCloud is ready")
    output = stream.getvalue()
    plain = strip_ansi(output)

    assert "LocalCloud v" in plain
    # Panel is printed exactly once
    assert plain.count("LocalCloud v") == 1
    # Log lines appear after the panel
    panel_pos = plain.find("LocalCloud v")
    log1_pos = plain.find("2026-08-18T14:30:01Z [INFO] Service starting")
    log2_pos = plain.find("2026-08-18T14:30:02Z [INFO] Ready")
    done_pos = plain.find("Done")

    assert panel_pos < log1_pos < log2_pos < done_pos

