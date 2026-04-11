#!/usr/bin/env python3
"""LocalCloud Python SDK Demo Runner.

Runs all service demos against a running LocalCloud instance and prints
a pass/fail summary. Automatically detects which services are enabled
and skips disabled ones.

Each demo uses the official Google Cloud Python SDK with the same code
you'd write for production — just pointed at LocalCloud via emulator
environment variables.

Usage:
    # Start LocalCloud, then:
    python run_demo.py
"""

import json
import os
import sys
import time
import importlib
import urllib.request
import urllib.error

# Maps display name -> (module_path, health_key)
# health_key is the key in the /_localcloud/health response's "services" dict
DEMOS = [
    ("Cloud Storage", "services.gcs_demo", "gcs"),
    ("Pub/Sub", "services.pubsub_demo", "pubsub"),
    ("Firestore", "services.firestore_demo", "firestore"),
    ("BigQuery", "services.bigquery_demo", "bigquery"),
    ("Secret Manager", "services.secretmanager_demo", "secretmanager"),
    ("Cloud Tasks", "services.cloudtasks_demo", "cloudtasks"),
    ("Spanner", "services.spanner_demo", "spanner"),
    ("Bigtable", "services.bigtable_demo", "bigtable"),
    ("Cloud Logging", "services.logging_demo", "logging"),
    ("Cloud Monitoring", "services.monitoring_demo", "monitoring"),
    ("GKE", "services.gke_demo", "gke"),
    ("Compute Engine", "services.compute_demo", "compute"),
    ("Cloud Run", "services.cloudrun_demo", "cloudrun"),
]

GREEN = "\033[92m"
YELLOW = "\033[93m"
RED = "\033[91m"
BOLD = "\033[1m"
DIM = "\033[2m"
RESET = "\033[0m"
CHECK = f"{GREEN}\u2713{RESET}"
CROSS = f"{RED}\u2717{RESET}"
SKIP = f"{YELLOW}\u2014{RESET}"


def get_enabled_services(gateway_url: str) -> set[str] | None:
    """Query LocalCloud health endpoint to discover enabled services."""
    try:
        req = urllib.request.Request(f"{gateway_url}/_localcloud/health")
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read())
            return set(data.get("services", {}).keys())
    except (urllib.error.URLError, OSError, json.JSONDecodeError) as e:
        print(f"{RED}Could not reach LocalCloud at {gateway_url}: {e}{RESET}")
        return None


def run_demo(name: str, module_path: str, project_id: str, keep_data: bool = False) -> tuple[int, int]:
    """Run a single demo module and print results. Returns (passed, failed)."""
    print(f"\n{BOLD}{'=' * 50}{RESET}")
    print(f"{BOLD}{name}{RESET}")
    print(f"{'=' * 50}")

    passed = failed = 0
    try:
        module = importlib.import_module(module_path)
        results = module.run(project_id, keep_data=keep_data)
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
    import argparse
    parser = argparse.ArgumentParser(description="LocalCloud Python SDK Demo")
    parser.add_argument("--keep-data", action="store_true",
                        help="Skip cleanup — leave created resources for manual inspection")
    parser.add_argument("--service", type=str, default=None,
                        help="Run only a specific service demo (e.g., gcs, pubsub, spanner)")
    args = parser.parse_args()

    keep_data = args.keep_data
    only_service = args.service

    project_id = os.environ.get("GCLOUD_PROJECT", "local-project")
    gateway_url = os.environ.get("LOCALCLOUD_GATEWAY", "http://localhost:8080")

    print(f"{BOLD}LocalCloud Python SDK Demo{RESET}")
    print(f"Project: {project_id}")
    print(f"Gateway: {gateway_url}")
    print(f"Time:    {time.strftime('%Y-%m-%d %H:%M:%S')}")
    if keep_data:
        print(f"Mode:    {YELLOW}keep-data (cleanup skipped){RESET}")
    if only_service:
        print(f"Service: {only_service}")

    # Discover which services are enabled
    enabled = get_enabled_services(gateway_url)
    if enabled is None:
        print(f"\n{RED}Cannot connect to LocalCloud. Is it running?{RESET}")
        sys.exit(1)

    print(f"Enabled: {', '.join(sorted(enabled))}")

    # Set emulator environment variables from LocalCloud
    try:
        req = urllib.request.Request(f"{gateway_url}/_localcloud/env?format=json")
        with urllib.request.urlopen(req, timeout=5) as resp:
            env_vars = json.loads(resp.read())
            for key, value in env_vars.items():
                os.environ[key] = value
            print(f"Env vars: {len(env_vars)} configured")
    except Exception as e:
        print(f"{YELLOW}Warning: Could not load env vars: {e}{RESET}")

    total_passed = 0
    total_failed = 0
    total_skipped = 0
    service_results = []  # (name, passed, failed, skipped)

    for name, module_path, health_key in DEMOS:
        if only_service and health_key != only_service:
            continue

        if health_key not in enabled:
            print(f"\n{BOLD}{'=' * 50}{RESET}")
            print(f"{BOLD}{name}{RESET}")
            print(f"{'=' * 50}")
            print(f"  {SKIP} Skipped (service not enabled)")
            service_results.append((name, 0, 0, True))
            total_skipped += 1
            continue

        p, f = run_demo(name, module_path, project_id, keep_data=keep_data)
        total_passed += p
        total_failed += f
        service_results.append((name, p, f, False))

    # Summary
    print(f"\n{BOLD}{'=' * 50}{RESET}")
    print(f"{BOLD}Summary{RESET}")
    print(f"{'=' * 50}")

    for name, p, f, skipped in service_results:
        if skipped:
            print(f"  {YELLOW}SKIP{RESET}  {name}")
        elif f == 0:
            print(f"  {GREEN}PASS{RESET}  {name} ({p}/{p + f} operations)")
        else:
            print(f"  {RED}FAIL{RESET}  {name} ({p}/{p + f} operations)")

    print(f"\n  Total: {total_passed} passed, {total_failed} failed, {total_skipped} skipped")

    if total_failed > 0:
        print(f"\n{RED}Some demos failed.{RESET}")
        sys.exit(1)
    else:
        print(f"\n{GREEN}All demos passed!{RESET}")


if __name__ == "__main__":
    main()
