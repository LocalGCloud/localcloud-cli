from __future__ import annotations

import json

import pytest

import localcloud_cli.endpoints as endpoints_module
from localcloud_cli.endpoints import (
    environment_config,
    rewrite_endpoints,
    transform_endpoint_payload,
    validate_local_endpoints,
)
from localcloud_cli.errors import HostError


def test_rewrite_endpoints_is_recursive_and_preserves_unmapped_ports() -> None:
    value = {
        "url": "http://localhost:5380/path",
        "nested": ["127.0.0.1:5382", "127.0.0.1:24099"],
    }

    rewritten = rewrite_endpoints(value, {"5380": 49080, "5382": 49081})

    assert rewritten == {
        "url": "http://127.0.0.1:49080/path",
        "nested": ["127.0.0.1:49081", "127.0.0.1:24099"],
    }


def test_transform_endpoint_payload_rewrites_generated_endpoint_records() -> None:
    value = {
        "content": [
            {
                "type": "text",
                "text": (
                    '{"endpoint":"http://127.0.0.1:5382",'
                    '"port":5382,"env_var":"STORAGE_EMULATOR_HOST"}'
                ),
            }
        ]
    }

    transformed = transform_endpoint_payload(value, {"5382": 49081})

    text = transformed["content"][0]["text"]
    assert "127.0.0.1:49081" in text
    assert '"port":49081' in text


def test_transform_endpoint_payload_rejects_public_google_endpoint() -> None:
    value = {
        "endpoint": "https://storage.googleapis.com",
        "env_var": "STORAGE_EMULATOR_HOST",
    }

    with pytest.raises(HostError) as caught:
        transform_endpoint_payload(value, {"5382": 49081})

    assert caught.value.code == "real_google_endpoint"


def test_validate_local_endpoints_rejects_public_google_and_non_loopback() -> None:
    for value in (
        "https://storage.googleapis.com/bucket",
        {"url": "http://192.0.2.10:8080"},
    ):
        with pytest.raises(HostError):
            validate_local_endpoints(value)


def test_validate_local_endpoints_allows_descriptive_urls() -> None:
    # Descriptive non-endpoint fields should not trigger nonlocal_endpoint
    value = {
        "description": "See docs at https://example.com/guide",
        "url": "http://127.0.0.1:5382",
    }
    validate_local_endpoints(value)



def test_environment_config_uses_running_environment_without_daemon_state(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []

    class FakeJava:
        def __init__(self, url: str, project: str, user: str):
            assert url == "http://127.0.0.1:49080"
            assert project == "agent-project-1"
            assert user == "integration-agent"

        def environment(self, output_format: str):
            calls.append(output_format)
            return {
                "LOCALCLOUD_PROJECT": "agent-project-1",
                "LOCALCLOUD_USER": "integration-agent",
                "LOCALCLOUD_PRINCIPAL": "integration-agent@localcloud.invalid",
                "STORAGE_EMULATOR_HOST": "http://127.0.0.1:5382",
            }

    monkeypatch.setattr(endpoints_module, "JavaMcpClient", FakeJava)
    environment = {
        "url": "http://127.0.0.1:49080",
        "endpoint_map": {"5382": 49081},
    }

    result = environment_config(
        environment,
        "agent-project-1",
        "integration-agent",
        "json",
    )

    assert result["STORAGE_EMULATOR_HOST"] == "http://127.0.0.1:49081"
    assert result["LOCALCLOUD_USER"] == "integration-agent"
    assert result["LOCALCLOUD_PRINCIPAL"] == "integration-agent@localcloud.invalid"
    assert calls == ["json"]


def test_off_canonical_runtime_rejects_endpoints_on_ports_it_does_not_publish() -> None:
    # A runtime off the canonical ports publishes only its services' ports.
    endpoint_map = {"5380": 5508, "5382": 5509}

    endpoints_module._validate_no_unpublished_canonical_endpoints(
        {"STORAGE_EMULATOR_HOST": "http://127.0.0.1:5509"}, endpoint_map
    )
    with pytest.raises(HostError) as caught:
        endpoints_module._validate_no_unpublished_canonical_endpoints(
            {"FIRESTORE_EMULATOR_HOST": "127.0.0.1:5384"}, endpoint_map
        )
    assert caught.value.details["canonical_port"] == "5384"

    # On the canonical ports nothing is remapped, so nothing is unpublished.
    endpoints_module._validate_no_unpublished_canonical_endpoints(
        {"FIRESTORE_EMULATOR_HOST": "127.0.0.1:5384"}, {"5380": 5380}
    )


def test_overlapping_port_map_accepts_rewritten_endpoints() -> None:
    # A port range inside the canonical block puts one service's host port on
    # another service's canonical port.
    endpoint_map = {"5380": 5381, "5382": 5383, "5383": 5384}
    value = {
        "content": [
            {
                "type": "text",
                "text": (
                    '{"services":['
                    '{"endpoint":"http://127.0.0.1:5382","port":5382,'
                    '"env_var":"STORAGE_EMULATOR_HOST"},'
                    '{"endpoint":"127.0.0.1:5383","port":5383,'
                    '"env_var":"PUBSUB_EMULATOR_HOST"}]}'
                ),
            }
        ]
    }

    transformed = transform_endpoint_payload(value, endpoint_map)

    services = json.loads(transformed["content"][0]["text"])["services"]
    assert services == [
        {
            "endpoint": "http://127.0.0.1:5383",
            "port": 5383,
            "env_var": "STORAGE_EMULATOR_HOST",
        },
        {"endpoint": "127.0.0.1:5384", "port": 5384, "env_var": "PUBSUB_EMULATOR_HOST"},
    ]


def test_overlapping_port_map_still_rejects_unrewritten_endpoints() -> None:
    endpoint_map = {"5380": 5381, "5382": 5383, "5383": 5384}

    for stale in ("http://LOCALHOST:5382", '{"port": 5382}'):
        with pytest.raises(HostError) as caught:
            endpoints_module._validate_no_stale_canonical_endpoints(stale, endpoint_map)
        assert caught.value.code == "stale_endpoint"
        assert caught.value.details == {
            "canonical_port": "5382",
            "expected_host_port": 5383,
        }


# --- lc env --identity -----------------------------------------------------------------------------

_IDENTITY_RESULT = {
    "status": "started",
    "project": "agent-project-1",
    "session": {
        "id": "wib-abc",
        "service_account": "default@agent-project-1.iam.gserviceaccount.com",
        "expires_at": "2026-10-07T12:00:00Z",
    },
    "environment": {
        "GCE_METADATA_HOST": "127.0.0.1:49123",
        "GCE_METADATA_IP": "127.0.0.1:49123",
        "GOOGLE_CLOUD_PROJECT": "agent-project-1",
    },
    "endpoint_variables": ["GCE_METADATA_HOST", "GCE_METADATA_IP"],
    "warnings": ["GOOGLE_APPLICATION_CREDENTIALS=/keys/sa.json takes precedence"],
}


def test_identity_environment_renders_every_env_format() -> None:
    from localcloud_cli.endpoints import render_identity_environment

    shell = render_identity_environment(_IDENTITY_RESULT, "shell")
    assert 'export GCE_METADATA_HOST="127.0.0.1:49123"\n' in shell
    assert 'export GCE_METADATA_IP="127.0.0.1:49123"\n' in shell
    assert 'export GOOGLE_CLOUD_PROJECT="agent-project-1"\n' in shell
    assert "# WARNING: GOOGLE_APPLICATION_CREDENTIALS=/keys/sa.json" in shell
    assert "lc env --identity --stop" in shell
    assert all(line.startswith(("#", "export ")) for line in shell.splitlines()), shell
    assert render_identity_environment(_IDENTITY_RESULT, "terraform") == shell

    assert json.loads(render_identity_environment(_IDENTITY_RESULT, "json")) == _IDENTITY_RESULT["environment"]

    compose = render_identity_environment(_IDENTITY_RESULT, "docker-compose")
    assert '  GCE_METADATA_HOST: "127.0.0.1:49123"' in compose
    assert compose.splitlines()[0] == "# docker-compose environment variables"


def test_identity_environment_refuses_a_non_loopback_relay_address() -> None:
    from localcloud_cli.endpoints import render_identity_environment

    remote = {**_IDENTITY_RESULT, "environment": {**_IDENTITY_RESULT["environment"],
                                                  "GCE_METADATA_HOST": "192.0.2.10:8081"}}
    with pytest.raises(HostError) as caught:
        render_identity_environment(remote, "shell")
    assert caught.value.code == "nonlocal_endpoint"


def test_identity_stop_unsets_the_session_variables() -> None:
    from localcloud_cli.endpoints import render_identity_stop

    result = {
        "status": "stopped",
        "relays_removed": ["lc-identity-1"],
        "sessions_ended": ["wib-abc"],
        "unset_variables": ["GCE_METADATA_HOST", "GCE_METADATA_IP"],
        "failures": [{"session": "wib-def", "cause": "LocalCloud is not running"}],
    }
    shell = render_identity_stop(result, "shell")
    assert shell.rstrip().endswith("unset GCE_METADATA_HOST GCE_METADATA_IP")
    nothing = render_identity_stop({**result, "unset_variables": [], "relays_removed": []}, "shell")
    assert "unset" not in nothing
    hostile = render_identity_stop({**result, "unset_variables": ["A;rm -rf /", "OK_NAME"]}, "shell")
    assert hostile.rstrip().endswith("unset OK_NAME")
    assert "wib-abc" in shell and "# WARNING: wib-def: LocalCloud is not running" in shell
    assert json.loads(render_identity_stop(result, "json")) == result
    assert "unset" not in render_identity_stop(result, "docker-compose")


def test_ambient_adc_is_reported_and_left_in_place(tmp_path) -> None:
    from localcloud_cli.endpoints import ambient_adc_warnings

    key = tmp_path / "sa.json"
    key.write_text("{}", encoding="utf-8")
    well_known = tmp_path / "home" / ".config" / "gcloud" / "application_default_credentials.json"
    well_known.parent.mkdir(parents=True)
    well_known.write_text("{}", encoding="utf-8")

    warnings = ambient_adc_warnings({"GOOGLE_APPLICATION_CREDENTIALS": str(key)}, home=tmp_path / "home")

    assert len(warnings) == 2
    assert f"GOOGLE_APPLICATION_CREDENTIALS={key}" in warnings[0]
    assert "unset GOOGLE_APPLICATION_CREDENTIALS" in warnings[0]
    assert str(well_known) in warnings[1]
    assert key.read_text(encoding="utf-8") == "{}" and well_known.is_file(), "nothing is deleted"
    assert ambient_adc_warnings({}, home=tmp_path / "empty") == []
    custom = tmp_path / "gcloud-config"
    custom.mkdir()
    (custom / "application_default_credentials.json").write_text("{}", encoding="utf-8")
    assert str(custom) in ambient_adc_warnings({"CLOUDSDK_CONFIG": str(custom)}, home=tmp_path / "empty")[0]


def test_identity_environment_follows_the_typed_profile() -> None:
    import identity_fixtures
    from localcloud_cli.endpoints import identity_environment

    profile = identity_fixtures.created_session("agent-project-1")["profile"]
    environment, variables = identity_environment(profile, 49123, {})
    assert environment == {
        "GCE_METADATA_HOST": "127.0.0.1:49123",
        "GCE_METADATA_IP": "127.0.0.1:49123",
        "GOOGLE_CLOUD_PROJECT": "agent-project-1",
    }
    assert variables == ["GCE_METADATA_HOST", "GCE_METADATA_IP"]

    proxied, _ = identity_environment(profile, 49123, {"http_proxy": "http://proxy:3128", "NO_PROXY": "*.corp"})
    assert proxied["NO_PROXY"] == proxied["no_proxy"] == "*.corp,127.0.0.1,localhost"
    everything, _ = identity_environment(profile, 49123, {
        "HTTP_PROXY": "http://proxy:3128", "NO_PROXY": "*", "no_proxy": "corp.example",
    })
    assert everything["NO_PROXY"] == everything["no_proxy"] == "*"

    for broken in (None, {**profile, "kind": "x"}, {**profile, "endpointVariables": ["MISSING"]},
                   {**profile, "metadataPort": "8081"}):
        with pytest.raises(HostError):
            identity_environment(broken, 49123, {})


def test_identity_environment_quotes_values_literally() -> None:
    from localcloud_cli.endpoints import render_identity_environment

    result = {**_IDENTITY_RESULT, "environment": {**_IDENTITY_RESULT["environment"],
                                                  "NO_PROXY": 'corp,$(touch x),"q"'}}
    shell = render_identity_environment(result, "shell")
    assert 'export NO_PROXY="corp,\\$(touch x),\\"q\\""' in shell
    compose = render_identity_environment(result, "docker-compose")
    assert '  NO_PROXY: "corp,$(touch x),\\"q\\""' in compose


def test_identity_shell_comments_keep_multiline_metadata_inert() -> None:
    import subprocess
    from localcloud_cli.endpoints import render_identity_environment, render_identity_stop

    injected = "first line\nprintf COMMENT_ESCAPED\\n\n#"
    result = {
        **_IDENTITY_RESULT,
        "session": {**_IDENTITY_RESULT["session"], "service_account": injected},
        "warnings": [injected],
    }
    stopped = {
        "sessions_ended": [injected],
        "failures": [{"session": "wib-abc", "cause": injected}],
        "unset_variables": ["GCE_METADATA_HOST", "HOSTILE\n"],
    }
    for rendered in (
        render_identity_environment(result, "shell"),
        render_identity_stop(stopped, "shell"),
    ):
        assert all(line.startswith(("#", "export ", "unset ")) for line in rendered.splitlines())
        executed = subprocess.run(["/bin/sh"], input=rendered, capture_output=True, text=True)
        assert executed.returncode == 0 and executed.stdout == "", executed
    compose = render_identity_environment(result, "docker-compose")
    assert all(line.startswith(("#", "environment:", "  ")) for line in compose.splitlines())


@pytest.mark.parametrize("name", ["BAD;printf COMMENT_ESCAPED", "BAD\n", "1BAD"])
def test_identity_environment_refuses_invalid_export_names(name: str) -> None:
    from localcloud_cli.endpoints import render_identity_environment

    result = {**_IDENTITY_RESULT, "environment": {**_IDENTITY_RESULT["environment"], name: "value"}}
    with pytest.raises(HostError) as caught:
        render_identity_environment(result, "shell")
    assert caught.value.code == "identity_profile_invalid"
