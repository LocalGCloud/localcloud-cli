"""Google Cloud Storage demo using the official Python SDK."""

import os
import uuid
import warnings

import urllib3
from requests.adapters import HTTPAdapter
from google.auth.credentials import AnonymousCredentials
from google.auth.transport.requests import AuthorizedSession
from google.cloud import storage


def _patch_ssl_for_self_signed_cert():
    """Patch AuthorizedSession to skip SSL verification for fake-gcs-server's self-signed cert."""

    class _NoVerifyAdapter(HTTPAdapter):
        def send(self, request, **kwargs):
            kwargs["verify"] = False
            return super().send(request, **kwargs)

    _orig_init = AuthorizedSession.__init__

    def _new_init(self, *a, **kw):
        _orig_init(self, *a, **kw)
        self.verify = False
        self.mount("https://", _NoVerifyAdapter())

    AuthorizedSession.__init__ = _new_init
    warnings.filterwarnings("ignore", category=urllib3.exceptions.InsecureRequestWarning)


def run(project_id: str, keep_data: bool = False) -> list[tuple[str, bool, str]]:
    """Run GCS demo operations. Returns list of (operation, success, detail)."""
    results = []

    # fake-gcs-server uses HTTPS with a self-signed cert.
    # Rewrite emulator host to https:// and disable SSL verification.
    host = os.environ.get("STORAGE_EMULATOR_HOST", "http://localhost:4443")
    # if host.startswith("http://"):
    #     os.environ["STORAGE_EMULATOR_HOST"] = "https://" + host[len("http://"):]

    _patch_ssl_for_self_signed_cert()

    client = storage.Client(project=project_id, credentials=AnonymousCredentials())
    bucket_name = f"demo-bucket-{uuid.uuid4().hex[:8]}"
    blob_name = "hello.txt"
    content = "Hello from LocalCloud!"

    # 1. Create bucket
    try:
        bucket = client.create_bucket(bucket_name)
        results.append(("Create bucket", True, bucket_name))
    except Exception as e:
        results.append(("Create bucket", False, str(e)))
        return results

    # 2. Upload object
    try:
        blob = bucket.blob(blob_name)
        blob.upload_from_string(content)
        results.append(("Upload object", True, blob_name))
    except Exception as e:
        results.append(("Upload object", False, str(e)))

    # 3. Download object
    try:
        downloaded = bucket.blob(blob_name).download_as_text()
        assert downloaded == content, f"expected {content!r}, got {downloaded!r}"
        results.append(("Download object", True, "content matches"))
    except Exception as e:
        results.append(("Download object", False, str(e)))

    # 4. List objects
    try:
        blobs = list(client.list_blobs(bucket_name))
        names = [b.name for b in blobs]
        assert blob_name in names, f"{blob_name} not in {names}"
        results.append(("List objects", True, f"{len(blobs)} object(s)"))
    except Exception as e:
        results.append(("List objects", False, str(e)))

    # 5. Copy object
    try:
        source_blob = bucket.blob(blob_name)
        copied_blob = bucket.copy_blob(source_blob, bucket, "hello-copy.txt")
        assert copied_blob.exists(), "copied blob does not exist"
        results.append(("Copy object", True, "hello-copy.txt"))
    except Exception as e:
        results.append(("Copy object", False, str(e)))

    # 6. Object metadata
    try:
        meta_blob = bucket.blob("meta-test.txt")
        meta_blob.metadata = {"author": "localcloud", "version": "1"}
        meta_blob.upload_from_string("metadata test")
        meta_blob.reload()
        assert meta_blob.metadata.get("author") == "localcloud", f"expected 'localcloud', got {meta_blob.metadata}"
        results.append(("Object metadata", True, "author=localcloud"))
    except Exception as e:
        results.append(("Object metadata", False, str(e)))

    # 7. List with prefix
    try:
        bucket.blob("folder/a.txt").upload_from_string("a")
        bucket.blob("folder/b.txt").upload_from_string("b")
        bucket.blob("other.txt").upload_from_string("other")
        prefix_blobs = list(client.list_blobs(bucket_name, prefix="folder/"))
        prefix_names = [b.name for b in prefix_blobs]
        assert "folder/a.txt" in prefix_names and "folder/b.txt" in prefix_names, f"prefix filter failed: {prefix_names}"
        assert "other.txt" not in prefix_names, f"other.txt should not appear: {prefix_names}"
        results.append(("List with prefix", True, f"{len(prefix_blobs)} object(s)"))
    except Exception as e:
        results.append(("List with prefix", False, str(e)))

    # 8. List buckets
    try:
        buckets = list(client.list_buckets())
        bucket_names = [b.name for b in buckets]
        assert bucket_name in bucket_names, f"{bucket_name} not in {bucket_names}"
        results.append(("List buckets", True, f"{len(buckets)} bucket(s)"))
    except Exception as e:
        results.append(("List buckets", False, str(e)))

    # 9. Delete object
    if not keep_data:
        try:
            bucket.blob(blob_name).delete()
            results.append(("Delete object", True, blob_name))
        except Exception as e:
            results.append(("Delete object", False, str(e)))
    else:
        results.append(("Skip cleanup", True, "data preserved for inspection"))

    # 10. Upload with content-type (JSON file)
    try:
        json_blob = bucket.blob("data/config.json")
        json_content = '{"database": "postgres", "port": 5432}'
        json_blob.upload_from_string(json_content, content_type="application/json")
        json_blob.reload()
        assert json_blob.content_type == "application/json", \
            f"expected application/json, got {json_blob.content_type}"
        downloaded_json = json_blob.download_as_text()
        assert downloaded_json == json_content, "JSON content mismatch"
        results.append(("Upload with content-type", True, "application/json preserved"))
    except Exception as e:
        results.append(("Upload with content-type", False, str(e)))

    # 11. Folder simulation with delimiter
    try:
        bucket.blob("reports/2024/q1.csv").upload_from_string("q1,revenue,100")
        bucket.blob("reports/2024/q2.csv").upload_from_string("q2,revenue,200")
        bucket.blob("reports/2024/q3.csv").upload_from_string("q3,revenue,300")
        # List with delimiter to get "folder" prefixes
        iterator = client.list_blobs(bucket_name, prefix="reports/", delimiter="/")
        blobs_at_root = list(iterator)
        prefixes = list(iterator.prefixes)
        assert "reports/2024/" in prefixes, f"expected 'reports/2024/' in prefixes, got {prefixes}"
        results.append(("Folder simulation with delimiter", True, f"prefixes={prefixes}"))
    except Exception as e:
        results.append(("Folder simulation with delimiter", False, str(e)))

    # 12. Delete bucket (force=True to delete remaining objects)
    if not keep_data:
        try:
            bucket.delete(force=True)
            results.append(("Delete bucket", True, bucket_name))
        except Exception as e:
            results.append(("Delete bucket", False, str(e)))
    else:
        results.append(("Skip cleanup", True, "data preserved for inspection"))

    return results
