"""Google Cloud Spanner demo using the official Python SDK."""

import os
import uuid

from google.auth.credentials import AnonymousCredentials
from google.cloud import spanner


def run(project_id: str) -> list[tuple[str, bool, str]]:
    """Run Spanner demo operations. Returns list of (operation, success, detail)."""
    results = []

    # Ensure the emulator env var is set so the SDK creates an insecure channel.
    if not os.environ.get("SPANNER_EMULATOR_HOST"):
        os.environ["SPANNER_EMULATOR_HOST"] = "localhost:9010"

    client = spanner.Client(project=project_id, credentials=AnonymousCredentials())
    instance_id = f"demo-instance-{uuid.uuid4().hex[:8]}"
    database_id = f"demo-db-{uuid.uuid4().hex[:8]}"

    # 1. Create instance
    try:
        instance = client.instance(instance_id)
        operation = instance.create()
        operation.result(timeout=30)
        results.append(("Create instance", True, instance_id))
    except Exception as e:
        results.append(("Create instance", False, str(e)))
        return results

    instance = client.instance(instance_id)

    # 2. Create database with schema
    try:
        database = instance.database(
            database_id,
            ddl_statements=[
                """CREATE TABLE Users (
                    UserId STRING(36) NOT NULL,
                    Name STRING(100),
                    Age INT64,
                    Balance INT64
                ) PRIMARY KEY (UserId)"""
            ],
        )
        operation = database.create()
        operation.result(timeout=30)
        results.append(("Create database", True, database_id))
    except Exception as e:
        results.append(("Create database", False, str(e)))
        return results

    database = instance.database(database_id)

    # 3. Insert rows
    alice_id = str(uuid.uuid4())
    bob_id = str(uuid.uuid4())
    charlie_id = str(uuid.uuid4())
    try:
        with database.batch() as batch:
            batch.insert(
                table="Users",
                columns=("UserId", "Name", "Age", "Balance"),
                values=[
                    (alice_id, "Alice", 30, 1000),
                    (bob_id, "Bob", 25, 500),
                    (charlie_id, "Charlie", 35, 750),
                ],
            )
        results.append(("Insert rows", True, "3 rows"))
    except Exception as e:
        results.append(("Insert rows", False, str(e)))

    # 4. Read rows (SQL)
    try:
        with database.snapshot() as snapshot:
            result_set = snapshot.execute_sql("SELECT Name, Age FROM Users ORDER BY Name")
            rows = list(result_set)
            assert len(rows) >= 3, f"expected >=3 rows, got {len(rows)}"
            results.append(("Read rows (SQL)", True, f"{len(rows)} rows"))
    except Exception as e:
        results.append(("Read rows (SQL)", False, str(e)))

    # 5. Query with filter
    try:
        with database.snapshot() as snapshot:
            result_set = snapshot.execute_sql(
                "SELECT Name, Age FROM Users WHERE Age >= @min_age",
                params={"min_age": 30},
                param_types={"min_age": spanner.param_types.INT64},
            )
            rows = list(result_set)
            assert len(rows) >= 2, f"expected >=2 rows, got {len(rows)}"
            results.append(("Query with filter", True, f"{len(rows)} rows (age>=30)"))
    except Exception as e:
        results.append(("Query with filter", False, str(e)))

    # 6. Update DDL (add column)
    try:
        operation = database.update_ddl(
            ["ALTER TABLE Users ADD COLUMN Email STRING(200)"]
        )
        operation.result(timeout=30)
        results.append(("Update DDL", True, "added Email column"))
    except Exception as e:
        results.append(("Update DDL", False, str(e)))

    # 7. UPDATE via DML
    try:
        def update_alice(transaction):
            transaction.execute_update(
                "UPDATE Users SET Age = 31 WHERE Name = 'Alice'"
            )
        database.run_in_transaction(update_alice)
        with database.snapshot() as snapshot:
            result_set = snapshot.execute_sql(
                "SELECT Age FROM Users WHERE Name = 'Alice'"
            )
            rows = list(result_set)
            assert rows[0][0] == 31, f"expected age=31, got {rows[0][0]}"
        results.append(("UPDATE via DML", True, "Alice age=31"))
    except Exception as e:
        results.append(("UPDATE via DML", False, str(e)))

    # 8. DELETE via DML
    try:
        def delete_bob(transaction):
            transaction.execute_update(
                "DELETE FROM Users WHERE Name = 'Bob'"
            )
        database.run_in_transaction(delete_bob)
        with database.snapshot() as snapshot:
            result_set = snapshot.execute_sql("SELECT COUNT(*) FROM Users")
            count = list(result_set)[0][0]
            assert count == 2, f"expected 2 rows after delete, got {count}"
        results.append(("DELETE via DML", True, "Bob deleted, 2 remain"))
    except Exception as e:
        results.append(("DELETE via DML", False, str(e)))

    # 9. Atomic multi-row update via DML (demonstrates transactional semantics)
    try:
        # Perform two related updates atomically via database.run_in_transaction
        # This simulates a transfer: Alice loses 200, Charlie gains 200
        def transfer_funds(transaction):
            # Both updates are atomic — either both succeed or both fail
            transaction.execute_update(
                "UPDATE Users SET Balance = Balance - 200 WHERE UserId = @uid",
                params={"uid": alice_id},
                param_types={"uid": spanner.param_types.STRING},
            )
            transaction.execute_update(
                "UPDATE Users SET Balance = Balance + 200 WHERE UserId = @uid",
                params={"uid": charlie_id},
                param_types={"uid": spanner.param_types.STRING},
            )

        database.run_in_transaction(transfer_funds)

        # Verify the updates by checking each user in separate snapshot reads
        with database.snapshot() as snapshot:
            alice_result = list(snapshot.execute_sql(
                "SELECT Balance FROM Users WHERE UserId = @uid",
                params={"uid": alice_id},
                param_types={"uid": spanner.param_types.STRING},
            ))

        with database.snapshot() as snapshot:
            charlie_result = list(snapshot.execute_sql(
                "SELECT Balance FROM Users WHERE UserId = @uid",
                params={"uid": charlie_id},
                param_types={"uid": spanner.param_types.STRING},
            ))

        alice_bal = alice_result[0][0]
        charlie_bal = charlie_result[0][0]
        assert alice_bal == 800, f"expected Alice balance=800, got {alice_bal}"
        assert charlie_bal == 950, f"expected Charlie balance=950, got {charlie_bal}"
        results.append(("Atomic multi-row update", True, "Alice=800, Charlie=950"))
    except Exception as e:
        results.append(("Read-write transaction", False, str(e)))

    # 10. Secondary index
    try:
        operation = database.update_ddl(
            ["CREATE INDEX UsersByAge ON Users(Age)"]
        )
        operation.result(timeout=30)
        # Query using the index (Spanner can use it automatically)
        with database.snapshot() as snapshot:
            result_set = snapshot.execute_sql(
                "SELECT Name, Age FROM Users@{FORCE_INDEX=UsersByAge} WHERE Age >= 30"
            )
            rows = list(result_set)
            assert len(rows) >= 1, f"expected >=1 rows via index, got {len(rows)}"
        results.append(("Secondary index", True, f"{len(rows)} rows via UsersByAge"))
    except Exception as e:
        results.append(("Secondary index", False, str(e)))

    # 11. Drop database
    try:
        database.drop()
        results.append(("Drop database", True, database_id))
    except Exception as e:
        results.append(("Drop database", False, str(e)))

    # 12. Delete instance
    try:
        instance.delete()
        results.append(("Delete instance", True, instance_id))
    except Exception as e:
        results.append(("Delete instance", False, str(e)))

    return results
