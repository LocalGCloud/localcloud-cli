"""The identity session fixtures against LocalCloud's published contract (LC-ID-029-01).

LocalCloud publishes the `/identity/sessions` request and response schemas, with the typed
relay and SDK profiles, in its API contract projection. The CLI vendors them in
tests/fixtures/identity/localcloud-contract.json with the LocalCloud revision they came from
(scripts/sync-identity-contract.py). Every fixture validates against that contract; with
LOCALCLOUD_SOURCE (or a sibling ../localcloud checkout that has the contract) the vendored copy
must also equal the checkout's projection.
"""

from __future__ import annotations

import copy
import importlib.util
import json
import os
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator

from identity_fixtures import FIXTURES, load

ROOT = Path(__file__).resolve().parents[1]


def _sync_module() -> Any:
    spec = importlib.util.spec_from_file_location(
        "sync_identity_contract", ROOT / "scripts" / "sync-identity-contract.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _validator(contract: dict[str, Any], ref: str) -> Draft202012Validator:
    return Draft202012Validator({"$ref": ref, "components": contract["components"]})


def _errors(contract: dict[str, Any], ref: str, value: Any) -> list[str]:
    return sorted(error.message for error in _validator(contract, ref).iter_errors(value))


def test_every_identity_fixture_matches_the_vendored_localcloud_contract() -> None:
    manifest = load("fixtures.json")
    contract = load(manifest["contract"])
    assert contract["source"]["repository"] == "localcloud"
    assert len(contract["source"]["revision"]) == 40, "the vendored contract names its LocalCloud revision"
    assert set(manifest["fixtures"]) == {path.name for path in FIXTURES.glob("session-*.json")}, (
        "every session fixture is declared, so none escapes validation"
    )
    for name, entry in manifest["fixtures"].items():
        ref = contract["operations"][entry["operation"]][entry["body"]]
        assert ref, f"{entry['operation']} has no published {entry['body']} schema"
        assert _errors(contract, ref, load(name)) == [], name


def test_the_contract_rejects_sessions_the_cli_could_not_apply() -> None:
    contract = load("localcloud-contract.json")
    ref = contract["operations"]["POST /identity/sessions"]["response"]
    created = load("session-created.json")
    broken = []
    for mutate in (
        lambda session: session.pop("profile"),
        lambda session: session.pop("capability"),
        lambda session: session["profile"].pop("endpointVariables"),
        lambda session: session["profile"]["environment"].pop("GCE_METADATA_HOST"),
        lambda session: session["relay"].update(metadataPort=0),
        lambda session: session.update(unpublished=True),
    ):
        candidate = copy.deepcopy(created)
        mutate(candidate)
        broken.append(_errors(contract, ref, candidate))
    assert all(broken), broken
    listed = contract["operations"]["GET /identity/sessions"]["response"]
    leaked = {"sessions": [{**load("session-active.json"), "capability": created["capability"]}]}
    assert _errors(contract, listed, leaked), "a listed session can never carry a capability"


def test_the_vendored_contract_is_the_matched_localcloud_projection() -> None:
    source = os.environ.get("LOCALCLOUD_SOURCE")
    checkout = Path(source) if source else ROOT.parent / "localcloud"
    projection = checkout / "specs" / "api" / "openapi" / "localcloud.json"
    if not projection.is_file():
        if source:
            pytest.fail(f"LOCALCLOUD_SOURCE={source} has no {projection}")
        pytest.skip("no LocalCloud checkout; set LOCALCLOUD_SOURCE to the matched revision")
    sync = _sync_module()
    try:
        current = sync.extract(json.loads(projection.read_text(encoding="utf-8")))
    except ValueError as error:
        if source:
            pytest.fail(f"LOCALCLOUD_SOURCE={source}: {error}")
        pytest.skip(f"{checkout} is not the matched LocalCloud revision: {error}")
    vendored = load("localcloud-contract.json")
    assert {key: vendored[key] for key in ("components", "operations")} == current, (
        "run scripts/sync-identity-contract.py against the matched LocalCloud revision"
    )


@pytest.mark.parametrize("account", [None, "runner@local-gcp-project.iam.gserviceaccount.com", "runner"])
def test_the_session_request_the_cli_sends_matches_the_contract(
    monkeypatch: pytest.MonkeyPatch, account: str | None
) -> None:
    import localcloud_cli.java_client as java_client_module
    from localcloud_cli.java_client import JavaMcpClient

    sent: list[Any] = []

    class Response:
        status_code = 200

        def json(self) -> Any:
            return load("session-created.json")

    def request(method: str, url: str, **kwargs: Any) -> Response:
        sent.append(kwargs.get("json"))
        return Response()

    monkeypatch.setattr(java_client_module.httpx, "request", request)
    JavaMcpClient("http://127.0.0.1:5380", "local-gcp-project", "local-developer").create_identity_session(account)

    contract = load("localcloud-contract.json")
    ref = contract["operations"]["POST /identity/sessions"]["request"]
    assert _errors(contract, ref, sent[0]) == [], sent[0]
