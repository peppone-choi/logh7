"""Build the closed authority and persistence obligation inventory.

Static source is only a candidate.  It never proves runtime authority,
persistence, or original-server behaviour.  Exact LOGH7_TRACE markers are the
only permitted source-to-obligation join.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

from .io import canonical_json, sha256_file


STATE_NAMES = (
    "ENUMERATED", "STATIC_MAPPED", "CODEC_PROVEN", "RUNTIME_OBSERVED",
    "PLAYER_VISIBLE", "AUTHORITY_PROVEN", "PERSISTENCE_PROVEN",
    "BOTH_FACTIONS", "INDEPENDENTLY_REVIEWED",
)
AUTHORITY_SECTION_NAMES = frozenset({
    "commandHandler", "parsing", "validation", "decision", "mutation",
    "event", "response", "notify", "notificationFanout", "persistence", "checkpoint",
    "replayReducer", "reconnectProjection", "idempotency", "adminMutation",
})
IMPLEMENTATION_TARGETS = (
    "CONTRACT", "SERVER", "LEGACY_GATEWAY", "NEW_CLIENT", "DATABASE",
    "CONTENT_ADMIN", "QA", "INDEPENDENT_REVIEW",
)
LIFECYCLE_NAMES = (
    "create", "definition", "destroy", "query", "select", "terminal",
    "transfer", "update",
)
ROLE_TO_SECTION = {
    "COMMAND_HANDLER": "commandHandler", "PARSING": "parsing",
    "VALIDATION": "validation", "MUTATION": "mutation", "EVENT": "event",
    "FANOUT": "notificationFanout", "RESPONSE": "response",
    "NOTIFY": "notify", "PERSISTENCE": "persistence",
    "CHECKPOINT": "checkpoint", "REPLAY_REDUCER": "replayReducer",
    "RECONNECT_PROJECTION": "reconnectProjection", "IDEMPOTENCY": "idempotency",
    "ADMIN_MUTATION": "adminMutation", "ACCEPT_DECISION": "decision",
    "REJECT_DECISION": "decision",
}
SPECIAL_ROLES = frozenset({
    *(f"LIFECYCLE_{name.upper()}" for name in LIFECYCLE_NAMES),
    "AUTHORITY_OWNER", "EMISSION_IDENTITY", "AUTHORITY_COUNTERPART",
    "CLIENT_ONLY_DISPOSITION",
})
KNOWN_ROLES = frozenset(ROLE_TO_SECTION) | SPECIAL_ROLES
MARKER_RE = re.compile(r"LOGH7_TRACE\b(?P<body>[^\r\n]*)")
TEXT_SUFFIXES = frozenset({
    ".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".cs", ".json",
    ".sql", ".proto", ".toml", ".yaml", ".yml", ".xml", ".txt", ".md",
})


def _sha(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest().upper()


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        value = json.loads(line)
        if not isinstance(value, dict):
            raise ValueError(f"{path}:{number} must be an object")
        rows.append(value)
    keys = [row.get("key") for row in rows]
    if any(not isinstance(key, str) or not key for key in keys):
        raise ValueError(f"{path} contains a row without a key")
    if len({key.casefold() for key in keys}) != len(keys):
        raise ValueError(f"{path} contains duplicate keys")
    return rows


def _is_reparse(path: Path) -> bool:
    try:
        stat = path.lstat()
    except OSError:
        return False
    return path.is_symlink() or bool(getattr(stat, "st_file_attributes", 0) & 0x400)


def _require_root(label: str, path: Path) -> Path:
    declared = Path(os.path.abspath(path))
    for component in [*reversed(declared.parents), declared]:
        if component == Path(component.anchor):
            continue
        if component.exists() and _is_reparse(component):
            raise ValueError(f"{label} path contains a link or reparse point: {component}")
    if not declared.is_dir():
        raise ValueError(f"{label} must be a real directory: {declared}")
    return declared.resolve()


def _scan_root(label: str, root: Path, provenance: str) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]]]:
    files: list[dict[str, Any]] = []
    markers: list[dict[str, Any]] = []
    seen_paths: set[str] = set()
    all_paths = sorted(root.rglob("*"), key=lambda item: item.as_posix().casefold())
    directories: list[str] = []
    for path in all_paths:
        if _is_reparse(path):
            raise ValueError(f"source tree contains a link or reparse point: {path}")
        relative = path.relative_to(root).as_posix()
        folded = relative.casefold()
        if folded in seen_paths:
            raise ValueError(f"case-insensitive source path collision: {relative}")
        seen_paths.add(folded)
        if path.is_dir():
            directories.append(relative)
            continue
        if not path.is_file():
            continue
        file_id = f"SOURCE_FILE:{label}:{relative}"
        record = {
            "candidateId": file_id, "rootLabel": label, "path": relative,
            "sha256": sha256_file(path), "sizeBytes": path.stat().st_size,
            "provenance": provenance,
        }
        files.append(record)
        if path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except UnicodeDecodeError:
            continue
        for line_number, line in enumerate(lines, 1):
            for match_index, match in enumerate(MARKER_RE.finditer(line), 1):
                fields: dict[str, str] = {}
                for token in match.group("body").strip().split():
                    if "=" not in token:
                        continue
                    field, value = token.split("=", 1)
                    if field in fields:
                        raise ValueError(f"duplicate trace marker field {field}: {path}:{line_number}")
                    fields[field] = value
                if not {"target", "role", "status"} <= set(fields):
                    continue
                target, role, status = fields["target"], fields["role"], fields["status"]
                markers.append({
                    "candidateId": f"TRACE_MARKER:{label}:{relative}:{line_number}:{match_index}",
                    "sourceFileCandidateId": file_id, "target": target, "role": role,
                    "status": status, "line": line_number, "provenance": provenance,
                    "reason": fields.get("reason"), "counterpart": fields.get("counterpart"),
                })
    tree_entries = [f"D\t{path}" for path in directories]
    tree_entries.extend(f"F\t{item['path']}\t{item['sha256']}\t{item['sizeBytes']}" for item in files)
    root_record = {
        "candidateId": f"SOURCE_ROOT:{label}", "label": label,
        "declaredPath": str(root), "fileCount": len(files),
        "directoryCount": len(directories), "directories": directories,
        "treeSha256": _sha(tree_entries),
        "provenance": provenance,
    }
    return root_record, files, markers


def _surface_payload(raw: Mapping[str, Any]) -> dict[str, Any]:
    return {
        name: raw[name]
        for name in (
            "upstream", "sourceRoots", "sourceFileCandidates", "traceMarkerCandidates",
            "requirementCandidates", "conservation", "audit",
        )
    }


def _requirement(candidate_id: str, row_key: str, kind: str, source_key: str, **extra: Any) -> dict[str, Any]:
    return {
        "candidateId": candidate_id, "rowKey": row_key, "kind": kind,
        "sourceKey": source_key, **extra,
    }


def _build_requirements(
    protocols: Sequence[Mapping[str, Any]],
    entities: Sequence[Mapping[str, Any]],
    ui_rows: Sequence[Mapping[str, Any]],
) -> list[dict[str, Any]]:
    requirements: list[dict[str, Any]] = []
    for row in protocols:
        key = str(row["key"]); row_key = f"AUTHORITY:{key}"
        direction = row.get("direction", {}).get("status", "UNKNOWN")
        siblings = row.get("siblings", {})
        request_codes = siblings.get("request", {}).get("codes", []) if isinstance(siblings, dict) else []
        own_code = row.get("code", key.rsplit(":", 1)[-1])
        role = "REQUEST_PATH" if own_code in request_codes else (
            "SERVER_OUTPUT" if direction == "SERVER_TO_CLIENT" else "UNCLASSIFIED_SERVER_INPUT"
        )
        requirements.append(_requirement(
            f"AUTHORITY_REQUIREMENT:PROTOCOL:{key}", row_key, "PROTOCOL_PATH", key,
            name=row.get("name", key), direction=direction, protocolRole=role,
            provenance=row.get("provenance", "UNKNOWN"), reachability=row.get("reachability", "UNKNOWN"),
            evidence=row.get("evidence", []),
        ))
    for row in entities:
        key = str(row["key"])
        if row.get("stateBearing") is True:
            row_key = f"AUTHORITY:{key}"
            requirements.append(_requirement(
                f"AUTHORITY_REQUIREMENT:ENTITY:{key}", row_key, "ENTITY_PATH", key,
                name=row.get("name", key), entityType=row.get("entityType", "UNKNOWN"),
                provenance=row.get("provenance", "UNKNOWN"), reachability=row.get("reachability", "UNKNOWN"),
                evidence=row.get("evidence", []),
            ))
            for lifecycle in LIFECYCLE_NAMES:
                requirements.append(_requirement(
                    f"AUTHORITY_REQUIREMENT:LIFECYCLE:{key}:{lifecycle.upper()}", row_key,
                    "ENTITY_LIFECYCLE", key, lifecycle=lifecycle,
                ))
        elif row.get("rowKind") == "RECORD_TYPE" and row.get("entityType") == "EVENT_RECORD":
            row_key = f"AUTHORITY:EVENT_CANDIDATE:{key}"
            requirements.append(_requirement(
                f"AUTHORITY_REQUIREMENT:EVENT:{key}", row_key, "EVENT_PATH", key,
                name=row.get("name", key), provenance=row.get("provenance", "UNKNOWN"),
                reachability=row.get("reachability", "UNKNOWN"), evidence=row.get("evidence", []),
            ))
    for row in ui_rows:
        key = str(row["key"]); row_key = f"AUTHORITY:CLIENT_BEHAVIOR:{key}"
        requirements.append(_requirement(
            f"AUTHORITY_REQUIREMENT:UI:{key}", row_key, "CLIENT_BEHAVIOR_PATH", key,
            name=row.get("name", key), interactionKind=row.get("interactionKind", "UNKNOWN"),
            provenance=row.get("provenance", "UNKNOWN"), reachability=row.get("reachability", "UNKNOWN"),
            evidence=row.get("evidence", []),
        ))
    return sorted(requirements, key=lambda item: item["candidateId"])


def build_authority_source(
    *, server_root: str | Path, contracts_root: str | Path, database_root: str | Path,
    protocol_inventory: str | Path, entity_inventory: str | Path,
    ui_inventory: str | Path, legacy_candidate_root: str | Path | None = None,
) -> dict[str, Any]:
    roots = [
        ("SERVER", _require_root("server_root", Path(server_root)), "NEW_DESIGN"),
        ("CONTRACTS", _require_root("contracts_root", Path(contracts_root)), "NEW_DESIGN"),
        ("DATABASE", _require_root("database_root", Path(database_root)), "NEW_DESIGN"),
    ]
    if legacy_candidate_root is not None:
        roots.append(("LEGACY", _require_root("legacy_candidate_root", Path(legacy_candidate_root)), "LEGACY_CANDIDATE"))
    protocol_path, entity_path, ui_path = map(Path, (protocol_inventory, entity_inventory, ui_inventory))
    protocols, entities, ui_rows = map(_read_jsonl, (protocol_path, entity_path, ui_path))
    source_roots: list[dict[str, Any]] = []
    files: list[dict[str, Any]] = []
    markers: list[dict[str, Any]] = []
    for label, root, provenance in roots:
        root_record, root_files, root_markers = _scan_root(label, root, provenance)
        source_roots.append(root_record); files.extend(root_files); markers.extend(root_markers)

    requirements = _build_requirements(protocols, entities, ui_rows)
    source_roots.sort(key=lambda x: x["candidateId"]); files.sort(key=lambda x: x["candidateId"])
    markers.sort(key=lambda x: x["candidateId"])
    legacy_files = sum(1 for item in files if item["provenance"] == "LEGACY_CANDIDATE")
    raw = {
        "schemaVersion": 1,
        "upstream": {
            "protocol": {"path": str(protocol_path.resolve()), "sha256": sha256_file(protocol_path), "rowCount": len(protocols)},
            "entities": {"path": str(entity_path.resolve()), "sha256": sha256_file(entity_path), "rowCount": len(entities)},
            "ui": {"path": str(ui_path.resolve()), "sha256": sha256_file(ui_path), "rowCount": len(ui_rows)},
        },
        "sourceRoots": source_roots, "sourceFileCandidates": files,
        "traceMarkerCandidates": markers, "requirementCandidates": requirements,
        "conservation": {
            "sourceRoots": len(source_roots), "sourceFiles": len(files) - legacy_files,
            "legacySourceFiles": legacy_files, "traceMarkers": len(markers),
            "requirements": len(requirements),
        },
        "audit": {
            "nameMatchIsIdentity": False, "staticSourceIsAuthorityProof": False,
            "missingServerMeansClientOnly": False, "legacyCandidateIsOriginalEvidence": False,
        },
    }
    raw["surfaceSha256"] = _sha(_surface_payload(raw))
    return raw


def _validate_raw(raw: Mapping[str, Any]) -> None:
    required = {"schemaVersion", "upstream", "sourceRoots", "sourceFileCandidates", "traceMarkerCandidates", "requirementCandidates", "conservation", "audit", "surfaceSha256"}
    if set(raw) != required or raw.get("schemaVersion") != 1:
        raise ValueError("authority source top-level contract mismatch")
    collections = ("sourceRoots", "sourceFileCandidates", "traceMarkerCandidates", "requirementCandidates")
    expected = _sha(_surface_payload(raw))
    if raw.get("surfaceSha256") != expected:
        raise ValueError("authority source surface hash mismatch")
    ids: list[str] = []
    for name in collections:
        value = raw[name]
        if not isinstance(value, list): raise ValueError(f"{name} must be an array")
        for item in value:
            cid = item.get("candidateId") if isinstance(item, dict) else None
            if not isinstance(cid, str) or not cid: raise ValueError("candidateId must be non-empty")
            ids.append(cid.casefold())
    if len(set(ids)) != len(ids): raise ValueError("duplicate candidateId")
    expected_audit = {
        "nameMatchIsIdentity": False, "staticSourceIsAuthorityProof": False,
        "missingServerMeansClientOnly": False, "legacyCandidateIsOriginalEvidence": False,
    }
    if raw["audit"] != expected_audit:
        raise ValueError("authority source audit contract mismatch")
    upstream = raw["upstream"]
    if not isinstance(upstream, Mapping) or set(upstream) != {"protocol", "entities", "ui"}:
        raise ValueError("upstream contract mismatch")
    upstream_rows: dict[str, list[dict[str, Any]]] = {}
    for name, item in upstream.items():
        if not isinstance(item, Mapping) or set(item) != {"path", "sha256", "rowCount"}:
            raise ValueError(f"upstream {name} contract mismatch")
        path = Path(item["path"])
        if not path.is_file() or sha256_file(path) != item["sha256"]:
            raise ValueError(f"upstream {name} hash mismatch")
        upstream_rows[name] = _read_jsonl(path)
        if type(item["rowCount"]) is not int or item["rowCount"] != len(upstream_rows[name]):
            raise ValueError(f"upstream {name} rowCount mismatch")
    expected_requirements = _build_requirements(
        upstream_rows["protocol"], upstream_rows["entities"], upstream_rows["ui"]
    )
    actual_requirements = sorted(raw["requirementCandidates"], key=lambda item: item.get("candidateId", ""))
    if actual_requirements != expected_requirements:
        raise ValueError("authority requirement candidates differ from bound upstream inventories")
    file_ids = {item["candidateId"] for item in raw["sourceFileCandidates"]}
    authority_targets = {
        item["rowKey"] for item in raw["requirementCandidates"]
        if item.get("kind") != "ENTITY_LIFECYCLE"
    }
    for marker in raw["traceMarkerCandidates"]:
        if marker.get("sourceFileCandidateId") not in file_ids:
            raise ValueError("trace marker sourceFileCandidateId does not resolve to a source file")
        if marker.get("status") not in {"IMPLEMENTED", "STUB", "NOT_APPLICABLE"}:
            raise ValueError("trace marker has unsupported status")
        if marker["status"] == "NOT_APPLICABLE" and not marker.get("reason"):
            raise ValueError("NOT_APPLICABLE trace marker requires a reason")
        if marker.get("role") == "AUTHORITY_COUNTERPART" and marker.get("status") == "IMPLEMENTED" and not marker.get("counterpart"):
            raise ValueError("AUTHORITY_COUNTERPART marker requires counterpart identity")
        if marker.get("role") == "AUTHORITY_COUNTERPART" and marker.get("counterpart") not in authority_targets:
            raise ValueError("AUTHORITY_COUNTERPART marker counterpart does not resolve")
    root_labels = [root.get("label") for root in raw["sourceRoots"]]
    if len(set(root_labels)) != len(root_labels):
        raise ValueError("source root labels must be unique")
    if set(root_labels) not in ({"SERVER", "CONTRACTS", "DATABASE"}, {"SERVER", "CONTRACTS", "DATABASE", "LEGACY"}):
        raise ValueError("source root label set mismatch")
    for root in raw["sourceRoots"]:
        live_root, live_files, live_markers = _scan_root(
            root["label"], _require_root(root["label"], Path(root["declaredPath"])), root["provenance"]
        )
        expected_files = sorted(
            [item for item in raw["sourceFileCandidates"] if item["rootLabel"] == root["label"]],
            key=lambda item: item["candidateId"],
        )
        expected_markers = sorted(
            [
                item for item in raw["traceMarkerCandidates"]
                if item["candidateId"].startswith(f"TRACE_MARKER:{root['label']}:")
            ],
            key=lambda item: item["candidateId"],
        )
        live_files.sort(key=lambda item: item["candidateId"])
        live_markers.sort(key=lambda item: item["candidateId"])
        if root != live_root or expected_files != live_files or expected_markers != live_markers:
            raise ValueError(f"source root {root['label']} tree/hash/marker drift")
    conservation = raw["conservation"]
    expected_counts = {
        "sourceRoots": len(raw["sourceRoots"]),
        "sourceFiles": sum(x.get("provenance") != "LEGACY_CANDIDATE" for x in raw["sourceFileCandidates"]),
        "legacySourceFiles": sum(x.get("provenance") == "LEGACY_CANDIDATE" for x in raw["sourceFileCandidates"]),
        "traceMarkers": len(raw["traceMarkerCandidates"]), "requirements": len(raw["requirementCandidates"]),
    }
    if conservation != expected_counts or any(type(v) is not int for v in conservation.values()):
        raise ValueError("authority source conservation mismatch")


def _states() -> dict[str, bool]:
    return {name: name == "ENUMERATED" for name in STATE_NAMES}


def _verdicts() -> dict[str, str]:
    return {name: "PASS" if name == "ENUMERATED" else "UNSEEN" for name in STATE_NAMES}


def _section(status: str, reason: str, candidates: Iterable[str] = ()) -> dict[str, Any]:
    return {"status": status, "reason": reason, "sourceCandidateIds": sorted(set(candidates))}


def _applicable_sections(kind: str, direction: str | None = None) -> set[str]:
    if kind == "CLIENT_BEHAVIOR_PATH": return set()
    result = set(AUTHORITY_SECTION_NAMES)
    if kind == "PROTOCOL_PATH" and direction == "SERVER_TO_CLIENT":
        result -= {"commandHandler", "parsing", "validation", "decision", "mutation", "idempotency", "adminMutation"}
    if kind == "ENTITY_PATH": result -= {"commandHandler", "parsing"}
    if kind == "EVENT_PATH": result -= {"commandHandler", "parsing", "validation", "decision", "mutation", "idempotency", "adminMutation"}
    return result


def _target_reason(target: str, kind: str) -> str:
    return f"{target} must explicitly adjudicate the {kind} authority obligation"


def _marker_outcome(markers: Sequence[Mapping[str, Any]], default_reason: str) -> dict[str, Any]:
    ids = [marker["candidateId"] for marker in markers]
    if not markers:
        return _section("MISSING_CURRENT_SOURCE", default_reason)
    if len(markers) > 1:
        return _section("SOURCE_CONFLICT", "multiple exact candidates require adjudication", ids)
    marker = markers[0]
    if marker["status"] == "STUB":
        return _section("STUB", "an exact marker identifies incomplete implementation", ids)
    if marker["status"] == "NOT_APPLICABLE":
        return _section("NOT_APPLICABLE_EXPLICIT", marker["reason"], ids)
    return _section("SOURCE_CANDIDATE", "static source candidate; runtime proof absent", ids)


def _closed_static(status: str) -> bool:
    return status in {
        "SOURCE_CANDIDATE", "NOT_APPLICABLE_EXPLICIT", "NOT_APPLICABLE_DIRECTION",
        "NOT_APPLICABLE_ROW_KIND",
    }


def _boundary_for_section(name: str) -> str:
    return {
        "commandHandler": "COMMAND_HANDLER", "parsing": "PARSING",
        "validation": "VALIDATION", "decision": "DECISION",
        "mutation": "MUTATION", "event": "EVENT", "response": "RESPONSE",
        "notify": "NOTIFY", "notificationFanout": "NOTIFICATION_FANOUT",
        "persistence": "PERSISTENCE", "checkpoint": "CHECKPOINT",
        "replayReducer": "REPLAY_REDUCER", "reconnectProjection": "RECONNECT_PROJECTION",
        "idempotency": "IDEMPOTENCY", "adminMutation": "ADMIN_MUTATION",
    }[name]


def build_authority_inventory(raw: Mapping[str, Any]) -> list[dict[str, Any]]:
    _validate_raw(raw)
    requirements = list(raw["requirementCandidates"])
    main = [x for x in requirements if x["kind"] != "ENTITY_LIFECYCLE"]
    current_markers = [x for x in raw["traceMarkerCandidates"] if x["provenance"] != "LEGACY_CANDIDATE"]
    legacy_markers = [x for x in raw["traceMarkerCandidates"] if x["provenance"] == "LEGACY_CANDIDATE"]
    rows: list[dict[str, Any]] = []
    for requirement in main:
        key, kind = requirement["rowKey"], requirement["kind"]
        direction = requirement.get("direction")
        applicable = _applicable_sections(kind, direction)
        sections: dict[str, Any] = {}
        attached: list[str] = []
        for section_name in sorted(AUTHORITY_SECTION_NAMES):
            if section_name not in applicable:
                status = "NOT_APPLICABLE_DIRECTION" if kind == "PROTOCOL_PATH" else "NOT_APPLICABLE_ROW_KIND"
                sections[section_name] = _section(status, f"{section_name} is not applicable to {kind} in this direction")
                continue
            section_markers = [m for m in current_markers if m["target"] == key and ROLE_TO_SECTION.get(m["role"]) == section_name]
            ids = [m["candidateId"] for m in section_markers]; attached.extend(ids)
            sections[section_name] = _marker_outcome(section_markers, "no exact current-source trace marker")
        # Keep the two decision outcomes independent inside the aggregate section.
        if "decision" in applicable:
            for label, role in (("accepted", "ACCEPT_DECISION"), ("rejected", "REJECT_DECISION")):
                selected = [m for m in current_markers if m["target"] == key and m["role"] == role]
                sections["decision"][label] = _marker_outcome(
                    selected, "accepted and rejected outcomes are separate static obligations"
                )
                attached.extend(m["candidateId"] for m in selected)
            children = (sections["decision"]["accepted"], sections["decision"]["rejected"])
            if any(c["status"] == "STUB" for c in children): sections["decision"]["status"] = "STUB"
            elif any(c["status"] == "SOURCE_CONFLICT" for c in children): sections["decision"]["status"] = "SOURCE_CONFLICT"
            elif all(_closed_static(c["status"]) for c in children): sections["decision"]["status"] = "SOURCE_CANDIDATE"
            else: sections["decision"]["status"] = "MISSING_CURRENT_SOURCE"
        lifecycle: dict[str, Any] = {}
        lifecycle_requirements = [x for x in requirements if x["kind"] == "ENTITY_LIFECYCLE" and x["rowKey"] == key]
        for item in sorted(lifecycle_requirements, key=lambda x: x["lifecycle"]):
            role = f"LIFECYCLE_{item['lifecycle'].upper()}"
            selected = [m for m in current_markers if m["target"] == key and m["role"] == role]
            lifecycle[item["lifecycle"]] = _marker_outcome(selected, "no exact lifecycle source identity")
            lifecycle[item["lifecycle"]]["requirementCandidateId"] = item["candidateId"]
            attached.extend(m["candidateId"] for m in selected)
        row_candidate_ids = [requirement["candidateId"], *[x["candidateId"] for x in lifecycle_requirements], *attached]
        legacy = [m["candidateId"] for m in legacy_markers if m["target"] == key and m["role"] in KNOWN_ROLES]
        row = {
            "schemaVersion": 1, "key": key, "inventory": "AUTHORITY", "rowKind": kind,
            "sourceKey": requirement["sourceKey"], "name": requirement.get("name", requirement["sourceKey"]),
            "provenance": requirement.get("provenance", "UNKNOWN"),
            "reachability": requirement.get("reachability", "UNKNOWN"),
            "states": _states(), "stateVerdicts": _verdicts(), "sections": sections,
            "recoveryDisposition": "ORIGINAL_SERVER_LOST", "disposition": "ORPHAN_CURRENT_SOURCE",
            "firstMissingBoundary": "CURRENT_AUTHORITY_SOURCE", "sourceCandidateIds": sorted(set(row_candidate_ids)),
            "legacyCandidates": sorted(legacy), "evidence": sorted(set(requirement.get("evidence", []))),
            "implementationDisposition": {target: {"status": "REQUIRED", "reason": _target_reason(target, kind)} for target in IMPLEMENTATION_TARGETS},
        }
        if kind == "PROTOCOL_PATH": row.update(direction=direction, protocolRole=requirement.get("protocolRole"))
        if kind == "ENTITY_PATH":
            owner_markers = [m for m in current_markers if m["target"] == key and m["role"] == "AUTHORITY_OWNER"]
            owner = _marker_outcome(owner_markers, "state-bearing entity has no exact authority owner")
            attached.extend(m["candidateId"] for m in owner_markers)
            row["sourceCandidateIds"] = sorted(set([*row["sourceCandidateIds"], *[m["candidateId"] for m in owner_markers]]))
            row.update(entityType=requirement.get("entityType"), entityLifecycle=lifecycle, authorityOwner=owner)
        if kind == "EVENT_PATH":
            emitter_markers = [m for m in current_markers if m["target"] == key and m["role"] == "EMISSION_IDENTITY"]
            emission = _marker_outcome(emitter_markers, "record shape is not proof of server emission")
            row["sourceCandidateIds"] = sorted(set([*row["sourceCandidateIds"], *[m["candidateId"] for m in emitter_markers]]))
            row["emissionIdentity"] = emission
        if kind == "CLIENT_BEHAVIOR_PATH":
            counterpart_markers = [m for m in current_markers if m["target"] == key and m["role"] in {"AUTHORITY_COUNTERPART", "CLIENT_ONLY_DISPOSITION"}]
            counterpart = _marker_outcome(counterpart_markers, "client behavior does not identify an authority counterpart")
            counterpart["dispositionKind"] = "UNRESOLVED"
            counterpart["counterpartKey"] = None
            if len(counterpart_markers) == 1:
                counterpart["dispositionKind"] = counterpart_markers[0]["role"]
                if counterpart_markers[0]["role"] == "AUTHORITY_COUNTERPART":
                    counterpart["counterpartKey"] = counterpart_markers[0].get("counterpart")
            counterpart["status"] = "UNRESOLVED" if counterpart["status"] == "MISSING_CURRENT_SOURCE" else counterpart["status"]
            row["sourceCandidateIds"] = sorted(set([*row["sourceCandidateIds"], *[m["candidateId"] for m in counterpart_markers]]))
            row.update(interactionKind=requirement.get("interactionKind"), authorityCounterpart=counterpart)

        # Find the first unresolved static boundary in executable order.
        if kind == "CLIENT_BEHAVIOR_PATH":
            if row["authorityCounterpart"]["status"] == "UNRESOLVED":
                row["firstMissingBoundary"] = "AUTHORITY_COUNTERPART_CLASSIFICATION"
                row["disposition"] = "AUTHORITY_COUNTERPART_UNRESOLVED"
            else:
                row["firstMissingBoundary"] = "RUNTIME_AUTHORITY_EVIDENCE"
                row["disposition"] = "SOURCE_CANDIDATES_UNVERIFIED"
        else:
            special: list[tuple[str, Mapping[str, Any]]] = []
            if kind == "ENTITY_PATH": special.append(("AUTHORITY_OWNER", row["authorityOwner"]))
            if kind == "EVENT_PATH": special.append(("EMISSION_IDENTITY", row["emissionIdentity"]))
            ordered_names = (
                "commandHandler", "parsing", "validation", "decision", "mutation", "event",
                "notificationFanout", "response", "notify", "persistence", "checkpoint",
                "replayReducer", "reconnectProjection", "idempotency", "adminMutation",
            )
            if kind == "PROTOCOL_PATH" and direction == "SERVER_TO_CLIENT":
                ordered_names = (
                    "notificationFanout", "response", "notify", "event", "persistence",
                    "checkpoint", "replayReducer", "reconnectProjection",
                )
            candidates = [*special, *((_boundary_for_section(name), sections[name]) for name in ordered_names if name in applicable)]
            if kind == "ENTITY_PATH":
                candidates.extend((f"LIFECYCLE_{name.upper()}", lifecycle[name]) for name in LIFECYCLE_NAMES)
            unresolved = [(boundary, value) for boundary, value in candidates if not _closed_static(value["status"])]
            if unresolved:
                boundary, value = unresolved[0]
                row["firstMissingBoundary"] = "IMPLEMENTATION_STUB" if value["status"] == "STUB" else boundary
                row["disposition"] = "ORPHAN_CURRENT_SOURCE"
            else:
                row["firstMissingBoundary"] = "RUNTIME_AUTHORITY_EVIDENCE"
                row["disposition"] = "SOURCE_CANDIDATES_UNVERIFIED"
        rows.append(row)
    return normalize_authority_inventory(rows)


def normalize_authority_inventory(rows: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    normalized = json.loads(canonical_json(list(rows)))
    keys = [row.get("key") for row in normalized]
    if any(not isinstance(key, str) or not key for key in keys) or len({k.casefold() for k in keys}) != len(keys):
        raise ValueError("authority inventory contains duplicate or invalid keys")
    base_fields = {
        "schemaVersion", "key", "inventory", "rowKind", "sourceKey", "name", "provenance",
        "reachability", "states", "stateVerdicts", "sections", "recoveryDisposition",
        "disposition", "firstMissingBoundary", "sourceCandidateIds", "legacyCandidates",
        "evidence", "implementationDisposition",
    }
    extras = {
        "PROTOCOL_PATH": {"direction", "protocolRole"},
        "ENTITY_PATH": {"entityType", "entityLifecycle", "authorityOwner"},
        "EVENT_PATH": {"emissionIdentity"},
        "CLIENT_BEHAVIOR_PATH": {"interactionKind", "authorityCounterpart"},
    }
    valid_statuses = {
        "MISSING_CURRENT_SOURCE", "SOURCE_CANDIDATE", "SOURCE_CONFLICT", "STUB",
        "NOT_APPLICABLE_EXPLICIT", "NOT_APPLICABLE_DIRECTION", "NOT_APPLICABLE_ROW_KIND",
    }

    def text(name: str, value: Any) -> str:
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"{name} must be non-empty text")
        return value

    def text_list(name: str, value: Any) -> list[str]:
        if not isinstance(value, list) or any(not isinstance(item, str) or not item.strip() for item in value):
            raise ValueError(f"{name} must be a non-empty-text list")
        if len(value) != len(set(value)):
            raise ValueError(f"{name} must contain unique text")
        return value

    def section(name: str, value: Any, *, extra: set[str] | None = None) -> Mapping[str, Any]:
        extra = extra or set()
        expected = {"status", "reason", "sourceCandidateIds"} | extra
        if not isinstance(value, Mapping) or set(value) != expected:
            raise ValueError(f"{name} section schema mismatch")
        if value["status"] not in valid_statuses:
            raise ValueError(f"{name} section status mismatch")
        text(f"{name}.reason", value["reason"])
        text_list(f"{name}.sourceCandidateIds", value["sourceCandidateIds"])
        return value

    allowed_provenance = {
        "ORIGINAL_OBSERVED", "ORIGINAL_MANUAL", "INFERRED", "NEW_DESIGN",
        "AUTHORED_PLACEHOLDER", "UNKNOWN", "LEGACY_CANDIDATE",
    }
    allowed_reachability = {"SHIPPED_REACHABLE", "SHIPPED_DORMANT", "MANUAL_ONLY", "UNKNOWN"}
    allowed_disposition = {"ORPHAN_CURRENT_SOURCE", "SOURCE_CANDIDATES_UNVERIFIED", "AUTHORITY_COUNTERPART_UNRESOLVED"}
    allowed_boundaries = {
        "COMMAND_HANDLER", "PARSING", "VALIDATION", "DECISION", "MUTATION", "EVENT",
        "RESPONSE", "NOTIFY", "NOTIFICATION_FANOUT", "PERSISTENCE", "CHECKPOINT",
        "REPLAY_REDUCER", "RECONNECT_PROJECTION", "IDEMPOTENCY", "ADMIN_MUTATION",
        "AUTHORITY_OWNER", "EMISSION_IDENTITY", "AUTHORITY_COUNTERPART_CLASSIFICATION",
        "IMPLEMENTATION_STUB", "RUNTIME_AUTHORITY_EVIDENCE",
        *(f"LIFECYCLE_{name.upper()}" for name in LIFECYCLE_NAMES),
    }
    for row in normalized:
        kind = row.get("rowKind")
        if kind not in extras or set(row) != base_fields | extras[kind]:
            raise ValueError("authority row schema mismatch")
        if row.get("schemaVersion") != 1 or row.get("inventory") != "AUTHORITY":
            raise ValueError("authority row identity mismatch")
        for field in ("key", "sourceKey", "name", "provenance", "reachability", "recoveryDisposition", "disposition", "firstMissingBoundary"):
            text(f"authority row {field}", row.get(field))
        if row["provenance"] not in allowed_provenance or row["reachability"] not in allowed_reachability:
            raise ValueError("authority row provenance/reachability enum mismatch")
        if row["recoveryDisposition"] != "ORIGINAL_SERVER_LOST" or row["disposition"] not in allowed_disposition:
            raise ValueError("authority row recovery/disposition enum mismatch")
        if row["firstMissingBoundary"] not in allowed_boundaries:
            raise ValueError("authority row firstMissingBoundary enum mismatch")
        if row.get("states") != _states() or row.get("stateVerdicts") != _verdicts():
            raise ValueError("authority row state/verdict contradiction")
        if set(row.get("sections", {})) != AUTHORITY_SECTION_NAMES:
            raise ValueError("authority row section set mismatch")
        for section_name, section_value in row["sections"].items():
            child_fields = {"accepted", "rejected"} if section_name == "decision" and section_value.get("status") not in {"NOT_APPLICABLE_DIRECTION", "NOT_APPLICABLE_ROW_KIND"} else set()
            section(section_name, section_value, extra=child_fields)
            if child_fields:
                section("decision.accepted", section_value["accepted"])
                section("decision.rejected", section_value["rejected"])
        if set(row.get("implementationDisposition", {})) != set(IMPLEMENTATION_TARGETS):
            raise ValueError("authority implementation target set mismatch")
        for value in row["implementationDisposition"].values():
            if value.get("status") not in {"REQUIRED", "NOT_APPLICABLE"} or not value.get("reason"):
                raise ValueError("authority implementation disposition mismatch")
        for field in ("sourceCandidateIds", "legacyCandidates", "evidence"):
            text_list(f"authority row {field}", row.get(field))
        row_sources = set(row["sourceCandidateIds"])
        for section_value in row["sections"].values():
            if not set(section_value["sourceCandidateIds"]) <= row_sources:
                raise ValueError("authority section candidate assignment is not present on row")
        decision_value = row["sections"]["decision"]
        for child_name in ("accepted", "rejected"):
            if child_name in decision_value and not set(decision_value[child_name]["sourceCandidateIds"]) <= row_sources:
                raise ValueError("decision child candidate assignment is not present on row")
        if kind == "PROTOCOL_PATH":
            if row["direction"] not in {"CLIENT_TO_SERVER", "SERVER_TO_CLIENT", "BIDIRECTIONAL", "UNKNOWN"}:
                raise ValueError("protocol direction enum mismatch")
            if row["protocolRole"] not in {"REQUEST_PATH", "SERVER_OUTPUT", "UNCLASSIFIED_SERVER_INPUT"}:
                raise ValueError("protocol role enum mismatch")
        elif kind == "ENTITY_PATH":
            text("entityType", row["entityType"])
            section("authorityOwner", row["authorityOwner"])
            if not set(row["authorityOwner"]["sourceCandidateIds"]) <= row_sources:
                raise ValueError("authority owner candidate assignment mismatch")
            if set(row["entityLifecycle"]) != set(LIFECYCLE_NAMES):
                raise ValueError("entity lifecycle schema mismatch")
            for lifecycle_name, lifecycle_value in row["entityLifecycle"].items():
                section(lifecycle_name, lifecycle_value, extra={"requirementCandidateId"})
                text(f"{lifecycle_name}.requirementCandidateId", lifecycle_value["requirementCandidateId"])
                if lifecycle_value["requirementCandidateId"] not in row_sources:
                    raise ValueError("entity lifecycle requirement assignment mismatch")
                if not set(lifecycle_value["sourceCandidateIds"]) <= row_sources:
                    raise ValueError("entity lifecycle marker assignment mismatch")
        elif kind == "EVENT_PATH":
            section("emissionIdentity", row["emissionIdentity"])
            if not set(row["emissionIdentity"]["sourceCandidateIds"]) <= row_sources:
                raise ValueError("emission identity candidate assignment mismatch")
        else:
            text("interactionKind", row["interactionKind"])
            counterpart = row["authorityCounterpart"]
            expected_counterpart = {"status", "reason", "sourceCandidateIds", "dispositionKind", "counterpartKey"}
            if not isinstance(counterpart, Mapping) or set(counterpart) != expected_counterpart:
                raise ValueError("authority counterpart schema mismatch")
            if counterpart["status"] not in valid_statuses | {"UNRESOLVED"}:
                raise ValueError("authority counterpart status mismatch")
            text("authorityCounterpart.reason", counterpart["reason"])
            text_list("authorityCounterpart.sourceCandidateIds", counterpart["sourceCandidateIds"])
            if not set(counterpart["sourceCandidateIds"]) <= row_sources:
                raise ValueError("authority counterpart candidate assignment mismatch")
            if counterpart["dispositionKind"] not in {"UNRESOLVED", "AUTHORITY_COUNTERPART", "CLIENT_ONLY_DISPOSITION"}:
                raise ValueError("authority counterpart disposition schema mismatch")
            if counterpart["dispositionKind"] == "AUTHORITY_COUNTERPART":
                text("authorityCounterpart.counterpartKey", counterpart["counterpartKey"])
            elif counterpart["counterpartKey"] is not None:
                raise ValueError("authority counterpart key must be null for non-counterpart disposition")
    return sorted(normalized, key=lambda row: row["key"])


def build_authority_reconciliation(raw: Mapping[str, Any], rows: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    _validate_raw(raw)
    normalized_rows = normalize_authority_inventory(rows)
    raw_ids = {item["candidateId"] for collection in ("sourceRoots", "sourceFileCandidates", "traceMarkerCandidates", "requirementCandidates") for item in raw[collection]}
    assignments: dict[str, list[str]] = {}
    for row in normalized_rows:
        for cid in [*row.get("sourceCandidateIds", []), *row.get("legacyCandidates", [])]:
            if cid not in raw_ids:
                raise ValueError(f"fabricated candidate assignment: {cid}")
            assignments.setdefault(cid, []).append(row["key"])
    multiply = {cid: keys for cid, keys in assignments.items() if len(keys) != 1}
    if multiply:
        raise ValueError(f"candidate assigned to multiple rows: {sorted(multiply)}")
    represented = set(assignments)
    legacy_represented = {cid for row in normalized_rows for cid in row.get("legacyCandidates", [])}
    target_keys = {row["key"] for row in normalized_rows}
    for requirement in raw["requirementCandidates"]:
        if len(assignments.get(requirement["candidateId"], [])) != 1:
            raise ValueError("requirement candidate assignment is not exact-once")
    for marker in raw["traceMarkerCandidates"]:
        is_known = marker["target"] in target_keys and marker["role"] in KNOWN_ROLES
        assignment_count = len(assignments.get(marker["candidateId"], []))
        if is_known and assignment_count != 1:
            raise ValueError("known trace marker assignment is not exact-once")
        if not is_known and assignment_count != 0:
            raise ValueError("unknown trace marker was assigned to a row")
    records: list[dict[str, Any]] = []
    marker_by_file: dict[str, list[Mapping[str, Any]]] = {}
    for marker in raw["traceMarkerCandidates"]:
        marker_by_file.setdefault(marker["sourceFileCandidateId"], []).append(marker)
    for collection in ("sourceRoots", "sourceFileCandidates", "traceMarkerCandidates", "requirementCandidates"):
        for item in raw[collection]:
            cid = item["candidateId"]
            status, boundary = "NORMALIZED", None
            if collection == "sourceRoots" and item["fileCount"] == 0:
                status, boundary = "UNRESOLVED", "NO_SOURCE_FILES"
            elif collection == "sourceFileCandidates":
                attached = marker_by_file.get(cid, [])
                if not attached or not any(m["candidateId"] in represented | legacy_represented for m in attached):
                    status, boundary = "UNRESOLVED", "TRACE_MARKER"
            elif collection == "traceMarkerCandidates":
                valid = item["target"] in target_keys and item["role"] in KNOWN_ROLES
                if not valid:
                    status, boundary = "UNRESOLVED", "TRACE_TARGET_OR_ROLE"
                elif item["status"] == "STUB":
                    status, boundary = "UNRESOLVED", "IMPLEMENTATION_STUB"
            elif collection == "requirementCandidates" and cid not in represented:
                status, boundary = "UNRESOLVED", "AUTHORITY_ROW_JOIN"
            record = {"collection": collection, "candidateId": cid, "status": status}
            if boundary: record["firstMissingBoundary"] = boundary
            records.append(record)
    records.sort(key=lambda x: (x["collection"], x["candidateId"]))
    return {
        "schemaVersion": 1, "sourceSurfaceSha256": raw["surfaceSha256"],
        "inventorySha256": _sha(normalized_rows), "records": records,
        "normalizedCount": sum(r["status"] == "NORMALIZED" for r in records),
        "unresolvedCount": sum(r["status"] == "UNRESOLVED" for r in records),
        "unaccountedCount": len(raw_ids - {record["candidateId"] for record in records}),
    }


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(canonical_json(value), encoding="utf-8", newline="\n")


def _write_jsonl(path: Path, rows: Sequence[Mapping[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = "".join(canonical_json(row) for row in rows)
    path.write_text(text, encoding="utf-8", newline="\n")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", required=True, type=Path)
    parser.add_argument("--contracts", required=True, type=Path)
    parser.add_argument("--db", required=True, type=Path)
    parser.add_argument("--protocol-inventory", required=True, type=Path)
    parser.add_argument("--entity-inventory", required=True, type=Path)
    parser.add_argument("--ui-inventory", required=True, type=Path)
    parser.add_argument("--raw-output", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--reconciliation", required=True, type=Path)
    parser.add_argument("--legacy-candidate-root", type=Path)
    args = parser.parse_args(argv)
    raw = build_authority_source(
        server_root=args.server, contracts_root=args.contracts, database_root=args.db,
        protocol_inventory=args.protocol_inventory, entity_inventory=args.entity_inventory,
        ui_inventory=args.ui_inventory, legacy_candidate_root=args.legacy_candidate_root,
    )
    rows = build_authority_inventory(raw)
    reconciliation = build_authority_reconciliation(raw, rows)
    _write_json(args.raw_output, raw); _write_jsonl(args.output, rows); _write_json(args.reconciliation, reconciliation)
    print(f"authority rows={len(rows)} raw={raw['surfaceSha256']} reconciliation={reconciliation['inventorySha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
