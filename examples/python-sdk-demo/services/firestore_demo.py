"""Google Cloud Firestore demo using the official Python SDK."""

import os
import uuid

from google.auth.credentials import AnonymousCredentials
from google.cloud import firestore


def run(project_id: str, keep_data: bool = False) -> list[tuple[str, bool, str]]:
    """Run Firestore demo operations. Returns list of (operation, success, detail)."""
    results = []

    # Ensure the emulator env var is set so the SDK creates an insecure channel.
    if not os.environ.get("FIRESTORE_EMULATOR_HOST"):
        os.environ["FIRESTORE_EMULATOR_HOST"] = "localhost:8086"

    db = firestore.Client(project=project_id, credentials=AnonymousCredentials())
    collection_name = f"demo-collection-{uuid.uuid4().hex[:8]}"
    doc_id = f"doc-{uuid.uuid4().hex[:8]}"
    col_ref = db.collection(collection_name)

    # 1. Add document
    try:
        doc_data = {"name": "Alice", "age": 30, "city": "Seattle"}
        col_ref.document(doc_id).set(doc_data)
        results.append(("Add document", True, doc_id))
    except Exception as e:
        results.append(("Add document", False, str(e)))
        return results

    # 2. Get document
    try:
        doc = col_ref.document(doc_id).get()
        assert doc.exists, "document does not exist"
        data = doc.to_dict()
        assert data["name"] == "Alice", f"expected Alice, got {data['name']}"
        results.append(("Get document", True, f"name={data['name']}"))
    except Exception as e:
        results.append(("Get document", False, str(e)))

    # 3. Update document
    try:
        col_ref.document(doc_id).update({"age": 31, "city": "Portland"})
        updated = col_ref.document(doc_id).get().to_dict()
        assert updated["age"] == 31, f"expected 31, got {updated['age']}"
        assert updated["city"] == "Portland"
        results.append(("Update document", True, "age=31, city=Portland"))
    except Exception as e:
        results.append(("Update document", False, str(e)))

    # 4. Query collection
    try:
        from google.cloud.firestore_v1.base_query import FieldFilter
        query = col_ref.where(filter=FieldFilter("age", ">=", 30))
        docs = list(query.stream())
        assert len(docs) >= 1, f"expected >=1 doc, got {len(docs)}"
        results.append(("Query collection", True, f"{len(docs)} match(es)"))
    except Exception as e:
        results.append(("Query collection", False, str(e)))

    # 5. Batch write
    try:
        batch = db.batch()
        batch_ids = [f"batch-{i}-{uuid.uuid4().hex[:6]}" for i in range(3)]
        for bid in batch_ids:
            batch.set(col_ref.document(bid), {"name": bid, "age": 25, "city": "Denver"})
        batch.commit()
        for bid in batch_ids:
            assert col_ref.document(bid).get().exists, f"batch doc {bid} missing"
        results.append(("Batch write", True, f"{len(batch_ids)} docs"))
    except Exception as e:
        results.append(("Batch write", False, str(e)))

    # 6. Subcollection
    try:
        sub_doc_id = f"settings-{uuid.uuid4().hex[:6]}"
        sub_ref = col_ref.document(doc_id).collection("settings").document(sub_doc_id)
        sub_ref.set({"theme": "dark", "notifications": True})
        sub_doc = sub_ref.get()
        assert sub_doc.exists, "subcollection doc does not exist"
        assert sub_doc.to_dict()["theme"] == "dark"
        results.append(("Subcollection", True, f"settings/{sub_doc_id}"))
    except Exception as e:
        results.append(("Subcollection", False, str(e)))

    # 7. Multiple query filters
    try:
        from google.cloud.firestore_v1.base_query import FieldFilter
        query = col_ref.where(filter=FieldFilter("age", ">=", 25)).where(
            filter=FieldFilter("city", "==", "Denver")
        )
        docs = list(query.stream())
        assert len(docs) >= 1, f"expected >=1 doc, got {len(docs)}"
        results.append(("Multiple query filters", True, f"{len(docs)} match(es)"))
    except Exception as e:
        results.append(("Multiple query filters", False, str(e)))

    # 8. List documents
    try:
        all_docs = list(col_ref.stream())
        # We have the original doc + 3 batch docs = at least 4
        assert len(all_docs) >= 4, f"expected >=4 docs, got {len(all_docs)}"
        results.append(("List documents", True, f"{len(all_docs)} document(s)"))
    except Exception as e:
        results.append(("List documents", False, str(e)))

    # 9. Array field and contains query
    try:
        col_ref.document(doc_id).update({"tags": ["python", "gcp", "demo"]})
        from google.cloud.firestore_v1.base_query import FieldFilter
        query = col_ref.where(filter=FieldFilter("tags", "array_contains", "gcp"))
        docs = list(query.stream())
        assert len(docs) >= 1, f"expected >=1 doc with tag 'gcp', got {len(docs)}"
        found_tags = docs[0].to_dict().get("tags", [])
        assert "gcp" in found_tags, f"expected 'gcp' in tags, got {found_tags}"
        results.append(("Array field + contains query", True, f"{len(docs)} match(es)"))
    except Exception as e:
        results.append(("Array field + contains query", False, str(e)))

    # 10. Order by + limit
    try:
        query = col_ref.order_by("age").limit(2)
        docs = list(query.stream())
        assert len(docs) == 2, f"expected 2 docs, got {len(docs)}"
        ages = [d.to_dict()["age"] for d in docs]
        assert ages[0] <= ages[1], f"expected ascending order, got {ages}"
        results.append(("Order by + limit", True, f"ages={ages}"))
    except Exception as e:
        results.append(("Order by + limit", False, str(e)))

    # 11. Transaction (atomic read-modify-write)
    try:
        counter_id = f"counter-{uuid.uuid4().hex[:6]}"
        col_ref.document(counter_id).set({"count": 0})

        @firestore.transactional
        def increment_counter(transaction, doc_ref):
            snapshot = doc_ref.get(transaction=transaction)
            current = snapshot.to_dict()["count"]
            transaction.update(doc_ref, {"count": current + 5})

        transaction = db.transaction()
        counter_ref = col_ref.document(counter_id)
        increment_counter(transaction, counter_ref)

        final = counter_ref.get().to_dict()
        assert final["count"] == 5, f"expected count=5 after transaction, got {final['count']}"
        results.append(("Transaction", True, f"count=0->5"))
    except Exception as e:
        results.append(("Transaction", False, str(e)))

    # 12. Delete document
    if not keep_data:
        try:
            col_ref.document(doc_id).delete()
            deleted = col_ref.document(doc_id).get()
            assert not deleted.exists, "document still exists after delete"
            results.append(("Delete document", True, doc_id))
        except Exception as e:
            results.append(("Delete document", False, str(e)))
    else:
        results.append(("Skip cleanup", True, "data preserved for inspection"))

    return results
