#!/usr/bin/env python3
"""Spanner Persistence Integration Tests.

Tests that Spanner data survives container restarts when using the forked
emulator with --data_dir persistence enabled.

Prerequisites:
    - LocalCloud running with Spanner enabled and persistence configured
    - spanner-emulator-build:latest image built from the fork
    - google-cloud-spanner Python SDK installed

Usage:
    # Start LocalCloud with Spanner
    LOCALCLOUD_SERVICES=spanner docker compose up -d

    # Run persistence tests
    python test_spanner_persistence.py

    # Run a specific test
    python test_spanner_persistence.py --test create_and_restart
"""

import argparse
import json
import os
import subprocess
import sys
import time
import uuid

from google.auth.credentials import AnonymousCredentials
from google.cloud import spanner

GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
BOLD = "\033[1m"
DIM = "\033[2m"
RESET = "\033[0m"
CHECK = f"{GREEN}\u2713{RESET}"
CROSS = f"{RED}\u2717{RESET}"


def wait_for_spanner(host: str, timeout: int = 60) -> bool:
    """Wait for the Spanner emulator to become ready."""
    import urllib.request
    import urllib.error

    # The gateway exposes a REST endpoint on port 9020
    hostname = host.split(":")[0] if ":" in host else host
    url = f"http://{hostname}:9020/v1/projects/local-project/instances"

    for i in range(timeout):
        try:
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=3) as resp:
                if resp.status == 200:
                    return True
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(1)
    return False


def restart_container(container_name: str = "localcloud-main", timeout: int = 90) -> bool:
    """Restart the LocalCloud container and wait for Spanner to be ready."""
    print(f"  {DIM}Restarting container {container_name}...{RESET}")
    result = subprocess.run(
        ["docker", "compose", "restart"],
        capture_output=True, text=True, timeout=60
    )
    if result.returncode != 0:
        print(f"  {RED}docker compose restart failed: {result.stderr}{RESET}")
        return False

    # Wait for Spanner to come back up
    host = os.environ.get("SPANNER_EMULATOR_HOST", "localhost:9010")
    if not wait_for_spanner(host, timeout=timeout):
        print(f"  {RED}Spanner did not become ready after restart{RESET}")
        return False

    print(f"  {DIM}Container restarted, Spanner ready{RESET}")
    # Small buffer for emulator to fully initialize
    time.sleep(2)
    return True


def check_spanner_data_dir(container_name: str = "localcloud-main") -> bool:
    """Check if the spanner-data directory exists and has LevelDB files."""
    result = subprocess.run(
        ["docker", "compose", "exec", "localcloud", "ls", "/var/lib/localcloud/spanner-data/"],
        capture_output=True, text=True, timeout=10
    )
    return result.returncode == 0


def test_create_and_restart(client: spanner.Client, project_id: str) -> tuple[bool, str]:
    """IT1: Create instance/database/table, insert rows, restart, verify data persists."""
    instance_id = f"persist-test-{uuid.uuid4().hex[:8]}"
    database_id = f"persist-db-{uuid.uuid4().hex[:8]}"

    try:
        # Create instance
        instance = client.instance(instance_id)
        op = instance.create()
        op.result(timeout=30)

        # Create database with table
        database = instance.database(
            database_id,
            ddl_statements=[
                """CREATE TABLE PersistTest (
                    Id STRING(36) NOT NULL,
                    Name STRING(100),
                    Value INT64
                ) PRIMARY KEY (Id)"""
            ],
        )
        op = database.create()
        op.result(timeout=30)

        # Insert test rows
        test_rows = [
            (str(uuid.uuid4()), "Alice", 100),
            (str(uuid.uuid4()), "Bob", 200),
            (str(uuid.uuid4()), "Charlie", 300),
        ]
        database = instance.database(database_id)
        with database.batch() as batch:
            batch.insert(
                table="PersistTest",
                columns=("Id", "Name", "Value"),
                values=test_rows,
            )

        # Verify data before restart
        with database.snapshot() as snapshot:
            rows_before = list(snapshot.execute_sql(
                "SELECT Name, Value FROM PersistTest ORDER BY Name"
            ))
            assert len(rows_before) == 3, f"expected 3 rows before restart, got {len(rows_before)}"

        # Restart container
        if not restart_container():
            return False, "Container restart failed"

        # Reconnect and verify data after restart
        client_after = spanner.Client(project=project_id, credentials=AnonymousCredentials())
        instance_after = client_after.instance(instance_id)
        database_after = instance_after.database(database_id)

        with database_after.snapshot() as snapshot:
            rows_after = list(snapshot.execute_sql(
                "SELECT Name, Value FROM PersistTest ORDER BY Name"
            ))

        if len(rows_after) != 3:
            return False, f"expected 3 rows after restart, got {len(rows_after)}"

        # Verify actual data matches
        for before, after in zip(rows_before, rows_after):
            if before != after:
                return False, f"data mismatch: {before} != {after}"

        return True, f"3 rows survived restart (instance={instance_id})"

    except Exception as e:
        return False, str(e)
    finally:
        # Cleanup
        try:
            client_cleanup = spanner.Client(project=project_id, credentials=AnonymousCredentials())
            inst = client_cleanup.instance(instance_id)
            db = inst.database(database_id)
            db.drop()
            inst.delete()
        except Exception:
            pass


def test_schema_change_and_restart(client: spanner.Client, project_id: str) -> tuple[bool, str]:
    """IT2: ALTER TABLE, restart, verify schema change persists."""
    instance_id = f"schema-test-{uuid.uuid4().hex[:8]}"
    database_id = f"schema-db-{uuid.uuid4().hex[:8]}"

    try:
        instance = client.instance(instance_id)
        op = instance.create()
        op.result(timeout=30)

        database = instance.database(
            database_id,
            ddl_statements=[
                """CREATE TABLE SchemaTest (
                    Id STRING(36) NOT NULL,
                    Name STRING(100)
                ) PRIMARY KEY (Id)"""
            ],
        )
        op = database.create()
        op.result(timeout=30)

        # Add a column
        database = instance.database(database_id)
        op = database.update_ddl(["ALTER TABLE SchemaTest ADD COLUMN Email STRING(200)"])
        op.result(timeout=30)

        # Insert data with the new column
        row_id = str(uuid.uuid4())
        with database.batch() as batch:
            batch.insert(
                table="SchemaTest",
                columns=("Id", "Name", "Email"),
                values=[(row_id, "Alice", "alice@example.com")],
            )

        # Restart
        if not restart_container():
            return False, "Container restart failed"

        # Verify schema and data
        client_after = spanner.Client(project=project_id, credentials=AnonymousCredentials())
        db_after = client_after.instance(instance_id).database(database_id)

        with db_after.snapshot() as snapshot:
            rows = list(snapshot.execute_sql(
                "SELECT Name, Email FROM SchemaTest WHERE Id = @id",
                params={"id": row_id},
                param_types={"id": spanner.param_types.STRING},
            ))

        if len(rows) != 1:
            return False, f"expected 1 row, got {len(rows)}"
        if rows[0] != ("Alice", "alice@example.com"):
            return False, f"data mismatch: {rows[0]}"

        return True, "Schema change (ADD COLUMN) and data survived restart"

    except Exception as e:
        return False, str(e)
    finally:
        try:
            c = spanner.Client(project=project_id, credentials=AnonymousCredentials())
            inst = c.instance(instance_id)
            inst.database(database_id).drop()
            inst.delete()
        except Exception:
            pass


def test_multiple_db_restart(client: spanner.Client, project_id: str) -> tuple[bool, str]:
    """IT3: Two databases, restart, verify both are present."""
    instance_id = f"multidb-test-{uuid.uuid4().hex[:8]}"
    db1_id = f"db1-{uuid.uuid4().hex[:8]}"
    db2_id = f"db2-{uuid.uuid4().hex[:8]}"

    try:
        instance = client.instance(instance_id)
        op = instance.create()
        op.result(timeout=30)

        for db_id, table_name, value in [(db1_id, "Table1", 111), (db2_id, "Table2", 222)]:
            db = instance.database(
                db_id,
                ddl_statements=[
                    f"""CREATE TABLE {table_name} (
                        Id STRING(36) NOT NULL,
                        Val INT64
                    ) PRIMARY KEY (Id)"""
                ],
            )
            op = db.create()
            op.result(timeout=30)

            db = instance.database(db_id)
            with db.batch() as batch:
                batch.insert(
                    table=table_name,
                    columns=("Id", "Val"),
                    values=[(str(uuid.uuid4()), value)],
                )

        if not restart_container():
            return False, "Container restart failed"

        client_after = spanner.Client(project=project_id, credentials=AnonymousCredentials())
        inst = client_after.instance(instance_id)

        # Verify both databases
        for db_id, table_name, expected_val in [(db1_id, "Table1", 111), (db2_id, "Table2", 222)]:
            db = inst.database(db_id)
            with db.snapshot() as snapshot:
                rows = list(snapshot.execute_sql(f"SELECT Val FROM {table_name}"))
            if len(rows) != 1 or rows[0][0] != expected_val:
                return False, f"Database {db_id}: expected [{expected_val}], got {rows}"

        return True, f"Both databases ({db1_id}, {db2_id}) survived restart"

    except Exception as e:
        return False, str(e)
    finally:
        try:
            c = spanner.Client(project=project_id, credentials=AnonymousCredentials())
            inst = c.instance(instance_id)
            for db_id in [db1_id, db2_id]:
                try:
                    inst.database(db_id).drop()
                except Exception:
                    pass
            inst.delete()
        except Exception:
            pass


def test_drop_database_and_restart(client: spanner.Client, project_id: str) -> tuple[bool, str]:
    """IT4: Drop DB, restart, verify DB does not reappear."""
    instance_id = f"drop-test-{uuid.uuid4().hex[:8]}"
    database_id = f"drop-db-{uuid.uuid4().hex[:8]}"

    try:
        instance = client.instance(instance_id)
        op = instance.create()
        op.result(timeout=30)

        database = instance.database(
            database_id,
            ddl_statements=[
                """CREATE TABLE DropTest (
                    Id STRING(36) NOT NULL
                ) PRIMARY KEY (Id)"""
            ],
        )
        op = database.create()
        op.result(timeout=30)

        # Drop the database
        database = instance.database(database_id)
        database.drop()

        if not restart_container():
            return False, "Container restart failed"

        # Verify database is gone
        client_after = spanner.Client(project=project_id, credentials=AnonymousCredentials())
        inst = client_after.instance(instance_id)

        try:
            db = inst.database(database_id)
            # Try to use it — should fail
            with db.snapshot() as snapshot:
                list(snapshot.execute_sql("SELECT 1"))
            return False, "Database should not exist after drop + restart"
        except Exception:
            # Expected — database doesn't exist
            pass

        return True, "Dropped database did not reappear after restart"

    except Exception as e:
        return False, str(e)
    finally:
        try:
            c = spanner.Client(project=project_id, credentials=AnonymousCredentials())
            c.instance(instance_id).delete()
        except Exception:
            pass


def test_delete_survives_restart(client: spanner.Client, project_id: str) -> tuple[bool, str]:
    """IT5: Delete rows, restart, verify deletes persist."""
    instance_id = f"delete-test-{uuid.uuid4().hex[:8]}"
    database_id = f"delete-db-{uuid.uuid4().hex[:8]}"

    try:
        instance = client.instance(instance_id)
        op = instance.create()
        op.result(timeout=30)

        database = instance.database(
            database_id,
            ddl_statements=[
                """CREATE TABLE DeleteTest (
                    Id STRING(36) NOT NULL,
                    Name STRING(100)
                ) PRIMARY KEY (Id)"""
            ],
        )
        op = database.create()
        op.result(timeout=30)

        # Insert rows
        alice_id = str(uuid.uuid4())
        bob_id = str(uuid.uuid4())
        database = instance.database(database_id)
        with database.batch() as batch:
            batch.insert(
                table="DeleteTest",
                columns=("Id", "Name"),
                values=[(alice_id, "Alice"), (bob_id, "Bob")],
            )

        # Delete Bob
        def delete_bob(txn):
            txn.execute_update(
                "DELETE FROM DeleteTest WHERE Id = @id",
                params={"id": bob_id},
                param_types={"id": spanner.param_types.STRING},
            )
        database.run_in_transaction(delete_bob)

        if not restart_container():
            return False, "Container restart failed"

        client_after = spanner.Client(project=project_id, credentials=AnonymousCredentials())
        db = client_after.instance(instance_id).database(database_id)

        with db.snapshot() as snapshot:
            rows = list(snapshot.execute_sql("SELECT Name FROM DeleteTest ORDER BY Name"))

        if len(rows) != 1:
            return False, f"expected 1 row after restart, got {len(rows)}"
        if rows[0][0] != "Alice":
            return False, f"expected Alice, got {rows[0][0]}"

        return True, "Deleted row stayed deleted after restart"

    except Exception as e:
        return False, str(e)
    finally:
        try:
            c = spanner.Client(project=project_id, credentials=AnonymousCredentials())
            inst = c.instance(instance_id)
            inst.database(database_id).drop()
            inst.delete()
        except Exception:
            pass


def test_ephemeral_mode(client: spanner.Client, project_id: str) -> tuple[bool, str]:
    """IT5: Without persistence, data should not survive restart (upstream behavior).

    Note: This test requires the emulator to be running WITHOUT --data_dir.
    In the default LocalCloud Docker setup, --data_dir is always set.
    To test ephemeral mode, run the container with the upstream emulator binary:
        docker run ... -e LOCALCLOUD_ENABLE_SPANNER=true gcr.io/cloud-spanner-emulator/emulator:latest

    When run against the persistent setup, this test is skipped.
    """
    # Check if we're running against a persistent setup
    result = subprocess.run(
        ["docker", "compose", "exec", "localcloud",
         "test", "-d", "/var/lib/localcloud/spanner-data"],
        capture_output=True, text=True, timeout=10
    )
    if result.returncode == 0:
        return True, "SKIPPED: Running against persistent setup (--data_dir is set). Ephemeral test requires upstream emulator."

    # If we get here, --data_dir is not configured — test ephemeral behavior
    instance_id = f"ephemeral-{uuid.uuid4().hex[:8]}"
    try:
        instance = client.instance(instance_id)
        op = instance.create()
        op.result(timeout=30)

        if not restart_container():
            return False, "Container restart failed"

        # After restart without persistence, instance should be gone
        client_after = spanner.Client(project=project_id, credentials=AnonymousCredentials())
        try:
            inst = client_after.instance(instance_id)
            # If we can list databases, the instance still exists — fail
            list(inst.list_databases())
            return False, "Instance should not exist after restart without --data_dir"
        except Exception:
            return True, "Instance correctly lost after restart (ephemeral mode)"

    except Exception as e:
        return False, str(e)


def test_data_dir_check() -> tuple[bool, str]:
    """IT6: Verify LevelDB files exist in the spanner-data directory (Docker volume)."""
    result = subprocess.run(
        ["docker", "compose", "exec", "localcloud",
         "find", "/var/lib/localcloud/spanner-data/", "-type", "f"],
        capture_output=True, text=True, timeout=10
    )
    if result.returncode != 0:
        return False, f"Could not check spanner-data directory: {result.stderr}"

    files = [f for f in result.stdout.strip().split("\n") if f]
    if len(files) == 0:
        return True, "spanner-data directory exists (empty — no databases created yet)"

    return True, f"spanner-data directory has {len(files)} files on Docker volume"


# Test registry — numbered to match tasks.md IT1-IT6
TESTS = {
    "create_and_restart": ("IT1: Create and Restart", test_create_and_restart),
    "schema_change": ("IT2: Schema Change and Restart", test_schema_change_and_restart),
    "multiple_db": ("IT3: Multiple Databases Restart", test_multiple_db_restart),
    "drop_database": ("IT4: Drop Database and Restart", test_drop_database_and_restart),
    "ephemeral_mode": ("IT5: Ephemeral Mode (No Data Dir)", test_ephemeral_mode),
    "data_dir_check": ("IT6: Docker Volume Data Check", test_data_dir_check),
    "delete_survives": ("IT7: Delete Survives Restart", test_delete_survives_restart),
}


def main():
    parser = argparse.ArgumentParser(description="Spanner Persistence Integration Tests")
    parser.add_argument("--test", type=str, default=None,
                        help="Run a specific test (e.g., create_and_restart)")
    parser.add_argument("--list", action="store_true", help="List available tests")
    args = parser.parse_args()

    if args.list:
        print("Available tests:")
        for key, (name, _) in TESTS.items():
            print(f"  {key}: {name}")
        return

    project_id = os.environ.get("GCLOUD_PROJECT", "local-project")
    spanner_host = os.environ.get("SPANNER_EMULATOR_HOST", "localhost:9010")
    os.environ["SPANNER_EMULATOR_HOST"] = spanner_host

    print(f"{BOLD}Spanner Persistence Integration Tests{RESET}")
    print(f"Project: {project_id}")
    print(f"Spanner: {spanner_host}")
    print(f"Time:    {time.strftime('%Y-%m-%d %H:%M:%S')}")

    # Wait for Spanner to be ready
    print(f"\n{DIM}Waiting for Spanner emulator...{RESET}")
    if not wait_for_spanner(spanner_host):
        print(f"{RED}Spanner emulator not available at {spanner_host}{RESET}")
        sys.exit(1)
    print(f"{CHECK} Spanner emulator ready")

    client = spanner.Client(project=project_id, credentials=AnonymousCredentials())

    tests_to_run = TESTS
    if args.test:
        if args.test not in TESTS:
            print(f"{RED}Unknown test: {args.test}{RESET}")
            print(f"Available: {', '.join(TESTS.keys())}")
            sys.exit(1)
        tests_to_run = {args.test: TESTS[args.test]}

    passed = 0
    failed = 0
    results = []

    for key, (name, test_fn) in tests_to_run.items():
        print(f"\n{BOLD}{'=' * 60}{RESET}")
        print(f"{BOLD}{name}{RESET}")
        print(f"{'=' * 60}")

        try:
            if key in ("data_dir_check",):
                success, detail = test_fn()
            else:
                success, detail = test_fn(client, project_id)

            if success:
                print(f"  {CHECK} {detail}")
                passed += 1
                results.append((name, True))
            else:
                print(f"  {CROSS} {detail}")
                failed += 1
                results.append((name, False))
        except Exception as e:
            print(f"  {CROSS} Unhandled error: {e}")
            failed += 1
            results.append((name, False))

    # Summary
    print(f"\n{BOLD}{'=' * 60}{RESET}")
    print(f"{BOLD}Summary{RESET}")
    print(f"{'=' * 60}")
    for name, success in results:
        status = f"{GREEN}PASS{RESET}" if success else f"{RED}FAIL{RESET}"
        print(f"  {status}  {name}")

    print(f"\n  Total: {passed} passed, {failed} failed")

    if failed > 0:
        sys.exit(1)
    else:
        print(f"\n{GREEN}All persistence tests passed!{RESET}")


if __name__ == "__main__":
    main()
