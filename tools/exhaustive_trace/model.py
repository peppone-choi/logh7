"""Immutable contracts shared by every exhaustive-trace inventory."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from types import MappingProxyType
from typing import Mapping


class StringEnum(str, Enum):
    """String-valued enum whose serialized value is its declared contract name."""


class InventoryKind(StringEnum):
    PROTOCOL = "PROTOCOL"
    UI = "UI"
    ENTITY = "ENTITY"
    RESOURCE = "RESOURCE"
    FUNCTION = "FUNCTION"
    AUTHORITY = "AUTHORITY"


class Reachability(StringEnum):
    SHIPPED_REACHABLE = "SHIPPED_REACHABLE"
    SHIPPED_DORMANT = "SHIPPED_DORMANT"
    MANUAL_ONLY = "MANUAL_ONLY"
    UNKNOWN = "UNKNOWN"


class EvidenceState(StringEnum):
    ENUMERATED = "ENUMERATED"
    STATIC_MAPPED = "STATIC_MAPPED"
    CODEC_PROVEN = "CODEC_PROVEN"
    RUNTIME_OBSERVED = "RUNTIME_OBSERVED"
    PLAYER_VISIBLE = "PLAYER_VISIBLE"
    AUTHORITY_PROVEN = "AUTHORITY_PROVEN"
    PERSISTENCE_PROVEN = "PERSISTENCE_PROVEN"
    BOTH_FACTIONS = "BOTH_FACTIONS"
    INDEPENDENTLY_REVIEWED = "INDEPENDENTLY_REVIEWED"


class Verdict(StringEnum):
    PASS = "PASS"
    PARTIAL = "PARTIAL"
    UNSEEN = "UNSEEN"
    BLOCKED = "BLOCKED"
    UNKNOWN = "UNKNOWN"


class RecoveryDisposition(StringEnum):
    RECOVERED_ORIGINAL = "RECOVERED_ORIGINAL"
    RECOVERABLE_STATIC = "RECOVERABLE_STATIC"
    RECOVERABLE_LIVE = "RECOVERABLE_LIVE"
    SOURCE_CONFLICT = "SOURCE_CONFLICT"
    ORIGINAL_SERVER_LOST = "ORIGINAL_SERVER_LOST"
    ORIGINAL_UNIMPLEMENTED = "ORIGINAL_UNIMPLEMENTED"
    AUTHORING_REQUIRED = "AUTHORING_REQUIRED"
    RIGHTS_REVIEW_REQUIRED = "RIGHTS_REVIEW_REQUIRED"


class ImplementationTarget(StringEnum):
    CONTRACT = "CONTRACT"
    SERVER = "SERVER"
    LEGACY_GATEWAY = "LEGACY_GATEWAY"
    NEW_CLIENT = "NEW_CLIENT"
    DATABASE = "DATABASE"
    CONTENT_ADMIN = "CONTENT_ADMIN"
    QA = "QA"
    INDEPENDENT_REVIEW = "INDEPENDENT_REVIEW"


ALLOWED_PROVENANCE = frozenset(
    {
        "ORIGINAL_OBSERVED",
        "ORIGINAL_MANUAL",
        "INFERRED",
        "NEW_DESIGN",
        "AUTHORED_PLACEHOLDER",
        "UNKNOWN",
        "LEGACY_CANDIDATE",
    }
)


def _require_text(field_name: str, value: str) -> None:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field_name} must be non-empty text")


@dataclass(frozen=True)
class InventoryRow:
    key: str
    inventory: InventoryKind
    name: str
    provenance: str
    reachability: Reachability
    states: Mapping[EvidenceState, bool] = field(default_factory=dict)

    def __post_init__(self) -> None:
        _require_text("key", self.key)
        _require_text("name", self.name)
        if self.provenance not in ALLOWED_PROVENANCE:
            raise ValueError(f"unsupported provenance: {self.provenance}")
        if not isinstance(self.inventory, InventoryKind):
            raise ValueError("inventory must be an InventoryKind")
        if not isinstance(self.reachability, Reachability):
            raise ValueError("reachability must be a Reachability")

        supplied = dict(self.states)
        if any(not isinstance(state, EvidenceState) for state in supplied):
            raise ValueError("state keys must be EvidenceState values")
        if any(type(value) is not bool for value in supplied.values()):
            raise ValueError("state values must be boolean")
        normalized = {state: supplied.get(state, False) for state in EvidenceState}
        object.__setattr__(self, "states", MappingProxyType(normalized))


@dataclass(frozen=True)
class TraceNode:
    key: str
    kind: str
    label: str
    evidence: tuple[str, ...]

    def __post_init__(self) -> None:
        _require_text("key", self.key)
        _require_text("kind", self.kind)
        _require_text("label", self.label)
        if not isinstance(self.evidence, (list, tuple)):
            raise ValueError("evidence must contain non-empty references")
        evidence = tuple(self.evidence)
        if not evidence or any(not isinstance(item, str) or not item.strip() for item in evidence):
            raise ValueError("evidence must contain non-empty references")
        object.__setattr__(self, "evidence", evidence)


@dataclass(frozen=True)
class TraceEdge:
    source: str
    relation: str
    target: str
    evidence: tuple[str, ...]

    def __post_init__(self) -> None:
        _require_text("source", self.source)
        _require_text("relation", self.relation)
        _require_text("target", self.target)
        if not isinstance(self.evidence, (list, tuple)):
            raise ValueError("evidence must contain non-empty references")
        evidence = tuple(self.evidence)
        if not evidence or any(not isinstance(item, str) or not item.strip() for item in evidence):
            raise ValueError("evidence must contain non-empty references")
        object.__setattr__(self, "evidence", evidence)
