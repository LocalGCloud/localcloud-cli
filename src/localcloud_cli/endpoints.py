from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import urlsplit

from .errors import HostError
from .java_client import JavaMcpClient


HOST_PORT = re.compile(r"(?P<host>localhost|127\.0\.0\.1|\[::1\])(?P<separator>:)(?P<port>\d{1,5})")
# Every port a runtime publishes at its own number when it holds the canonical ports.
_CANONICAL_PORTS = range(5380, 5407)
REAL_GOOGLE = re.compile(r"(?:^|[/:.])(?:googleapis\.com|gcr\.io|pkg\.dev)(?:$|[/.:])", re.IGNORECASE)
ENDPOINT_FIELDS = frozenset(
    {
        "endpoint",
        "env_value",
        "endpoint_env",
        "token_uri",
        "auth_uri",
    }
)
ENDPOINT_RECORD_MARKERS = frozenset(
    {
        "env_var",
        "gcloud_endpoint_env_var",
        "terraform_endpoint_env_var",
    }
)
ENDPOINT_CONFIG_ASSIGNMENT = re.compile(
    r"(?im)^\s*(?:export\s+)?[\"']?"
    r"(?P<key>[A-Z][A-Z0-9_]*)[\"']?\s*[:=]"
)


def environment_config(
    environment: dict[str, Any],
    project: str,
    user: str,
    output_format: str = "shell",
) -> Any:
    java = JavaMcpClient(environment["url"], project=project, user=user)
    result = java.environment(output_format)
    endpoint_map = environment.get("endpoint_map") or {}
    rewritten = rewrite_endpoints(result, endpoint_map)
    _validate_no_unpublished_canonical_endpoints(rewritten, endpoint_map)
    if output_format == "json" and isinstance(rewritten, str):
        try:
            rewritten = json.loads(rewritten)
        except json.JSONDecodeError as error:
            raise HostError(
                "invalid_environment",
                "Java MCP returned invalid JSON environment configuration",
                {"cause": str(error)},
            ) from error
    validate_local_endpoints(rewritten)
    return rewritten


IDENTITY_ENVIRONMENT_KEYS = ("GCE_METADATA_HOST", "GCE_METADATA_IP", "GOOGLE_CLOUD_PROJECT")


def ambient_adc_warnings(
    environ: Mapping[str, str] | None = None,
    home: Path | None = None,
) -> list[str]:
    """Credentials Application Default Credentials prefers over the metadata server.

    Google's client libraries read GOOGLE_APPLICATION_CREDENTIALS first, then gcloud's
    application-default credentials file, and only then the metadata server an identity
    session provides. They are reported, never changed or deleted.
    """
    values = os.environ if environ is None else environ
    warnings: list[str] = []
    configured = (values.get("GOOGLE_APPLICATION_CREDENTIALS") or "").strip()
    if configured:
        warnings.append(
            f"GOOGLE_APPLICATION_CREDENTIALS={configured} takes precedence over the identity "
            "session's metadata server: Google client libraries in this shell use it until "
            "you unset GOOGLE_APPLICATION_CREDENTIALS. LocalCloud left it unchanged."
        )
    config_dir = (values.get("CLOUDSDK_CONFIG") or "").strip()
    if config_dir:
        well_known = Path(config_dir) / "application_default_credentials.json"
    elif os.name == "nt" and values.get("APPDATA"):
        well_known = Path(values["APPDATA"]) / "gcloud" / "application_default_credentials.json"
    else:
        base = home if home is not None else Path.home()
        well_known = base / ".config" / "gcloud" / "application_default_credentials.json"
    if well_known.is_file():
        warnings.append(
            f"gcloud application-default credentials at {well_known} take precedence over the "
            "identity session's metadata server for libraries that read that file. LocalCloud "
            "left them unchanged."
        )
    return warnings


def render_identity_environment(result: Mapping[str, Any], output_format: str) -> str:
    """`lc env --identity` output in the same formats as `lc env`."""
    environment = {
        key: str(result["environment"][key]) for key in IDENTITY_ENVIRONMENT_KEYS
    }
    validate_local_endpoints(
        {key: f"http://{value}" for key, value in environment.items() if key != "GOOGLE_CLOUD_PROJECT"}
    )
    if output_format == "json":
        return json.dumps(environment, indent=2)
    session = result.get("session") or {}
    summary = (
        f"LocalCloud identity session {session.get('id')} for {session.get('service_account')} "
        f"(project {result.get('project')}) expires {session.get('expires_at')}; "
        "end it with: lc env --identity --stop"
    )
    warnings = [str(warning) for warning in result.get("warnings") or []]
    if output_format == "docker-compose":
        lines = [
            "# docker-compose environment variables",
            f"# {summary}",
            "# 127.0.0.1 is the Docker host's loopback; only host-networked services reach it.",
            *(f"# WARNING: {warning}" for warning in warnings),
            "environment:",
            *(f'  {key}: "{value}"' for key, value in environment.items()),
        ]
        return "\n".join(lines) + "\n"
    lines = [
        f"# {summary}",
        *(f"# WARNING: {warning}" for warning in warnings),
        *(f'export {key}="{value}"' for key, value in environment.items()),
    ]
    return "\n".join(lines) + "\n"


def render_identity_stop(result: Mapping[str, Any], output_format: str) -> str:
    """`lc env --identity --stop` output: unset the session variables in shell formats."""
    if output_format == "json":
        return json.dumps(dict(result), indent=2)
    ended = ", ".join(result.get("sessions_ended") or []) or "none"
    removed = len(result.get("relays_removed") or [])
    lines = [f"# LocalCloud identity relays removed: {removed}; sessions ended: {ended}"]
    for failure in result.get("failures") or []:
        subject = failure.get("session") or failure.get("container")
        lines.append(f"# WARNING: {subject}: {failure.get('cause')}")
    if output_format != "docker-compose":
        lines.append("unset GCE_METADATA_HOST GCE_METADATA_IP")
    return "\n".join(lines) + "\n"


def rewrite_endpoints(value: Any, endpoint_map: dict[str, Any]) -> Any:
    normalized = {str(port): int(host_port) for port, host_port in endpoint_map.items()}
    if isinstance(value, dict):
        return {key: rewrite_endpoints(item, normalized) for key, item in value.items()}
    if isinstance(value, list):
        return [rewrite_endpoints(item, normalized) for item in value]
    if not isinstance(value, str):
        return value

    def replace(match: re.Match[str]) -> str:
        port = match.group("port")
        host_port = normalized.get(port)
        if host_port is None:
            return match.group(0)
        return f"127.0.0.1:{host_port}"

    return HOST_PORT.sub(replace, value)


def _validate_url_target(raw: str) -> None:
    if REAL_GOOGLE.search(raw):
        raise HostError(
            "real_google_endpoint",
            "Generated environment references a real Google endpoint",
        )
    try:
        parsed = urlsplit(raw)
    except ValueError as error:
        raise HostError("invalid_endpoint", "Generated environment contains an invalid URL", {"url": raw}) from error
    if parsed.hostname and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise HostError(
            "nonlocal_endpoint",
            "Generated environment contains a non-loopback endpoint",
            {"url": raw},
        )


def validate_local_endpoints(value: Any, *, in_endpoint_context: bool = True) -> None:
    if isinstance(value, str):
        if REAL_GOOGLE.search(value):
            raise HostError(
                "real_google_endpoint",
                "Generated environment references a real Google endpoint",
            )
        if in_endpoint_context:
            for match in re.finditer(r"https?://[^\s\"']+", value):
                raw = match.group(0).rstrip("\\,}")
                _validate_url_target(raw)
        else:
            for line in value.splitlines():
                assignment_match = ENDPOINT_CONFIG_ASSIGNMENT.search(line)
                if assignment_match and _is_endpoint_env_key(assignment_match.group("key")):
                    for match in re.finditer(r"https?://[^\s\"']+", line):
                        _validate_url_target(match.group(0).rstrip("\\,}"))
                elif re.match(r"^\s*https?://", line.strip()):
                    for match in re.finditer(r"https?://[^\s\"']+", line):
                        _validate_url_target(match.group(0).rstrip("\\,}"))
        return

    if isinstance(value, dict):
        for key, child in value.items():
            key_str = str(key)
            if REAL_GOOGLE.search(key_str):
                raise HostError(
                    "real_google_endpoint",
                    "Generated environment references a real Google endpoint",
                )
            child_is_endpoint = (
                _is_endpoint_value_key(key_str)
                or _is_endpoint_env_key(key_str)
            )
            validate_local_endpoints(child, in_endpoint_context=child_is_endpoint)
        return


    if isinstance(value, (list, tuple, set)):
        for item in value:
            validate_local_endpoints(item, in_endpoint_context=in_endpoint_context)
        return



def transform_endpoint_payload(
    value: Any,
    endpoint_map: dict[str, Any],
    *,
    endpoint_context: bool = False,
) -> Any:
    if isinstance(value, list):
        return [
            transform_endpoint_payload(
                item,
                endpoint_map,
                endpoint_context=endpoint_context,
            )
            for item in value
        ]
    if not isinstance(value, dict):
        if isinstance(value, str):
            if endpoint_context:
                return _rewrite_nested_generated_value(value, endpoint_map)
            return _transform_endpoint_text(value, endpoint_map)
        return value

    generated_record = endpoint_context or _is_endpoint_record(value)
    env_mapping = any(_is_endpoint_env_key(str(key)) for key in value)
    transformed: dict[Any, Any] = {}
    for key, child in value.items():
        normalized_key = str(key).lower()
        if generated_record and _is_endpoint_value_key(normalized_key):
            transformed[key] = _rewrite_endpoint_value(child, endpoint_map)
        elif generated_record and normalized_key == "port":
            transformed[key] = _rewrite_endpoint_value(
                child, endpoint_map, port=True
            )
        elif env_mapping and _is_endpoint_env_key(str(key)):
            transformed[key] = _rewrite_endpoint_value(child, endpoint_map)
        elif (
            normalized_key == "text"
            and isinstance(child, str)
            and (value.get("type") == "text" or "mimeType" in value)
        ):
            transformed[key] = _transform_endpoint_text(child, endpoint_map)
        else:
            transformed[key] = transform_endpoint_payload(
                child,
                endpoint_map,
                endpoint_context=generated_record,
            )
    return transformed


def _is_endpoint_env_key(key: str) -> bool:
    normalized = key.upper()
    return (
        normalized.endswith(("_HOST", "_ENDPOINT", "_URL", "_URI"))
        or "EMULATOR_HOST" in normalized
        or "CUSTOM_ENDPOINT" in normalized
        or "API_ENDPOINT_OVERRIDES" in normalized
    )


def _is_endpoint_value_key(key: str) -> bool:
    normalized = key.lower()
    return (
        normalized in ENDPOINT_FIELDS
        or normalized in {"emulator", "host", "url", "uri"}
        or normalized.endswith(("_endpoint", "_host", "_url", "_uri"))
    )


def _is_endpoint_record(value: dict[Any, Any]) -> bool:
    keys = {str(key).lower() for key in value}
    if {"token_uri", "auth_uri"} <= keys:
        return True
    if "endpoint_env" in keys and "real_google_cloud_fallback" in keys:
        return True
    return bool(
        keys.intersection({"endpoint", "env_value"})
        and keys.intersection(ENDPOINT_RECORD_MARKERS)
    )


def _rewrite_and_check_stale(value: Any, endpoint_map: dict[str, Any]) -> Any:
    rewritten = rewrite_endpoints(value, endpoint_map)
    _validate_no_stale_canonical_endpoints(rewritten, endpoint_map)
    return rewritten


def _rewrite_endpoint_value(
    value: Any,
    endpoint_map: dict[str, Any],
    *,
    port: bool = False,
) -> Any:
    rewritten = value
    if port and not isinstance(value, bool):
        mapped = endpoint_map.get(str(value))
        if mapped is not None:
            rewritten = str(mapped) if isinstance(value, str) else int(mapped)
    rewritten = _rewrite_and_check_stale(rewritten, endpoint_map)
    # Only values from keys already identified as endpoint fields go through
    # this path, so validating against real/non-loopback endpoints here is
    # safe; `_rewrite_nested_generated_value` below deliberately skips this
    # check since it runs on generic nested strings that could false-positive
    # (e.g. descriptive text mentioning a real hostname).
    validate_local_endpoints(rewritten)
    return rewritten


def _rewrite_nested_generated_value(
    value: Any,
    endpoint_map: dict[str, Any],
) -> Any:
    return _rewrite_and_check_stale(value, endpoint_map)


def _rewrite_endpoint_config(value: str, endpoint_map: dict[str, Any]) -> str:
    if not any(
        _is_endpoint_env_key(match.group("key"))
        for match in ENDPOINT_CONFIG_ASSIGNMENT.finditer(value)
    ):
        return value
    return _rewrite_endpoint_value(value, endpoint_map)


def _transform_endpoint_text(value: str, endpoint_map: dict[str, Any]) -> str:
    try:
        parsed = json.loads(value)
    except (json.JSONDecodeError, TypeError):
        return _rewrite_endpoint_config(value, endpoint_map)
    if not isinstance(parsed, (dict, list)):
        return _rewrite_endpoint_config(value, endpoint_map)
    transformed = transform_endpoint_payload(parsed, endpoint_map)
    if transformed == parsed:
        return value
    return json.dumps(transformed, ensure_ascii=False, separators=(",", ":"))


def _validate_no_unpublished_canonical_endpoints(
    value: Any,
    endpoint_map: dict[str, Any],
) -> None:
    """Off the canonical ports a runtime publishes only the ports its services
    use. An endpoint on any other canonical port would reach another runtime."""
    if all(str(port) == str(host_port) for port, host_port in endpoint_map.items()):
        return
    published = {str(port) for port in endpoint_map}
    host_ports = {int(host_port) for host_port in endpoint_map.values()}
    serialized = value if isinstance(value, str) else json.dumps(value)
    for match in HOST_PORT.finditer(serialized):
        port = int(match.group("port"))
        if (
            port in _CANONICAL_PORTS
            and str(port) not in published
            and port not in host_ports
        ):
            raise HostError(
                "stale_endpoint",
                "LocalCloud returned an endpoint on a port this runtime does not publish",
                {"canonical_port": str(port)},
            )


def _validate_no_stale_canonical_endpoints(
    value: Any,
    endpoint_map: dict[str, Any],
) -> None:
    _validate_no_unpublished_canonical_endpoints(value, endpoint_map)
    serialized = value if isinstance(value, str) else json.dumps(value)
    for canonical_port, host_port in endpoint_map.items():
        canonical = str(canonical_port)
        if canonical == str(host_port):
            continue
        stale_host = re.search(
            rf"(?:localhost|127\.0\.0\.1|\[::1\]):{re.escape(canonical)}(?!\d)",
            serialized,
            re.IGNORECASE,
        )
        stale_port = re.search(
            rf'(?:\\?")port(?:\\?")\s*:\s*{re.escape(canonical)}(?!\d)',
            serialized,
        )
        if stale_host or stale_port:
            raise HostError(
                "stale_endpoint",
                "Java MCP result contains an unmapped canonical endpoint",
                {
                    "canonical_port": canonical,
                    "expected_host_port": int(host_port),
                },
            )
