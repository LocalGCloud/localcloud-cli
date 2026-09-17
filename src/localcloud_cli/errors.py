from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class HostError(Exception):
    code: str
    message: str
    details: dict[str, Any] = field(default_factory=dict)

    def __str__(self) -> str:
        return self.message

    def to_dict(self) -> dict[str, Any]:
        return {
            "error": True,
            "code": self.code,
            "message": self.message,
            "details": self.details,
        }


class DockerError(HostError):
    """Errors originating from Docker interaction or daemon state."""


class ConfigError(HostError):
    """Errors originating from invalid or unresolvable configuration."""


class ReadinessError(HostError):
    """Errors originating from container readiness timeouts or failures."""


class EndpointError(HostError):
    """Errors originating from invalid or non-loopback endpoints."""


class OwnershipError(HostError):
    """Errors originating from container or resource ownership mismatches."""


class StateError(HostError):
    """Errors originating from active runtime state serialization or locking."""

