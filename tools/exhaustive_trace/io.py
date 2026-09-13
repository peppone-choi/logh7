"""Deterministic serialization and hashing helpers."""

from __future__ import annotations

import hashlib
import json
from dataclasses import fields, is_dataclass
from enum import Enum
from pathlib import Path
from typing import Any, Mapping


def _jsonable(value: Any) -> Any:
    if isinstance(value, Enum):
        return value.value
    if is_dataclass(value) and not isinstance(value, type):
        return {item.name: _jsonable(getattr(value, item.name)) for item in fields(value)}
    if isinstance(value, Mapping):
        normalized: dict[str, Any] = {}
        for key, item in value.items():
            normalized_key = _jsonable(key)
            if not isinstance(normalized_key, str):
                raise ValueError("mapping keys must be strings or string-valued enums")
            if normalized_key in normalized:
                raise ValueError(f"mapping key collision after normalization: {normalized_key}")
            normalized[normalized_key] = _jsonable(item)
        return normalized
    if isinstance(value, (list, tuple)):
        return [_jsonable(item) for item in value]
    if isinstance(value, Path):
        return str(value)
    return value


def canonical_json(value: Any) -> str:
    """Serialize JSON deterministically as UTF-8 text with one final newline."""

    return json.dumps(
        _jsonable(value),
        allow_nan=False,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ) + "\n"


def sha256_file(path: str | Path) -> str:
    """Return the uppercase SHA-256 of the exact bytes at *path*."""

    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()
