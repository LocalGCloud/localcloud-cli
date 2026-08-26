from __future__ import annotations

import subprocess
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
CHECKER = PROJECT_ROOT / "scripts" / "check-startup-feedback.py"


def _fake_command(path: Path, *, delay: float) -> Path:
    path.write_text(
        f"#!{sys.executable}\n"
        "import sys\n"
        "import time\n"
        f"time.sleep({delay!r})\n"
        "print('Processing   fixture', file=sys.stderr, flush=True)\n"
        "time.sleep(10)\n",
        encoding="utf-8",
    )
    path.chmod(0o755)
    return path


def test_startup_feedback_gate_accepts_prompt_output(tmp_path: Path) -> None:
    command = _fake_command(tmp_path / "prompt-command", delay=0.0)

    result = subprocess.run(
        [sys.executable, str(CHECKER), str(command), "--timeout", "0.5"],
        text=True,
        capture_output=True,
        check=False,
        timeout=2,
    )

    assert result.returncode == 0, result.stderr
    assert "Processing emitted after" in result.stdout


def test_startup_feedback_gate_rejects_delayed_output(tmp_path: Path) -> None:
    command = _fake_command(tmp_path / "slow-command", delay=0.25)

    result = subprocess.run(
        [sys.executable, str(CHECKER), str(command), "--timeout", "0.05"],
        text=True,
        capture_output=True,
        check=False,
        timeout=2,
    )

    assert result.returncode == 1
    assert "did not emit Processing within 0.1 seconds" in result.stderr


def test_startup_feedback_gate_does_not_block_on_partial_stderr(
    tmp_path: Path,
) -> None:
    command = tmp_path / "partial-command"
    command.write_text(
        f"#!{sys.executable}\n"
        "import sys\n"
        "import time\n"
        "sys.stderr.write('unterminated warning')\n"
        "sys.stderr.flush()\n"
        "time.sleep(10)\n",
        encoding="utf-8",
    )
    command.chmod(0o755)

    result = subprocess.run(
        [sys.executable, str(CHECKER), str(command), "--timeout", "0.05"],
        text=True,
        capture_output=True,
        check=False,
        timeout=2,
    )

    assert result.returncode == 1
    assert "did not emit Processing within 0.1 seconds" in result.stderr
