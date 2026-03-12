#!/usr/bin/env python3
"""LocalCloud Python SDK Demo Runner.

Runs all service demos against a running LocalCloud instance and prints
a pass/fail summary. Each demo uses the official Google Cloud Python SDK
with the same code you'd write for production — just pointed at LocalCloud
via emulator environment variables.

Usage:
    # Start LocalCloud, then:
    eval "$(curl -s http://localhost:8080/_localcloud/env)"
    python run_demo.py
"""

import os
import sys
import time
import importlib

DEMOS = [
    ("Cloud Storage", "services.gcs_demo"),
    ("Pub/Sub", "services.pubsub_demo"),
    ("Firestore", "services.firestore_demo"),
    ("BigQuery", "services.bigquery_demo"),
    ("Secret Manager", "services.secretmanager_demo"),
    ("Cloud Tasks", "services.cloudtasks_demo"),
    ("Spanner", "services.spanner_demo"),
    ("Bigtable", "services.bigtable_demo"),
    ("Cloud Logging", "services.logging_demo"),
    ("Cloud Monitoring", "services.monitoring_demo"),
    ("GKE", "services.gke_demo"),
    ("Compute Engine", "services.compute_demo"),
    ("Cloud Run", "services.cloudrun_demo"),
]

GREEN = "\033[92m"
RED = "\033[91m"
BOLD = "\033[1m"
DIM = "\033[2m"
RESET = "\033[0m"
CHECK = f"{GREEN}\u2713{RESET}"
CROSS = f"{RED}\u2717{RESET}"


def run_demo(name: str, module_path: str, project_id: str) -> tuple[int, int]:
    """Run a single demo module and print results. Returns (passed, failed)."""
    print(f"\n{BOLD}{'=' * 50}{RESET}")
    print(f"{BOLD}{name}{RESET}")
    print(f"{'=' * 50}")

    passed = failed = 0
    try:
        module = importlib.import_module(module_path)
        results = module.run(project_id)
        for op, success, detail in results:
            if success:
                print(f"  {CHECK} {op} {DIM}({detail}){RESET}")
                passed += 1
            else:
                print(f"  {CROSS} {op} {DIM}({detail}){RESET}")
                failed += 1
    except Exception as e:
        print(f"  {CROSS} Failed to run demo: {e}")
        failed += 1

    return passed, failed


def main():
    project_id = os.environ.get("GCLOUD_PROJECT", "local-project")

    print(f"{BOLD}LocalCloud Python SDK Demo{RESET}")
    print(f"Project: {project_id}")
    print(f"Time:    {time.strftime('%Y-%m-%d %H:%M:%S')}")

    total_passed = 0
    total_failed = 0
    service_results = []

    for name, module_path in DEMOS:
        p, f = run_demo(name, module_path, project_id)
        total_passed += p
        total_failed += f
        service_results.append((name, p, f))

    # Summary
    print(f"\n{BOLD}{'=' * 50}{RESET}")
    print(f"{BOLD}Summary{RESET}")
    print(f"{'=' * 50}")

    for name, p, f in service_results:
        status = f"{GREEN}PASS{RESET}" if f == 0 else f"{RED}FAIL{RESET}"
        print(f"  {status}  {name} ({p}/{p + f} operations)")

    print(f"\n  Total: {total_passed} passed, {total_failed} failed")

    if total_failed > 0:
        print(f"\n{RED}Some demos failed.{RESET}")
        sys.exit(1)
    else:
        print(f"\n{GREEN}All demos passed!{RESET}")


if __name__ == "__main__":
    main()
