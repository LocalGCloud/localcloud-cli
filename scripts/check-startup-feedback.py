from __future__ import annotations

import argparse
import os
import selectors
import subprocess
import sys
import time
from pathlib import Path


class StartupFeedbackError(RuntimeError):
    pass


def measure_startup_feedback(command: Path, timeout: float) -> float:
    if timeout <= 0:
        raise ValueError("timeout must be positive")

    executable = command.resolve()
    started = time.monotonic()
    process = subprocess.Popen(
        [str(executable), "status", "--data-volume", "localcloud-startup-smoke"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )
    assert process.stderr is not None
    stderr_fd = process.stderr.fileno()
    os.set_blocking(stderr_fd, False)
    selector = selectors.DefaultSelector()
    selector.register(stderr_fd, selectors.EVENT_READ)
    output = bytearray()
    try:
        remaining = timeout - (time.monotonic() - started)
        while remaining > 0:
            events = selector.select(remaining)
            if not events:
                break
            try:
                chunk = os.read(stderr_fd, 4096)
            except BlockingIOError:
                remaining = timeout - (time.monotonic() - started)
                continue
            if not chunk:
                break
            output.extend(chunk)
            if b"Processing" in output:
                return time.monotonic() - started
            remaining = timeout - (time.monotonic() - started)
    finally:
        selector.close()
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        process.stderr.close()

    raise StartupFeedbackError(
        f"{executable} did not emit Processing within {timeout:.1f} seconds"
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify prompt startup feedback from a native LocalCloud bundle."
    )
    parser.add_argument("command", type=Path)
    parser.add_argument("--timeout", type=float, default=2.0)
    args = parser.parse_args()

    try:
        elapsed = measure_startup_feedback(args.command, args.timeout)
    except (OSError, StartupFeedbackError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(f"Processing emitted after {elapsed:.3f} seconds")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
