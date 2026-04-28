"""Google Cloud Bigtable demo using the official Python SDK."""

import os
import uuid
import struct

from google.auth.credentials import AnonymousCredentials
from google.cloud import bigtable
from google.cloud.bigtable import column_family
from google.cloud.bigtable import row_filters


def run(project_id: str, keep_data: bool = False) -> list[tuple[str, bool, str]]:
    """Run Bigtable demo operations. Returns list of (operation, success, detail)."""
    results = []

    # Ensure the emulator env var is set so the SDK creates an insecure channel.
    if not os.environ.get("BIGTABLE_EMULATOR_HOST"):
        os.environ["BIGTABLE_EMULATOR_HOST"] = "localhost:8087"

    client = bigtable.Client(project=project_id, admin=True, credentials=AnonymousCredentials())
    instance = client.instance("demo-instance")
    try:
        if not instance.exists():
            instance.create(location_id="local", serve_nodes=1)
            results.append(("Create instance", True, "demo-instance"))
    except Exception as e:
        results.append(("Create instance", False, str(e)))
        return results

    table_id = f"demo-table-{uuid.uuid4().hex[:8]}"
    cf_id = "cf1"

    # 1. Create table with column family
    try:
        table = instance.table(table_id)
        gc_rule = column_family.MaxVersionsGCRule(2)
        table.create(column_families={cf_id: gc_rule})
        results.append(("Create table", True, table_id))
    except Exception as e:
        results.append(("Create table", False, str(e)))
        return results

    table = instance.table(table_id)

    # 2. Write rows
    try:
        for i in range(5):
            row_key = f"user#{i:04d}".encode()
            row = table.direct_row(row_key)
            row.set_cell(cf_id, "name", f"User {i}")
            row.set_cell(cf_id, "age", str(20 + i))
            row.commit()
        results.append(("Write rows", True, "5 rows"))
    except Exception as e:
        results.append(("Write rows", False, str(e)))

    # 3. Read single row
    try:
        row = table.read_row("user#0000".encode())
        assert row is not None, "row user#0000 not found"
        name = row.cells[cf_id]["name".encode()][0].value.decode()
        assert name == "User 0", f"expected 'User 0', got '{name}'"
        results.append(("Read single row", True, f"name={name}"))
    except Exception as e:
        results.append(("Read single row", False, str(e)))

    # 4. Read row range
    try:
        partial_rows = table.read_rows(
            start_key="user#0001".encode(),
            end_key="user#0004".encode(),
        )
        partial_rows.consume_all()
        rows = partial_rows.rows
        assert len(rows) >= 2, f"expected >=2 rows, got {len(rows)}"
        results.append(("Read row range", True, f"{len(rows)} rows"))
    except Exception as e:
        results.append(("Read row range", False, str(e)))

    # 5. Mutate row (update)
    try:
        row = table.direct_row("user#0000".encode())
        row.set_cell(cf_id, "name", "Updated User 0")
        row.set_cell(cf_id, "email", "user0@example.com")
        row.commit()
        updated = table.read_row("user#0000".encode())
        name = updated.cells[cf_id]["name".encode()][0].value.decode()
        assert name == "Updated User 0", f"expected 'Updated User 0', got '{name}'"
        results.append(("Mutate row", True, f"name={name}"))
    except Exception as e:
        results.append(("Mutate row", False, str(e)))

    # 6. Delete row
    try:
        row = table.direct_row("user#0004".encode())
        row.delete()
        row.commit()
        deleted = table.read_row("user#0004".encode())
        assert deleted is None, "row user#0004 still exists after delete"
        results.append(("Delete row", True, "user#0004"))
    except Exception as e:
        results.append(("Delete row", False, str(e)))

    # 7. Add column family
    try:
        cf2_id = "cf2"
        gc_rule2 = column_family.MaxVersionsGCRule(3)
        cf2 = table.column_family(cf2_id, gc_rule=gc_rule2)
        cf2.create()
        results.append(("Add column family", True, "cf2"))
    except Exception as e:
        results.append(("Add column family", False, str(e)))

    # 8. Write to multiple column families
    cf2_id = "cf2"
    try:
        row = table.direct_row("user#0001".encode())
        row.set_cell(cf_id, "name", "User 1 Updated")
        row.set_cell(cf2_id, "address", "123 Main St")
        row.set_cell(cf2_id, "city", "Seattle")
        row.commit()
        read_row = table.read_row("user#0001".encode())
        cf1_name = read_row.cells[cf_id]["name".encode()][0].value.decode()
        cf2_addr = read_row.cells[cf2_id]["address".encode()][0].value.decode()
        assert cf1_name == "User 1 Updated", f"cf1 name mismatch: {cf1_name}"
        assert cf2_addr == "123 Main St", f"cf2 address mismatch: {cf2_addr}"
        results.append(("Write to multiple CFs", True, "cf1+cf2 on user#0001"))
    except Exception as e:
        results.append(("Write to multiple CFs", False, str(e)))

    # 9. Row filter (CellsColumnLimitFilter — latest version only)
    try:
        # Write a second version to user#0000 name
        row = table.direct_row("user#0000".encode())
        row.set_cell(cf_id, "name", "User 0 v3")
        row.commit()
        # Read with filter: only latest cell per column
        filtered_row = table.read_row(
            "user#0000".encode(),
            filter_=row_filters.CellsColumnLimitFilter(1),
        )
        name_cells = filtered_row.cells[cf_id]["name".encode()]
        assert len(name_cells) == 1, f"expected 1 cell with filter, got {len(name_cells)}"
        assert name_cells[0].value.decode() == "User 0 v3", \
            f"expected latest version, got {name_cells[0].value.decode()}"
        results.append(("Row filter (latest version)", True, "1 cell returned"))
    except Exception as e:
        results.append(("Row filter (latest version)", False, str(e)))

    # 10. List tables
    try:
        tables = instance.list_tables()
        table_ids = [t.table_id for t in tables]
        assert table_id in table_ids, f"{table_id} not in {table_ids}"
        results.append(("List tables", True, f"{len(tables)} table(s)"))
    except Exception as e:
        results.append(("List tables", False, str(e)))

    # 11. Delete table
    if not keep_data:
        try:
            table.delete()
            results.append(("Delete table", True, table_id))
        except Exception as e:
            results.append(("Delete table", False, str(e)))
    else:
        results.append(("Skip cleanup", True, "data preserved for inspection"))

    return results
