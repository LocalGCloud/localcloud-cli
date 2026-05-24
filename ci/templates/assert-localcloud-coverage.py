#!/usr/bin/env python3
"""Assert LocalCloud service coverage before running CI tests.

The script reads `/coverage` from a running LocalCloud container and fails when
requested services are unknown, unverified, or below the requested coverage
level. It is intentionally dependency-free so it can run in GitHub Actions,
Buildkite, Jenkins, and local shells.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request


STATUS_ORDER = {
    "unsupported": 0,
    "unverified": 1,
    "early-partial": 2,
    "partial": 3,
    "supported": 4,
}


def fetch_coverage(base_url: str) -> dict:
    url = base_url.rstrip("/") + "/coverage"
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.URLError as exc:
        raise SystemExit(f"failed to fetch {url}: {exc}") from exc


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Assert LocalCloud compatibility coverage")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--services", required=True, help="Comma-separated service ids required by this test job")
    parser.add_argument("--min-status", default="partial", choices=sorted(STATUS_ORDER))
    parser.add_argument("--require-terraform", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    required = [service.strip() for service in args.services.split(",") if service.strip()]
    coverage = fetch_coverage(args.base_url)
    services = {
        service["service_id"]: service
        for service in coverage.get("services", [])
    }

    failures: list[str] = []
    minimum = STATUS_ORDER[args.min_status]
    for service_id in required:
        service = services.get(service_id)
        if service is None:
            failures.append(f"{service_id}: not present in /coverage")
            continue

        status = service.get("coverage_status", "unverified")
        if STATUS_ORDER.get(status, 0) < minimum:
            failures.append(f"{service_id}: coverage_status={status}, expected >= {args.min_status}")

        if args.require_terraform:
            terraform = service.get("terraform_resources", {})
            terraform_status = terraform.get("status", "unverified")
            if STATUS_ORDER.get(terraform_status, 0) < minimum:
                failures.append(f"{service_id}: terraform status={terraform_status}, expected >= {args.min_status}")

    if failures:
        print("LocalCloud coverage assertion failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        print("See /coverage for limitations and workarounds.", file=sys.stderr)
        return 1

    print(f"LocalCloud coverage OK for: {', '.join(required)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
