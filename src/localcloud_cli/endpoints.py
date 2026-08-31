from __future__ import annotations

import json
import re
from typing import Any
from urllib.parse import urlsplit

from .errors import HostError
from .java_client import JavaMcpClient


HOST_PORT = re.compile(r"(?P<host>localhost|127\.0\.0\.1|\[::1\])(?P<separator>:)(?P<port>\d{1,5})")
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
    rewritten = rewrite_endpoints(result, environment.get("endpoint_map") or {})
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


def validate_local_endpoints(value: Any) -> None:
    serialized = json.dumps(value, sort_keys=True) if not isinstance(value, str) else value
    if REAL_GOOGLE.search(serialized):
        raise HostError(
            "real_google_endpoint",
            "Generated environment references a real Google endpoint",
        )
    for match in re.finditer(r"https?://[^\s\"']+", serialized):
        raw = match.group(0).rstrip("\\,}")
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


def _validate_no_stale_canonical_endpoints(
    value: Any,
    endpoint_map: dict[str, Any],
) -> None:
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
