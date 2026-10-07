"""`lc env --identity` end to end against a LocalCloud image (LC-ID-026-01, LC-ID-025-02).

A real `lc start` on an isolated data volume, non-canonical host ports and a temporary HOME,
then `lc env --identity` exactly as a developer runs it, with a planted
GOOGLE_APPLICATION_CREDENTIALS key file, a planted gcloud application-default credentials file
and HTTP(S)_PROXY pointing at a recording proxy. An unchanged Google Cloud application
(tests/fixtures/identity/adc_app.py) runs with only the printed environment: its ADC ID token
reaches a receiver that verifies it against LocalCloud's keys and canonical issuer. Then
`lc env --identity --stop` ends the session and removes only its relay; the MCP setup tool's
relay profile is exercised the same way. Nothing outside the run's data volume is touched.

    LOCALCLOUD_RUN_IDENTITY_E2E=1 LOCALCLOUD_IMAGE=localcloud:<tag> \\
        uv run --frozen --extra test python -m pytest -m docker \\
        tests/integration/test_identity_session_acceptance.py
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any
from uuid import uuid4

import docker
import google.auth.transport.requests
import httpx
import pytest
from google.oauth2 import id_token

from recording_http import recording_server

pytestmark = pytest.mark.docker

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "tests" / "fixtures" / "identity" / "adc_app.py"
VOLUME_LABEL = "com.localcloud.volume-name"
SESSION_LABEL = "com.localcloud.identity-session"
JWT = re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{16,}")
CAPABILITY = re.compile(r"lcrc1\.[A-Za-z0-9_-]{8,}")
PROXY_VARIABLES = ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")


def _docker_host() -> str:
    configured = os.environ.get("DOCKER_HOST")
    if configured:
        return configured
    try:
        return subprocess.run(
            ["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unix:///var/run/docker.sock"


def _lc(environment: dict[str, str], cwd: Path, *arguments: str, check: bool = True) -> subprocess.CompletedProcess:
    completed = subprocess.run(
        [sys.executable, "-c", "import sys; from localcloud_cli.entrypoint import main; sys.exit(main())",
         *arguments],
        cwd=cwd, env=environment, capture_output=True, text=True, timeout=600,
    )
    if check and completed.returncode != 0:
        raise AssertionError(f"lc {' '.join(arguments)} exited {completed.returncode}:\n"
                             f"{completed.stdout}\n{completed.stderr}")
    return completed


def _app(environment: dict[str, str], receiver: str) -> dict[str, Any]:
    completed = subprocess.run(
        [sys.executable, str(APP)], env={**environment, "RECEIVER_URL": receiver},
        capture_output=True, text=True, timeout=120,
    )
    lines = [line for line in completed.stdout.splitlines() if line.startswith("{")]
    assert lines, f"the application printed no report:\n{completed.stdout}\n{completed.stderr}"
    return json.loads(lines[-1])


def _proxied_hosts(proxied: list[dict[str, Any]]) -> list[str]:
    """The target host of each request a recording proxy received (absolute-form or CONNECT)."""
    hosts = []
    for hit in proxied:
        target = hit["target"]
        authority = target.split("://", 1)[1].split("/", 1)[0] if "://" in target else target
        hosts.append(authority.rsplit(":", 1)[0].strip("[]").lower())
    return hosts


def _google(host: str) -> bool:
    return host.endswith((".googleapis.com", ".google.com", ".gstatic.com"))


def _local(hosts: list[str]) -> list[str]:
    return [host for host in hosts if host in {"127.0.0.1", "localhost", "::1"}]


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _verified(gateway: str, token: str, audience: str, issuer: str) -> dict[str, Any]:
    """google-auth's generic verifier with LocalCloud's certificates and an explicit issuer check."""
    claims = id_token.verify_token(
        token, google.auth.transport.requests.Request(), audience=audience,
        certs_url=f"{gateway}/oauth2/v1/certs",
    )
    assert claims["iss"] == issuer, claims
    return claims


def _mcp_setup(gateway: str, project: str) -> dict[str, Any]:
    response = httpx.post(f"{gateway}/mcp", json={
        "jsonrpc": "2.0", "id": 1, "method": "tools/call",
        "params": {"name": "localcloud_identity_setup", "arguments": {"project": project}},
    }, timeout=60, trust_env=False)
    response.raise_for_status()
    result = response.json()["result"]
    assert not result.get("isError"), result
    return result["structuredContent"]["result"]


def _wait_active(gateway: str, session_id: str) -> None:
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        state = httpx.get(f"{gateway}/identity/sessions/{session_id}", timeout=10, trust_env=False).json()
        if state.get("state") == "ACTIVE":
            return
        time.sleep(0.5)
    raise AssertionError(f"session {session_id} never became ACTIVE")


def test_lc_env_identity_serves_an_unchanged_adc_application_and_stops_cleanly(tmp_path: Path) -> None:
    if os.environ.get("LOCALCLOUD_RUN_IDENTITY_E2E") != "1":
        pytest.skip("set LOCALCLOUD_RUN_IDENTITY_E2E=1 and LOCALCLOUD_IMAGE to run the identity session acceptance")
    image = os.environ.get("LOCALCLOUD_IMAGE")
    if not image:
        pytest.fail("identity_e2e_prerequisite: LOCALCLOUD_IMAGE names the image under test")
    docker_host = _docker_host()
    client = docker.DockerClient(base_url=docker_host)
    try:
        client.images.get(image)
    except Exception as error:  # noqa: BLE001
        pytest.fail(f"identity_e2e_prerequisite: {image}: {error}")

    run = uuid4().hex[:8]
    volume = f"lc-identity-e2e-{run}"
    project = f"identity-e2e-{run}"
    home = tmp_path / "home"
    (home / ".config" / "gcloud").mkdir(parents=True)
    workdir = tmp_path / "work"
    workdir.mkdir()
    (workdir / "localcloud.yaml").write_text("\n".join([
        "version: 1",
        "context:",
        f"  project: {project}",
        "  user: local-developer",
        "host:",
        f"  data_volume: {volume}",
        "  data: ephemeral",
        f"  image: {image}",
        "  memory: 2g",
        "  docker_socket: false",
        "  transparent_network: false",
        "mcp:",
        "  write: true",
        "server:",
        "  auto_seed: false",
        "services:",
        "  enabled:",
        "    - cloudiam",
        "    - cloudresourcemanager",
        "",
    ]), encoding="utf-8")
    base = {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "HOME": str(home),
        "LOCALCLOUD_HOME": str(home / ".local" / "share" / "localcloud"),
        "DOCKER_HOST": docker_host,
        "LOCALCLOUD_TELEMETRY": "false",
    }
    if os.environ.get("PYTHONPATH"):
        # `lc` is whatever this interpreter imports, also in the subprocesses.
        base["PYTHONPATH"] = os.environ["PYTHONPATH"]
    evidence: dict[str, Any] = {"run": run, "image": image}

    with recording_server() as (proxy_port, proxied), \
            recording_server(lambda method, target, headers: (200, {"ok": True})) as (receiver_port, received):
        proxy = f"http://127.0.0.1:{proxy_port}"
        developer = {**base, **{name: proxy for name in PROXY_VARIABLES}}
        receiver = f"http://127.0.0.1:{receiver_port}/protected"
        planted_adc = home / ".config" / "gcloud" / "application_default_credentials.json"
        # gcloud's user ADC file. Its refresh goes to oauth2.googleapis.com, which here means the
        # recording proxy (HTTPS_PROXY), which refuses it: nothing reaches Google.
        planted_adc.write_text(json.dumps({
            "type": "authorized_user", "client_id": "planted.apps.example", "client_secret": "planted",
            "refresh_token": "planted-refresh-token",
        }), encoding="utf-8")
        try:
            port_range = os.environ.get("LOCALCLOUD_IDENTITY_E2E_PORT_RANGE", "5900-5949")
            started = _lc(developer, workdir, "start", "--no-pull", "--local-only", "--port-range", port_range,
                          "--verbose", check=False)
            if started.returncode != 0:
                # `lc start` waits 60 s for health; on a loaded Docker host the runtime may need longer.
                assert '"health_timeout"' in started.stdout + started.stderr, started.stdout + started.stderr
                evidence["start"] = "health_timeout after 60 s; waited for the running container"
            status = json.loads(_lc(developer, workdir, "status", "--verbose").stdout)
            gateway = status["container"]["url"].rstrip("/")
            deadline = time.monotonic() + float(os.environ.get("LOCALCLOUD_IDENTITY_E2E_START_TIMEOUT", "300"))
            while True:
                try:
                    health = httpx.get(f"{gateway}/health", timeout=10, trust_env=False).json()
                    if health.get("status") == "healthy":
                        break
                except httpx.HTTPError:
                    pass
                assert time.monotonic() < deadline, f"LocalCloud never became healthy: {status}"
                time.sleep(2)
            assert not gateway.endswith(":5380"), f"the run uses remapped host ports: {gateway}"
            assert proxied == [], "lc start and status never use HTTP(S)_PROXY"
            issuer = httpx.get(f"{gateway}/identity/status", timeout=30, trust_env=False).json()["issuer"]
            evidence.update(gateway=gateway, issuer=issuer)

            # A real service-account key file shadows the session while GOOGLE_APPLICATION_CREDENTIALS is set.
            key_account = httpx.post(f"{gateway}/v1/projects/{project}/serviceAccounts",
                                     json={"accountId": "planted-key"}, timeout=30, trust_env=False).json()["email"]
            key = httpx.post(f"{gateway}/v1/projects/{project}/serviceAccounts/{key_account}/keys", json={},
                             timeout=30, trust_env=False).json()
            import base64

            key_file = home / "keys" / "service-account.json"
            key_file.parent.mkdir()
            key_document = json.loads(base64.b64decode(key["privateKeyData"]))
            key_document["token_uri"] = f"{gateway}/token"
            key_file.write_text(json.dumps(key_document), encoding="utf-8")
            digests = {path: _sha256(path) for path in (key_file, planted_adc)}
            shell = {**developer, "GOOGLE_APPLICATION_CREDENTIALS": str(key_file)}

            # One setup action, as printed for eval or a .env file.
            printed = _lc(shell, workdir, "env", "--identity", "--format", "json")
            environment = json.loads(printed.stdout)
            evidence["environment"] = environment
            assert re.fullmatch(r"127\.0\.0\.1:\d+", environment["GCE_METADATA_HOST"]), environment
            assert environment["GCE_METADATA_IP"] == environment["GCE_METADATA_HOST"]
            assert environment["GOOGLE_CLOUD_PROJECT"] == project
            assert environment["NO_PROXY"] == environment["no_proxy"] == "127.0.0.1,localhost"
            assert "GOOGLE_APPLICATION_CREDENTIALS" in printed.stderr and str(planted_adc) in printed.stderr, (
                printed.stderr)
            assert not CAPABILITY.search(printed.stdout + printed.stderr)
            assert {path: _sha256(path) for path in (key_file, planted_adc)} == digests
            assert proxied == [], "lc env --identity never uses HTTP(S)_PROXY"
            relays = client.containers.list(filters={"label": [SESSION_LABEL, f"{VOLUME_LABEL}={volume}"]})
            assert len(relays) == 1
            session_id = relays[0].labels[SESSION_LABEL]
            port = int(environment["GCE_METADATA_HOST"].rsplit(":", 1)[1])
            relays[0].reload()
            assert relays[0].attrs["HostConfig"]["PortBindings"]["8081/tcp"][0]["HostIp"] == "127.0.0.1"

            # 1. The developer's shell as is: the planted key file wins, as Google's precedence says.
            shadowed = _app({**shell, **environment}, receiver)
            assert shadowed["credentials"] == "google.oauth2.service_account.Credentials", shadowed
            assert shadowed.get("receiver_status") == 200, shadowed
            claims = _verified(gateway, received[-1]["headers"]["authorization"][7:], receiver, issuer)
            assert claims["email"] == key_account, "GOOGLE_APPLICATION_CREDENTIALS took precedence"

            # 2. GOOGLE_APPLICATION_CREDENTIALS unset: gcloud's ADC file is next, and is used; its
            #    refresh heads for Google through the developer's proxy, which refuses it.
            adc_shadowed = _app({**developer, **environment}, receiver)
            assert adc_shadowed["credentials"] == "google.oauth2.credentials.Credentials", adc_shadowed
            assert "error" in adc_shadowed, adc_shadowed
            # Only Google-bound requests (the token refresh; an installed gcloud may also check for
            # updates while google-auth asks it for the project), all refused by the proxy.
            assert "oauth2.googleapis.com" in _proxied_hosts(proxied), proxied
            assert all(_google(host) for host in _proxied_hosts(proxied)), proxied
            google_bound = len(proxied)

            # 3. Following the printed isolation: the unchanged application gets the session's identity.
            isolated = tmp_path / "empty-gcloud"
            isolated.mkdir()
            app_environment = {**developer, **environment, "CLOUDSDK_CONFIG": str(isolated)}
            served = _app(app_environment, receiver)
            evidence["application"] = served
            assert served["credentials"] == "google.auth.compute_engine.credentials.Credentials", served
            assert served["project"] == project and served.get("receiver_status") == 200, served
            session_token = received[-1]["headers"]["authorization"][7:]
            claims = _verified(gateway, session_token, receiver, issuer)
            assert claims["email"] == f"default@{project}.iam.gserviceaccount.com"
            assert claims["localcloud"]["src"] == "session" and claims["localcloud"]["bid"] == session_id
            evidence["claims"] = {key: claims[key] for key in ("iss", "aud", "email", "sub")}
            assert len(proxied) == google_bound, f"the session run used the proxy: {proxied}"
            assert {path: _sha256(path) for path in (key_file, planted_adc)} == digests

            # A second account's session; stopping it leaves the default session serving.
            runner_account = httpx.post(f"{gateway}/v1/projects/{project}/serviceAccounts",
                                        json={"accountId": "e2e-runner"}, timeout=30, trust_env=False).json()["email"]
            runner = json.loads(_lc(developer, workdir, "env", "--identity", "--account", runner_account,
                                    "--format", "json").stdout)
            assert runner["GCE_METADATA_HOST"] != environment["GCE_METADATA_HOST"]
            stopped_runner = _lc(developer, workdir, "env", "--identity", "--stop", "--account", runner_account)
            assert "unset GCE_METADATA_HOST GCE_METADATA_IP" in stopped_runner.stdout
            assert len(client.containers.list(filters={"label": [SESSION_LABEL, f"{VOLUME_LABEL}={volume}"]})) == 1
            assert _app(app_environment, receiver).get("receiver_status") == 200, "the default session still serves"

            # --stop: the relay goes, the session ends and its tokens stop verifying; nothing else changes.
            localcloud = [client.containers.get(status["container"]["name"])]
            # Another LocalCloud volume's relay, which this volume's --stop must leave alone.
            decoy = client.containers.run(
                image, entrypoint=["sleep"], command=["900"], detach=True, name=f"lc-identity-e2e-decoy-{run}",
                labels={SESSION_LABEL: "wib-decoy", VOLUME_LABEL: f"{volume}-other",
                        "com.localcloud.test-run": run}, healthcheck={"test": ["NONE"]},
            )
            stopped = _lc(developer, workdir, "env", "--identity", "--stop")
            assert stopped.stdout.rstrip().endswith("unset GCE_METADATA_HOST GCE_METADATA_IP"), stopped.stdout
            assert client.containers.list(all=True, filters={"label": [SESSION_LABEL, f"{VOLUME_LABEL}={volume}"]}) == []
            for container in [*localcloud, decoy]:
                container.reload()
                assert container.status == "running", f"{container.name} was touched"
            ended = httpx.get(f"{gateway}/identity/sessions/{session_id}", timeout=30, trust_env=False).json()
            assert ended["state"] == "REVOKED" and ended["revocationReason"] == "deleted", ended
            verdict = httpx.post(f"{gateway}/identity/verify", json={"token": session_token, "audience": receiver},
                                 timeout=30, trust_env=False).json()
            assert verdict == {**verdict, "valid": False, "reason": "TOKEN_REVOKED"}, verdict
            with pytest.raises(httpx.HTTPError):
                httpx.get(f"http://127.0.0.1:{port}/computeMetadata/v1/project/project-id",
                          headers={"Metadata-Flavor": "Google"}, timeout=5, trust_env=False).raise_for_status()

            # The MCP setup tool's relay profile, started by hand as its next steps say, then /env.
            setup = _mcp_setup(gateway, project)
            relay_profile, profile = setup["relay"], setup["profile"]
            assert set(setup) >= {"capability", "relay", "profile", "next_steps"}
            inspect = localcloud[0].attrs
            network = next(iter(inspect["NetworkSettings"]["Networks"]))
            relay_environment = {
                **relay_profile["environment"],
                "LOCALCLOUD_RELAY_GATEWAY": relay_profile["environment"].get(
                    "LOCALCLOUD_RELAY_GATEWAY", f"http://{localcloud[0].name}:5380"),
                relay_profile["capabilityEnv"]: setup["capability"],
            }
            mcp_relay = client.containers.run(
                relay_profile.get("image") or inspect["Image"], command=relay_profile["command"][1:],
                entrypoint=[relay_profile["command"][0]], detach=True, name=f"lc-identity-e2e-mcp-{run}",
                environment=relay_environment, network=relay_profile.get("network") or network,
                ports={f"{relay_profile['metadataPort']}/tcp": ("127.0.0.1", None)},
                labels={VOLUME_LABEL: volume, "com.localcloud.test-run": run}, healthcheck={"test": ["NONE"]},
            )
            _wait_active(gateway, setup["id"])
            mcp_relay.reload()
            mcp_port = int(mcp_relay.attrs["NetworkSettings"]["Ports"][f"{relay_profile['metadataPort']}/tcp"][0]
                           ["HostPort"])
            rendered = httpx.get(f"{gateway}/env", params={"identitySession": setup["id"], "metadataPort": mcp_port,
                                                           "format": "json"}, timeout=30, trust_env=False).json()
            assert rendered == {**profile["environment"], **{name: f"127.0.0.1:{mcp_port}"
                                                              for name in profile["endpointVariables"]}}
            mcp_environment = {**developer, **rendered, "NO_PROXY": ",".join(profile["noProxy"]),
                               "no_proxy": ",".join(profile["noProxy"]), "CLOUDSDK_CONFIG": str(isolated)}
            assert _app(mcp_environment, receiver).get("receiver_status") == 200
            mcp_claims = _verified(gateway, received[-1]["headers"]["authorization"][7:], receiver, issuer)
            assert mcp_claims["localcloud"]["bid"] == setup["id"]
            deleted = httpx.delete(f"{gateway}/identity/sessions/{setup['id']}", timeout=30, trust_env=False).json()
            assert deleted["state"] == "REVOKED"
            mcp_relay.remove(force=True, v=True)
            hosts = _proxied_hosts(proxied)
            evidence["proxy"] = {"requests": len(hosts), "local_targets": _local(hosts), "hosts": sorted(set(hosts))}
            assert _local(hosts) == [], f"a relay or LocalCloud request went through the proxy: {proxied}"
            assert len(hosts) == google_bound, "only the run with the shadowing user credential used the proxy"

            # No capability or token in session listings, the request log or the relay logs.
            listing = httpx.get(f"{gateway}/identity/sessions", params={"includeRevoked": "true"},
                                timeout=30, trust_env=False).text
            log = httpx.get(f"{gateway}/requests", params={"limit": 1000}, timeout=30, trust_env=False).text
            for text in (listing, log):
                assert not CAPABILITY.search(text) and not JWT.search(text)
            evidence["result"] = "passed"
        finally:
            evidence_path = os.environ.get("LOCALCLOUD_IDENTITY_E2E_EVIDENCE")
            if evidence_path:
                Path(evidence_path).write_text(json.dumps(evidence, indent=2, default=str) + "\n",
                                               encoding="utf-8")
            _lc(developer, workdir, "stop", check=False)
            for label in (f"{VOLUME_LABEL}={volume}", f"com.localcloud.test-run={run}"):
                for container in client.containers.list(all=True, filters={"label": label}):
                    container.remove(force=True, v=True)
            for leftover in client.volumes.list(filters={"name": volume}):
                if leftover.name == volume:
                    leftover.remove(force=True)
            client.close()

    remaining = docker.DockerClient(base_url=docker_host)
    try:
        assert remaining.containers.list(all=True, filters={"label": f"{VOLUME_LABEL}={volume}"}) == []
        assert remaining.containers.list(all=True, filters={"label": f"com.localcloud.test-run={run}"}) == []
        assert [v.name for v in remaining.volumes.list(filters={"name": volume}) if v.name == volume] == []
    finally:
        remaining.close()
