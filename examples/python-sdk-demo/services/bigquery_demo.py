"""Google Cloud BigQuery demo using the official Python SDK."""

import os
import uuid

from google.auth.credentials import AnonymousCredentials
from google.cloud import bigquery


def run(project_id: str) -> list[tuple[str, bool, str]]:
    """Run BigQuery demo operations. Returns list of (operation, success, detail)."""
    results = []

    # Ensure the emulator env var is set so the SDK routes to LocalCloud.
    if not os.environ.get("BIGQUERY_EMULATOR_HOST"):
        os.environ["BIGQUERY_EMULATOR_HOST"] = "http://localhost:9050"

    client = bigquery.Client(project=project_id, credentials=AnonymousCredentials())
    dataset_id = f"demo_dataset_{uuid.uuid4().hex[:8]}"
    dataset_ref = f"{project_id}.{dataset_id}"
    employees_table_id = f"{dataset_ref}.employees"
    departments_table_id = f"{dataset_ref}.departments"

    # 1. Create dataset
    try:
        dataset = bigquery.Dataset(dataset_ref)
        dataset.location = "US"
        client.create_dataset(dataset)
        results.append(("Create dataset", True, dataset_id))
    except Exception as e:
        results.append(("Create dataset", False, str(e)))
        return results

    # 2. Create table with schema
    try:
        schema = [
            bigquery.SchemaField("name", "STRING", mode="REQUIRED"),
            bigquery.SchemaField("department", "STRING", mode="REQUIRED"),
            bigquery.SchemaField("salary", "INTEGER", mode="REQUIRED"),
            bigquery.SchemaField("hire_date", "STRING", mode="NULLABLE"),
        ]
        table = bigquery.Table(employees_table_id, schema=schema)
        client.create_table(table)
        results.append(("Create employees table", True, "employees"))
    except Exception as e:
        results.append(("Create employees table", False, str(e)))

    # 3. Insert employee rows
    try:
        rows = [
            {"name": "Alice", "department": "Engineering", "salary": 120000, "hire_date": "2020-01-15"},
            {"name": "Bob", "department": "Engineering", "salary": 115000, "hire_date": "2020-03-20"},
            {"name": "Charlie", "department": "Marketing", "salary": 95000, "hire_date": "2019-06-01"},
            {"name": "Diana", "department": "Marketing", "salary": 98000, "hire_date": "2021-02-10"},
            {"name": "Eve", "department": "Sales", "salary": 105000, "hire_date": "2018-11-30"},
            {"name": "Frank", "department": "Sales", "salary": 110000, "hire_date": "2019-08-15"},
            {"name": "Grace", "department": "Engineering", "salary": 130000, "hire_date": "2017-04-01"},
            {"name": "Hank", "department": "HR", "salary": 85000, "hire_date": "2020-09-01"},
            {"name": "Ivy", "department": "HR", "salary": 88000, "hire_date": "2021-07-15"},
            {"name": "Jack", "department": "Engineering", "salary": 140000, "hire_date": "2016-01-10"},
        ]
        errors = client.insert_rows_json(employees_table_id, rows)
        assert errors == [], f"insert errors: {errors}"
        results.append(("Insert employee rows", True, f"{len(rows)} rows"))
    except Exception as e:
        results.append(("Insert employee rows", False, str(e)))

    # 4. SELECT with WHERE
    try:
        query = f"SELECT name, salary FROM `{employees_table_id}` WHERE department = 'Engineering'"
        rows = list(client.query(query).result())
        assert len(rows) == 4, f"expected 4 engineering rows, got {len(rows)}"
        names = {r.name for r in rows}
        assert "Alice" in names and "Bob" in names, f"missing expected names: {names}"
        results.append(("SELECT with WHERE", True, f"{len(rows)} engineering employees"))
    except Exception as e:
        results.append(("SELECT with WHERE", False, str(e)))

    # 5. Aggregate GROUP BY
    try:
        query = f"""
            SELECT department, COUNT(*) AS cnt, AVG(salary) AS avg_salary
            FROM `{employees_table_id}`
            GROUP BY department
        """
        rows = list(client.query(query).result())
        assert len(rows) >= 4, f"expected >=4 departments, got {len(rows)}"
        dept_map = {r.department: r for r in rows}
        assert dept_map["Engineering"].cnt == 4, f"expected 4 engineers, got {dept_map['Engineering'].cnt}"
        results.append(("Aggregate GROUP BY", True, f"{len(rows)} departments"))
    except Exception as e:
        results.append(("Aggregate GROUP BY", False, str(e)))

    # 6. ORDER BY + LIMIT (top 3 salaries)
    try:
        query = f"SELECT name, salary FROM `{employees_table_id}` ORDER BY salary DESC LIMIT 3"
        rows = list(client.query(query).result())
        assert len(rows) == 3, f"expected 3 rows, got {len(rows)}"
        assert rows[0].salary >= rows[1].salary >= rows[2].salary, "not sorted descending"
        results.append(("ORDER BY + LIMIT", True, f"top={rows[0].name} ${rows[0].salary}"))
    except Exception as e:
        results.append(("ORDER BY + LIMIT", False, str(e)))

    # 7. Create second table (departments)
    try:
        dept_schema = [
            bigquery.SchemaField("dept_name", "STRING", mode="REQUIRED"),
            bigquery.SchemaField("budget", "INTEGER", mode="REQUIRED"),
            bigquery.SchemaField("head", "STRING", mode="NULLABLE"),
        ]
        dept_table = bigquery.Table(departments_table_id, schema=dept_schema)
        client.create_table(dept_table)
        results.append(("Create departments table", True, "departments"))
    except Exception as e:
        results.append(("Create departments table", False, str(e)))

    # 8. INSERT into departments
    try:
        dept_rows = [
            {"dept_name": "Engineering", "budget": 5000000, "head": "Grace"},
            {"dept_name": "Marketing", "budget": 2000000, "head": "Charlie"},
            {"dept_name": "Sales", "budget": 3000000, "head": "Frank"},
        ]
        errors = client.insert_rows_json(departments_table_id, dept_rows)
        assert errors == [], f"insert errors: {errors}"
        results.append(("Insert department rows", True, f"{len(dept_rows)} rows"))
    except Exception as e:
        results.append(("Insert department rows", False, str(e)))

    # 9. JOIN query
    try:
        query = f"""
            SELECT e.name, e.department, d.budget
            FROM `{employees_table_id}` e
            JOIN `{departments_table_id}` d ON e.department = d.dept_name
        """
        rows = list(client.query(query).result())
        # HR has no entry in departments, so only Engineering+Marketing+Sales employees (8)
        assert len(rows) >= 8, f"expected >=8 joined rows, got {len(rows)}"
        assert all(r.budget > 0 for r in rows), "budget should be positive"
        results.append(("JOIN query", True, f"{len(rows)} joined rows"))
    except Exception as e:
        results.append(("JOIN query", False, str(e)))

    # 10. Subquery (above-average salary)
    try:
        query = f"""
            SELECT name, salary
            FROM `{employees_table_id}`
            WHERE salary > (SELECT AVG(salary) FROM `{employees_table_id}`)
        """
        rows = list(client.query(query).result())
        assert len(rows) >= 1, f"expected >=1 above-avg rows, got {len(rows)}"
        results.append(("Subquery", True, f"{len(rows)} above-avg earners"))
    except Exception as e:
        results.append(("Subquery", False, str(e)))

    # 11. UNION query
    try:
        query = f"""
            SELECT name, 'senior' AS level FROM `{employees_table_id}` WHERE salary >= 120000
            UNION ALL
            SELECT name, 'junior' AS level FROM `{employees_table_id}` WHERE salary < 100000
        """
        rows = list(client.query(query).result())
        assert len(rows) >= 2, f"expected >=2 union rows, got {len(rows)}"
        levels = {r.level for r in rows}
        results.append(("UNION query", True, f"{len(rows)} rows, levels={levels}"))
    except Exception as e:
        results.append(("UNION query", False, str(e)))

    # 12. Delete dataset (and contents)
    try:
        client.delete_dataset(dataset_ref, delete_contents=True)
        results.append(("Delete dataset", True, dataset_id))
    except Exception as e:
        results.append(("Delete dataset", False, str(e)))

    return results
