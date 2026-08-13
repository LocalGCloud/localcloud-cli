from __future__ import annotations

import io
import json
from typing import Any

import pytest

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
    "instance": "default",
    "project": "local-gcp-project",
    "user": "local-developer",
    "container": {
        "name": "localcloud-default",
        "state": "running",
        "url": "http://127.0.0.1:49080",
    },
    "services": "default",
    "data": "persistent",
    "network": "localcloud-default",
    "volume": "localcloud-data",
    "mcp": {"direct_url": "http://127.0.0.1:49080/mcp"},
}


def test_summary_uses_relevant_fields_in_stable_order() -> None:
    assert render_summary("start", PAYLOAD).splitlines() == [
        "Status    started",
        "Instance  default",
        "Project   local-gcp-project",
        "User      local-developer",
        "State     running",
        "URL       http://127.0.0.1:49080",
        "Services  default",
        "Data      persistent",
    ]


def test_summary_adds_nested_fields_in_requested_order_and_deduplicates() -> None:
    requested = parse_fields(["container.name,mcp.direct_url", "container.name"])
    lines = render_summary("start", PAYLOAD, requested).splitlines()
    assert lines[-2:] == [
        "Container  localcloud-default",
        "MCP URL    http://127.0.0.1:49080/mcp",
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
        HostError("instance_not_running", "Start it first", {"instance": "team-a"})
    )
    assert rendered == "Error [instance_not_running] Start it first\nInstance: team-a"


def test_error_omits_verbose_logs_and_nested_diagnostics() -> None:
    rendered = render_error(
        HostError(
            "container_start_failed",
            "Container failed",
            {
                "instance": "team-a",
                "logs": "very long\ncontainer logs",
                "rollback_failures": [{"resource": "volume"}],
            },
        )
    )
    assert rendered == "Error [container_start_failed] Container failed\nInstance: team-a"


def test_cloud_has_equal_visible_width_and_animation_changes_color() -> None:
    resting = render_cloud(phase=0.0, progress=1.0, color=ColorMode.TRUECOLOR)
    moving = render_cloud(phase=0.0, progress=0.25, color=ColorMode.TRUECOLOR)
    assert {visible_width(line) for line in resting} == {20}
    assert tuple(strip_ansi(line) for line in resting) == tuple(strip_ansi(line) for line in moving)
    assert resting != moving


@pytest.mark.parametrize("width", [42, 60, 100])
def test_panel_rows_are_aligned_at_all_breakpoints(width: int) -> None:
    context = PanelContext(
        instance="instance-with-a-name-that-must-be-truncated",
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
        instance="团队-instance",
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
        instance="default",
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
    reporter.start("Inspecting instance 'default'…")
    reporter.succeed("LocalCloud instance is running")
    assert stream.getvalue().splitlines() == [
        "ProcessingInspecting instance 'default'…",
        "Done      LocalCloud instance is running  2.5s",
    ]


def test_verbose_non_tty_reporter_is_silent_for_json_errors() -> None:
    stream = io.StringIO()
    reporter = LifecycleReporter(stream=stream, verbose=True)
    reporter.start("Inspecting instance…")
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
