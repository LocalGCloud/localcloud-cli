"""Vendor LocalCloud's identity session contract for the CLI's fixture tests.

`lc env --identity` consumes `POST/GET/DELETE /identity/sessions` and the typed relay and SDK
profiles they carry. LocalCloud publishes their schemas in its API contract projection
(`specs/api/openapi/localcloud.json`). This script copies those schemas and the operations'
request/response references from a LocalCloud checkout into
`tests/fixtures/identity/localcloud-contract.json`, recording the checkout's revision, so that
`tests/test_identity_contract.py` validates every identity fixture against the producer's
contract without a LocalCloud checkout.

    python3 scripts/sync-identity-contract.py --localcloud ../localcloud           # update
    python3 scripts/sync-identity-contract.py --localcloud ../localcloud --check   # verify

`--check` exits 1 when the vendored contract differs from the checkout's projection.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT = ROOT / "tests" / "fixtures" / "identity" / "localcloud-contract.json"
PROJECTION = Path("specs") / "api" / "openapi" / "localcloud.json"
SCHEMAS = (
    "IdentitySessionRequest",
    "IdentitySession",
    "IdentitySessionCreated",
    "IdentitySessionList",
    "IdentityRelayProfile",
    "IdentitySdkProfile",
)
OPERATIONS = (
    ("POST", "/identity/sessions"),
    ("GET", "/identity/sessions"),
    ("GET", "/identity/sessions/{id}"),
    ("DELETE", "/identity/sessions/{id}"),
)


def extract(projection: dict[str, Any]) -> dict[str, Any]:
    """The identity session schemas and typed operations of a LocalCloud API projection."""
    schemas = projection.get("components", {}).get("schemas", {})
    missing = [name for name in SCHEMAS if name not in schemas]
    if missing:
        raise ValueError(
            "the projection has no typed identity session contract "
            f"(missing {', '.join(missing)}); it predates LocalCloud's IdentitySessionContract"
        )
    operations: dict[str, dict[str, str | None]] = {}
    for method, path in OPERATIONS:
        operation = projection["paths"][path][method.lower()]
        request = (
            operation.get("requestBody", {})
            .get("content", {})
            .get("application/json", {})
            .get("schema", {})
            .get("$ref")
        )
        response = operation["responses"]["200"]["content"]["application/json"]["schema"].get("$ref")
        operations[f"{method} {path}"] = {"request": request, "response": response}
    return {
        "components": {"schemas": {name: schemas[name] for name in SCHEMAS}},
        "operations": operations,
    }


def revision(checkout: Path) -> dict[str, Any]:
    def git(*args: str) -> str:
        return subprocess.run(
            ["git", "-C", str(checkout), *args], capture_output=True, text=True, check=True
        ).stdout.strip()

    return {
        "revision": git("rev-parse", "HEAD"),
        "dirty": bool(git("status", "--porcelain", "--", str(PROJECTION))),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--localcloud", type=Path, default=ROOT.parent / "localcloud",
                        help="LocalCloud checkout (default: ../localcloud)")
    parser.add_argument("--check", action="store_true", help="verify instead of writing")
    args = parser.parse_args(argv)
    projection = json.loads((args.localcloud / PROJECTION).read_text(encoding="utf-8"))
    contract = extract(projection)
    if args.check:
        vendored = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
        current = {key: vendored.get(key) for key in ("components", "operations")}
        if current != contract:
            print(f"{SNAPSHOT.relative_to(ROOT)} differs from {args.localcloud / PROJECTION}; "
                  "run scripts/sync-identity-contract.py", file=sys.stderr)
            return 1
        return 0
    document = {
        "source": {"repository": "localcloud", "path": str(PROJECTION), **revision(args.localcloud)},
        **contract,
    }
    SNAPSHOT.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {SNAPSHOT.relative_to(ROOT)} from {document['source']['revision'][:12]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
