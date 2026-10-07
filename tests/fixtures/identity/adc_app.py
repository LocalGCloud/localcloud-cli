"""A Google Cloud application that knows nothing about LocalCloud.

It finds credentials with Application Default Credentials exactly as it would on Google Cloud,
mints an access token and an ID token for its downstream service, and calls that service
(RECEIVER_URL, its only configuration) with the ID token. It prints one JSON line describing
what ADC chose; tokens themselves are never printed.

tests/integration/test_identity_session_acceptance.py runs it unchanged under
`lc env --identity`.
"""

from __future__ import annotations

import hashlib
import json
import os
import sys

import google.auth
import google.auth.transport.requests
import requests
from google.oauth2 import id_token


def main() -> int:
    receiver = os.environ["RECEIVER_URL"]
    report: dict[str, object] = {}
    try:
        credentials, project = google.auth.default(
            scopes=["https://www.googleapis.com/auth/cloud-platform"]
        )
        report["credentials"] = f"{type(credentials).__module__}.{type(credentials).__name__}"
        report["project"] = project
        request = google.auth.transport.requests.Request()
        credentials.refresh(request)
        report["access_token_sha256"] = hashlib.sha256(credentials.token.encode()).hexdigest()[:16]
        token = id_token.fetch_id_token(request, receiver)
        response = requests.post(
            receiver,
            headers={"Authorization": f"Bearer {token}"},
            json={"project": project},
            timeout=30,
        )
        report["receiver_status"] = response.status_code
    except Exception as error:  # noqa: BLE001 - the report names any failure
        report["error"] = f"{type(error).__name__}: {error}"[:600]
    print(json.dumps(report), flush=True)
    return 0 if "error" not in report else 1


if __name__ == "__main__":
    sys.exit(main())
