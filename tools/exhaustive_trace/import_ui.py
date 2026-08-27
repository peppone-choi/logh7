"""Fail-closed normalization for the original client's static UI surface."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from types import MappingProxyType
from typing import Any, Iterable, Mapping

from tools.exhaustive_trace.model import (
    EvidenceState,
    InventoryKind,
    InventoryRow,
    Reachability,
    RecoveryDisposition,
)
from tools.exhaustive_trace.io import canonical_json, sha256_file
from tools.exhaustive_trace.source_manifest import CLIENT_SHA256, SourceManifest


class UiStringEnum(str, Enum):
    pass


class UiRowKind(UiStringEnum):
    MODE_ROOT = "MODE_ROOT"
    MANAGER_ROOT = "MANAGER_ROOT"
    WIDGET = "WIDGET"
    MENU_ROW = "MENU_ROW"
    DISPLAY_LABEL = "DISPLAY_LABEL"
    UNKNOWN = "UNKNOWN"


class InteractionKind(UiStringEnum):
    INTERACTIVE = "INTERACTIVE"
    DISPLAY_ONLY = "DISPLAY_ONLY"
    CONTAINER = "CONTAINER"
    UNKNOWN = "UNKNOWN"


class UiDisposition(UiStringEnum):
    PROVEN = "PROVEN"
    PROVEN_ABSENT = "PROVEN_ABSENT"
    NOT_APPLICABLE = "NOT_APPLICABLE"
    SOURCE_CONFLICT = "SOURCE_CONFLICT"
    UNKNOWN = "UNKNOWN"


class UiLabelStatus(UiStringEnum):
    BOUND_CONSUMER = "BOUND_CONSUMER"
    CANDIDATE = "CANDIDATE"
    PROVEN_UNLABELED = "PROVEN_UNLABELED"
    SOURCE_CONFLICT = "SOURCE_CONFLICT"
    UNKNOWN = "UNKNOWN"


class UiStateStatus(UiStringEnum):
    CANDIDATE = "CANDIDATE"
    WRITER_PROVEN = "WRITER_PROVEN"
    CONSTANT_ENABLED = "CONSTANT_ENABLED"
    CONSTANT_DISABLED = "CONSTANT_DISABLED"
    PROVEN_ABSENT = "PROVEN_ABSENT"
    NOT_APPLICABLE = "NOT_APPLICABLE"
    UNKNOWN = "UNKNOWN"


class UiChildStatus(UiStringEnum):
    OBSERVED = "OBSERVED"
    PROVEN_NONE = "PROVEN_NONE"
    NOT_APPLICABLE = "NOT_APPLICABLE"
    UNKNOWN = "UNKNOWN"


class UiEventNamespace(UiStringEnum):
    WIN32 = "WIN32"
    INTERNAL_WIDGET = "INTERNAL_WIDGET"
    MENU_ACTION = "MENU_ACTION"
    MOUSE = "MOUSE"
    KEYBOARD = "KEYBOARD"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True)
class UiSection:
    status: UiStringEnum
    values: Mapping[str, Any]

    def __post_init__(self) -> None:
        object.__setattr__(self, "values", MappingProxyType(dict(self.values)))


@dataclass(frozen=True)
class UiInventoryRow:
    row: InventoryRow
    row_kind: UiRowKind
    interaction_kind: InteractionKind
    identity: Mapping[str, Any]
    builder: UiSection
    label: UiSection
    event: UiSection
    handler: UiSection
    enablement: UiSection
    visibility: UiSection
    child_managers: UiSection
    recovery_disposition: RecoveryDisposition
    reachability_evidence: tuple[str, ...]
    evidence: tuple[str, ...]
    source_candidate_ids: tuple[str, ...]

    def __post_init__(self) -> None:
        object.__setattr__(self, "identity", MappingProxyType(dict(self.identity)))


@dataclass(frozen=True)
class UiEvidenceManifest:
    path: Path
    raw_path: Path
    raw_sha256: str
    exporter_path: Path
    exporter_sha256: str
    repository_sha256: str


TOP_LEVEL_FIELDS = frozenset(
    {
        "schemaVersion",
        "source",
        "exporter",
        "surfaceSha256",
        "successMarker",
        "rootModes",
        "managerConstructions",
        "managerLookupCandidates",
        "widgetConstructions",
        "menuRows",
        "descriptorLoaderCandidates",
        "labelCandidates",
        "eventCandidates",
        "handlerCandidates",
        "enablementCandidates",
        "visibilityCandidates",
        "childManagerCandidates",
        "inputSourceCandidates",
        "renderCandidates",
    }
)

CANDIDATE_COLLECTIONS = (
    "rootModes",
    "managerConstructions",
    "managerLookupCandidates",
    "widgetConstructions",
    "menuRows",
    "descriptorLoaderCandidates",
    "labelCandidates",
    "eventCandidates",
    "handlerCandidates",
    "enablementCandidates",
    "visibilityCandidates",
    "childManagerCandidates",
    "inputSourceCandidates",
    "renderCandidates",
)

FUNCTION_PATTERN = re.compile(r"^(?:FUN_)?[0-9A-Fa-f]{8}$")
ADDRESS_PATTERN = re.compile(r"^(?:0x)?[0-9A-Fa-f]{8}$")
SHA256_PATTERN = re.compile(r"^[0-9A-Fa-f]{64}$")
EXPECTED_UI_LANGUAGE = "x86:LE:32:default"
EXPECTED_UI_COMPILER = "windows"
EXPECTED_UI_IMAGE_BASE = "00400000"


def _mapping(name: str, value: object) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{name} must be an object")
    return value


def _text(name: str, value: object) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be non-empty text")
    return value.strip()


def _text_list(name: str, value: object, *, allow_empty: bool = True) -> tuple[str, ...]:
    if not isinstance(value, list) or any(not isinstance(item, str) or not item.strip() for item in value):
        raise ValueError(f"{name} must be a text list")
    if not allow_empty and not value:
        raise ValueError(f"{name} must not be empty")
    return tuple(item.strip() for item in value)


def _evidence(name: str, value: object) -> tuple[str, ...]:
    return _text_list(name, value, allow_empty=False)


def _sha256(name: str, value: object) -> str:
    text = _text(name, value).upper()
    if not SHA256_PATTERN.fullmatch(text):
        raise ValueError(f"{name} must be a SHA-256")
    return text


def load_ui_evidence_manifest(path: str | Path) -> UiEvidenceManifest:
    manifest_path = Path(path).resolve()
    payload = _mapping(
        "UI evidence manifest",
        json.loads(manifest_path.read_text(encoding="utf-8")),
    )
    if payload.get("schemaVersion") != 1:
        raise ValueError("unsupported UI evidence manifest schemaVersion")
    if _sha256("clientSha256", payload.get("clientSha256")) != CLIENT_SHA256:
        raise ValueError("UI evidence manifest is bound to a different client")
    repository_sha = _sha256(
        "ghidraRepositorySha256", payload.get("ghidraRepositorySha256")
    )

    def bound_file(label: str) -> tuple[Path, str]:
        record = _mapping(label, payload.get(label))
        file_path = Path(_text(f"{label}.path", record.get("path")))
        if not file_path.is_absolute():
            file_path = Path.cwd() / file_path
        file_path = file_path.resolve()
        expected = _sha256(f"{label}.sha256", record.get("sha256"))
        if not file_path.is_file():
            raise ValueError(f"{label} file is missing: {file_path}")
        actual = sha256_file(file_path)
        if actual != expected:
            raise ValueError(f"{label} hash mismatch: expected {expected}, got {actual}")
        return file_path, expected

    raw_path, raw_sha = bound_file("raw")
    exporter_path, exporter_sha = bound_file("exporter")
    return UiEvidenceManifest(
        manifest_path,
        raw_path,
        raw_sha,
        exporter_path,
        exporter_sha,
        repository_sha,
    )


def _canonical_small_id(name: str, value: object, site: str) -> str:
    if value is None:
        return f"UNKNOWN@{site.removeprefix('0x').upper()}"
    if not isinstance(value, str):
        raise ValueError(f"{name} must be a hexadecimal ID")
    raw = value.strip()
    if raw.upper().startswith("UNKNOWN@"):
        return raw.upper()
    try:
        parsed = int(raw, 16)
    except ValueError as error:
        raise ValueError(f"{name} must be a hexadecimal ID") from error
    if not 0 <= parsed <= 0xFF:
        raise ValueError(f"{name} is outside the stable one-byte namespace")
    return f"0x{parsed:02X}"


def _canonical_index(value: object, site: str) -> str:
    if value is None:
        return f"UNKNOWN@{site.removeprefix('0x').upper()}"
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise ValueError("widget index must be a non-negative integer")
    return f"{value:04d}"


def _validate_export(
    raw: Mapping[str, Any],
    *,
    expected_exporter_sha256: str | None = None,
    expected_repository_sha256: str | None = None,
    expected_message_data_sha256: str | None = None,
) -> None:
    unknown = set(raw) - TOP_LEVEL_FIELDS
    missing = TOP_LEVEL_FIELDS - set(raw)
    if unknown or missing:
        raise ValueError(f"UI export top-level fields differ: unknown={sorted(unknown)} missing={sorted(missing)}")
    if raw.get("schemaVersion") != 1:
        raise ValueError("unsupported UI export schemaVersion")
    if raw.get("successMarker") != "EXPORT_EXHAUSTIVE_UI_OK":
        raise ValueError("UI export success marker is missing")
    source = _mapping("source", raw.get("source"))
    if source.get("program") != "g7mtclient.exe":
        raise ValueError("UI source program mismatch")
    if _sha256("source.executableSha256", source.get("executableSha256")) != CLIENT_SHA256:
        raise ValueError("UI source executable hash mismatch")
    if source.get("language") != EXPECTED_UI_LANGUAGE:
        raise ValueError("UI source language mismatch")
    if source.get("compiler") != EXPECTED_UI_COMPILER:
        raise ValueError("UI source compiler mismatch")
    if source.get("imageBase") != EXPECTED_UI_IMAGE_BASE:
        raise ValueError("UI source image base mismatch")
    message_data_sha = _sha256(
        "source.messageDataSha256", source.get("messageDataSha256")
    )
    if expected_message_data_sha256 is not None and message_data_sha != expected_message_data_sha256.upper():
        raise ValueError("UI raw message data hash differs from source manifest")
    exporter = _mapping("exporter", raw.get("exporter"))
    if exporter.get("class") != "ExportExhaustiveUi":
        raise ValueError("UI exporter class mismatch")
    exporter_sha = _sha256("exporter.sha256", exporter.get("sha256"))
    repository_sha = _sha256(
        "exporter.ghidraRepositorySha256", exporter.get("ghidraRepositorySha256")
    )
    if expected_exporter_sha256 is not None and exporter_sha != expected_exporter_sha256.upper():
        raise ValueError("UI raw exporter hash differs from evidence manifest")
    if expected_repository_sha256 is not None and repository_sha != expected_repository_sha256.upper():
        raise ValueError("UI raw Ghidra repository hash differs from source manifest")
    _text("surfaceSha256", raw.get("surfaceSha256"))
    seen: set[str] = set()
    for collection in CANDIDATE_COLLECTIONS:
        items = raw.get(collection)
        if not isinstance(items, list):
            raise ValueError(f"{collection} must be a list")
        for index, item_value in enumerate(items):
            item = _mapping(f"{collection}[{index}]", item_value)
            candidate_id = _text(f"{collection}[{index}].candidateId", item.get("candidateId"))
            if candidate_id in seen:
                raise ValueError(f"duplicate UI candidateId: {candidate_id}")
            seen.add(candidate_id)


def _label_section(value: object) -> UiSection:
    item = _mapping("label", value)
    status = UiLabelStatus(_text("label.status", item.get("status")))
    evidence = _evidence("label.evidence", item.get("evidence"))
    text = item.get("text")
    source = item.get("source")
    consumers = _text_list("label.consumerFunctions", item.get("consumerFunctions"))
    if status is UiLabelStatus.BOUND_CONSUMER:
        _text("label.text", text)
        _text("label.source", source)
        if not consumers:
            raise ValueError("bound label requires a consumer function")
    elif status is UiLabelStatus.UNKNOWN and (text is not None or source is not None or consumers):
        raise ValueError("unknown label cannot assert text, source, or consumer")
    return UiSection(status, {"text": text, "source": source, "consumerFunctions": consumers, "evidence": evidence})


def _event_section(value: object) -> UiSection:
    item = _mapping("event", value)
    status = UiDisposition(_text("event.status", item.get("status")))
    namespace = UiEventNamespace(_text("event.namespace", item.get("namespace")))
    types = _text_list("event.types", item.get("types"))
    predicates = _text_list("event.predicates", item.get("predicates"))
    evidence = _evidence("event.evidence", item.get("evidence"))
    if status is UiDisposition.UNKNOWN and (
        namespace is not UiEventNamespace.UNKNOWN or types or predicates
    ):
        raise ValueError("unknown event cannot claim namespace, types, or predicates")
    if status is UiDisposition.PROVEN and (
        namespace is UiEventNamespace.UNKNOWN or not types or not predicates
    ):
        raise ValueError("proven event requires namespace, types, and predicates")
    return UiSection(status, {"namespace": namespace, "types": types, "predicates": predicates, "evidence": evidence})


def _handler_section(value: object) -> UiSection:
    item = _mapping("handler", value)
    status = UiDisposition(_text("handler.status", item.get("status")))
    functions = _text_list("handler.functions", item.get("functions"))
    reason = item.get("reason")
    evidence = _evidence("handler.evidence", item.get("evidence"))
    if status is UiDisposition.PROVEN and not functions:
        raise ValueError("proven handler requires functions")
    if status is UiDisposition.UNKNOWN and functions:
        raise ValueError("unknown handler cannot claim functions")
    if status is UiDisposition.PROVEN_ABSENT:
        if functions:
            raise ValueError("absent handler cannot claim functions")
        _text("handler.reason", reason)
    if status is UiDisposition.NOT_APPLICABLE:
        _text("handler.reason", reason)
    return UiSection(status, {"functions": functions, "reason": reason, "evidence": evidence})


def _state_section(name: str, value: object) -> UiSection:
    item = _mapping(name, value)
    status = UiStateStatus(_text(f"{name}.status", item.get("status")))
    fields = _text_list(f"{name}.stateFields", item.get("stateFields"))
    writers = _text_list(f"{name}.writers", item.get("writers"))
    predicates = _text_list(f"{name}.predicates", item.get("predicates"))
    reason = item.get("reason")
    evidence = _evidence(f"{name}.evidence", item.get("evidence"))
    if status is UiStateStatus.WRITER_PROVEN and (not fields or not writers):
        raise ValueError(f"{name} writer proof requires state fields and writers")
    if status is UiStateStatus.CANDIDATE and not (fields or writers or predicates):
        raise ValueError(f"{name} candidate requires a candidate field, writer, or predicate")
    if status in {UiStateStatus.UNKNOWN, UiStateStatus.PROVEN_ABSENT} and (
        fields or writers or predicates
    ):
        raise ValueError(f"{name} unknown or absent state cannot claim fields, writers, or predicates")
    if status is UiStateStatus.NOT_APPLICABLE:
        _text(f"{name}.reason", reason)
    return UiSection(status, {"stateFields": fields, "writers": writers, "predicates": predicates, "reason": reason, "evidence": evidence})


def _child_section(value: object) -> UiSection:
    item = _mapping("childManagers", value)
    status = UiChildStatus(_text("childManagers.status", item.get("status")))
    targets = _text_list("childManagers.targetKeys", item.get("targetKeys"))
    reason = item.get("reason")
    evidence = _evidence("childManagers.evidence", item.get("evidence"))
    if status is UiChildStatus.OBSERVED and not targets:
        raise ValueError("observed child-manager relation requires targets")
    if status in {UiChildStatus.UNKNOWN, UiChildStatus.PROVEN_NONE} and targets:
        raise ValueError("unknown child-manager relation cannot claim targets")
    if status is UiChildStatus.PROVEN_NONE:
        _text("childManagers.reason", reason)
    if status is UiChildStatus.NOT_APPLICABLE:
        _text("childManagers.reason", reason)
    return UiSection(status, {"targetKeys": targets, "reason": reason, "evidence": evidence})


def _build_widget_rows(
    candidate: Mapping[str, Any], row_kind: UiRowKind = UiRowKind.WIDGET
) -> list[UiInventoryRow]:
    candidate_id = _text("widget candidateId", candidate.get("candidateId"))
    site = _text("widget constructionSite", candidate.get("constructionSite"))
    if not ADDRESS_PATTERN.match(site):
        raise ValueError("constructionSite must be a static address")
    builder_function = _text("widget builderFunction", candidate.get("builderFunction"))
    if not FUNCTION_PATTERN.match(builder_function):
        raise ValueError("builderFunction must be a static function")
    constructor = _text("widget constructor", candidate.get("constructor"))
    if not FUNCTION_PATTERN.match(constructor):
        raise ValueError("constructor must be a static function")
    modes = candidate.get("modes")
    managers = candidate.get("managerIds")
    if not isinstance(modes, list) or not isinstance(managers, list):
        raise ValueError("widget modes and manager IDs must be lists")
    mode_tokens = [_canonical_small_id("mode ID", value, site) for value in modes] or [_canonical_small_id("mode ID", None, site)]
    manager_tokens = [_canonical_small_id("manager ID", value, site) for value in managers] or [_canonical_small_id("manager ID", None, site)]
    category = _canonical_small_id("category ID", candidate.get("category"), site)
    index = _canonical_index(candidate.get("index"), site)
    menu_row = None
    if row_kind is UiRowKind.MENU_ROW:
        menu_row = _canonical_index(candidate.get("row"), site)
    label = _label_section(candidate.get("label"))
    event = _event_section(candidate.get("event"))
    handler = _handler_section(candidate.get("handler"))
    enablement = _state_section("enablement", candidate.get("enablement"))
    visibility = _state_section("visibility", candidate.get("visibility"))
    child_managers = _child_section(candidate.get("childManagers"))
    interaction = InteractionKind(str(candidate.get("interactionKind", "UNKNOWN")))
    reachability = Reachability(_text("reachability", candidate.get("reachability")))
    reachability_evidence = _evidence("reachabilityEvidence", candidate.get("reachabilityEvidence"))
    evidence = _evidence("widget evidence", candidate.get("evidence"))
    if reachability is Reachability.SHIPPED_REACHABLE:
        if not any(item.startswith("callpath:") for item in reachability_evidence):
            raise ValueError("reachable UI row requires callpath evidence")
        proven_state = {UiStateStatus.WRITER_PROVEN, UiStateStatus.CONSTANT_ENABLED}
        if interaction is InteractionKind.INTERACTIVE:
            if handler.status is not UiDisposition.PROVEN:
                raise ValueError("reachable interactive UI row requires a proven handler")
            if (
                event.status is not UiDisposition.PROVEN
                or enablement.status not in proven_state
                or visibility.status not in proven_state
            ):
                raise ValueError(
                    "reachable interactive UI row requires event, enablement, and visibility proof"
                )
        elif interaction is InteractionKind.DISPLAY_ONLY:
            if (
                handler.status is not UiDisposition.NOT_APPLICABLE
                or enablement.status not in proven_state | {UiStateStatus.NOT_APPLICABLE}
                or visibility.status not in proven_state
            ):
                raise ValueError(
                    "reachable display-only UI row requires explicit handler exception and draw eligibility"
                )
        else:
            raise ValueError("reachable UI row requires a classified interaction kind")
    if reachability is Reachability.SHIPPED_DORMANT:
        dormant_proof = (
            handler.status is UiDisposition.PROVEN_ABSENT
            or enablement.status is UiStateStatus.CONSTANT_DISABLED
        )
        if not dormant_proof:
            raise ValueError("dormant UI row requires permanent-disable or absent-handler proof")
    rows: list[UiInventoryRow] = []
    for mode in mode_tokens:
        for manager in manager_tokens:
            key = f"UI:MODE:{mode}:MANAGER:{manager}:CATEGORY:{category}:INDEX:{index}"
            if menu_row is not None:
                key += f":ROW:{menu_row}"
            states = {state: state is EvidenceState.ENUMERATED for state in EvidenceState}
            row = InventoryRow(
                key=key,
                inventory=InventoryKind.UI,
                name=f"Mode{mode}_Manager{manager}_Category{category}_Index{index}" +
                    (f"_Row{menu_row}" if menu_row is not None else ""),
                provenance="ORIGINAL_OBSERVED",
                reachability=reachability,
                states=states,
            )
            rows.append(
                UiInventoryRow(
                    row=row,
                    row_kind=row_kind,
                    interaction_kind=interaction,
                    identity={
                        "mode": mode,
                        "manager": manager,
                        "category": category,
                        "index": index,
                        **({"row": menu_row} if menu_row is not None else {}),
                    },
                    builder=UiSection(
                        UiDisposition.PROVEN,
                        {
                            "functions": (builder_function,),
                            "constructionSites": (site,),
                            "constructor": constructor,
                            "evidence": evidence,
                        },
                    ),
                    label=label,
                    event=event,
                    handler=handler,
                    enablement=enablement,
                    visibility=visibility,
                    child_managers=child_managers,
                    recovery_disposition=RecoveryDisposition.RECOVERABLE_STATIC,
                    reachability_evidence=reachability_evidence,
                    evidence=evidence,
                    source_candidate_ids=(candidate_id,),
                )
            )
    return rows


def _unknown_section(status: UiStringEnum, evidence: tuple[str, ...], *, reason: str | None = None) -> UiSection:
    values: dict[str, Any] = {"evidence": evidence}
    if isinstance(status, UiLabelStatus):
        values.update({"text": None, "source": None, "consumerFunctions": ()})
    elif isinstance(status, UiDisposition):
        values.update({"functions": (), "reason": reason})
    elif isinstance(status, UiStateStatus):
        values.update({"stateFields": (), "writers": (), "predicates": (), "reason": reason})
    elif isinstance(status, UiChildStatus):
        values.update({"targetKeys": (), "reason": reason})
    return UiSection(status, values)


def _structural_row(
    *,
    key: str,
    name: str,
    kind: UiRowKind,
    identity: Mapping[str, Any],
    builder_function: str,
    construction_site: str,
    constructor: str,
    candidate_id: str,
    evidence: tuple[str, ...],
) -> UiInventoryRow:
    states = {state: state is EvidenceState.ENUMERATED for state in EvidenceState}
    row = InventoryRow(
        key=key,
        inventory=InventoryKind.UI,
        name=name,
        provenance="ORIGINAL_OBSERVED",
        reachability=Reachability.UNKNOWN,
        states=states,
    )
    return UiInventoryRow(
        row=row,
        row_kind=kind,
        interaction_kind=InteractionKind.CONTAINER,
        identity=identity,
        builder=UiSection(
            UiDisposition.PROVEN,
            {
                "functions": (builder_function,),
                "constructionSites": (construction_site,),
                "constructor": constructor,
                "evidence": evidence,
            },
        ),
        label=_unknown_section(UiLabelStatus.UNKNOWN, evidence),
        event=UiSection(
            UiDisposition.UNKNOWN,
            {"namespace": UiEventNamespace.UNKNOWN, "types": (), "predicates": (), "evidence": evidence},
        ),
        handler=_unknown_section(UiDisposition.UNKNOWN, evidence, reason="not yet joined"),
        enablement=_unknown_section(UiStateStatus.UNKNOWN, evidence),
        visibility=_unknown_section(UiStateStatus.UNKNOWN, evidence),
        child_managers=_unknown_section(UiChildStatus.UNKNOWN, evidence, reason="not yet joined"),
        recovery_disposition=RecoveryDisposition.RECOVERABLE_STATIC,
        reachability_evidence=evidence,
        evidence=evidence,
        source_candidate_ids=(candidate_id,),
    )


def _build_root_mode_row(candidate: Mapping[str, Any]) -> UiInventoryRow:
    candidate_id = _text("root mode candidateId", candidate.get("candidateId"))
    mode = _canonical_small_id("mode ID", candidate.get("mode"), "ROOT")
    dispatch = _text("root dispatchFunction", candidate.get("dispatchFunction"))
    builder = _text("root builderFunction", candidate.get("builderFunction"))
    site = _text("root branchCallsite", candidate.get("branchCallsite"))
    evidence = _evidence("root mode evidence", candidate.get("evidence"))
    return _structural_row(
        key=f"UI:MODE:{mode}:MANAGER:ROOT:CATEGORY:MODE_ROOT:INDEX:0000",
        name=f"Mode{mode}_Root",
        kind=UiRowKind.MODE_ROOT,
        identity={"mode": mode, "manager": "ROOT", "category": "MODE_ROOT", "index": "0000"},
        builder_function=builder,
        construction_site=site,
        constructor=dispatch,
        candidate_id=candidate_id,
        evidence=evidence,
    )


def _build_manager_rows(candidate: Mapping[str, Any]) -> list[UiInventoryRow]:
    candidate_id = _text("manager candidateId", candidate.get("candidateId"))
    site = _text("manager constructionSite", candidate.get("constructionSite"))
    builder = _text("manager builderFunction", candidate.get("builderFunction"))
    constructor = _text("manager constructor", candidate.get("constructor"))
    evidence = _evidence("manager evidence", candidate.get("evidence"))
    modes = candidate.get("modes")
    if not isinstance(modes, list):
        raise ValueError("manager modes must be a list")
    mode_tokens = [_canonical_small_id("mode ID", value, site) for value in modes] or [_canonical_small_id("mode ID", None, site)]
    manager = _canonical_small_id("manager ID", candidate.get("managerId"), site)
    return [
        _structural_row(
            key=f"UI:MODE:{mode}:MANAGER:{manager}:CATEGORY:MANAGER_ROOT:INDEX:0000",
            name=f"Mode{mode}_Manager{manager}_Root",
            kind=UiRowKind.MANAGER_ROOT,
            identity={"mode": mode, "manager": manager, "category": "MANAGER_ROOT", "index": "0000"},
            builder_function=builder,
            construction_site=site,
            constructor=constructor,
            candidate_id=candidate_id,
            evidence=evidence,
        )
        for mode in mode_tokens
    ]


def build_ui_inventory(
    raw: Mapping[str, Any],
    *,
    expected_exporter_sha256: str | None = None,
    expected_repository_sha256: str | None = None,
    expected_message_data_sha256: str | None = None,
) -> list[UiInventoryRow]:
    _validate_export(
        raw,
        expected_exporter_sha256=expected_exporter_sha256,
        expected_repository_sha256=expected_repository_sha256,
        expected_message_data_sha256=expected_message_data_sha256,
    )
    rows: list[UiInventoryRow] = [
        _build_root_mode_row(_mapping("root mode", candidate))
        for candidate in raw["rootModes"]
    ]
    for candidate_value in raw["managerConstructions"]:
        candidate = _mapping("manager construction", candidate_value)
        if candidate.get("status") != "EXCLUDED":
            rows.extend(_build_manager_rows(candidate))
    for candidate_value in raw["widgetConstructions"]:
        candidate = _mapping("widget construction", candidate_value)
        if candidate.get("status") != "EXCLUDED":
            rows.extend(_build_widget_rows(candidate))
    for candidate_value in raw["menuRows"]:
        candidate = _mapping("menu row", candidate_value)
        if candidate.get("status") != "EXCLUDED":
            rows.extend(_build_widget_rows(candidate, UiRowKind.MENU_ROW))
    rows.sort(key=lambda item: item.row.key)
    keys = [item.row.key for item in rows]
    if len(keys) != len(set(keys)):
        raise ValueError("duplicate canonical UI tuple")
    known_keys = set(keys)
    for row in rows:
        for target in row.child_managers.values["targetKeys"]:
            if target not in known_keys:
                raise ValueError(f"dangling child-manager target: {target}")
    return rows


def _json_value(value: Any) -> Any:
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, Mapping):
        return {key: _json_value(item) for key, item in sorted(value.items())}
    if isinstance(value, tuple):
        return [_json_value(item) for item in value]
    return value


def ui_row_to_dict(item: UiInventoryRow) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "key": item.row.key,
        "inventory": item.row.inventory.value,
        "name": item.row.name,
        "rowKind": item.row_kind.value,
        "interactionKind": item.interaction_kind.value,
        "identity": _json_value(item.identity),
        "provenance": item.row.provenance,
        "reachability": item.row.reachability.value,
        "reachabilityEvidence": list(item.reachability_evidence),
        "recoveryDisposition": item.recovery_disposition.value,
        "states": {state.value: item.row.states[state] for state in EvidenceState},
        "builder": {"status": item.builder.status.value, **_json_value(item.builder.values)},
        "label": {"status": item.label.status.value, **_json_value(item.label.values)},
        "event": {"status": item.event.status.value, **_json_value(item.event.values)},
        "handler": {"status": item.handler.status.value, **_json_value(item.handler.values)},
        "enablement": {"status": item.enablement.status.value, **_json_value(item.enablement.values)},
        "visibility": {"status": item.visibility.status.value, **_json_value(item.visibility.values)},
        "childManagers": {"status": item.child_managers.status.value, **_json_value(item.child_managers.values)},
        "evidence": list(item.evidence),
        "sourceCandidateIds": list(item.source_candidate_ids),
    }


def normalize_ui_inventory(rows: Iterable[UiInventoryRow]) -> list[dict[str, Any]]:
    return [ui_row_to_dict(row) for row in sorted(rows, key=lambda item: item.row.key)]


def build_ui_reconciliation(raw: Mapping[str, Any], rows: Iterable[UiInventoryRow]) -> dict[str, Any]:
    _validate_export(raw)
    normalized = {candidate_id for row in rows for candidate_id in row.source_candidate_ids}
    records: list[dict[str, Any]] = []
    normalized_count = unresolved_count = excluded_count = 0
    for collection in CANDIDATE_COLLECTIONS:
        for index, candidate_value in enumerate(raw[collection]):
            candidate = _mapping(f"{collection}[{index}]", candidate_value)
            candidate_id = _text("candidateId", candidate.get("candidateId"))
            if candidate_id in normalized:
                status = "NORMALIZED"
                normalized_count += 1
            elif candidate.get("status") == "EXCLUDED":
                _text("exclusionReason", candidate.get("exclusionReason"))
                status = "EXCLUDED"
                excluded_count += 1
            else:
                status = "UNRESOLVED"
                unresolved_count += 1
            records.append(
                {
                    "candidateId": candidate_id,
                    "collection": collection,
                    "index": index,
                    "status": status,
                }
            )
    candidate_count = len(records)
    accounted = normalized_count + unresolved_count + excluded_count
    return {
        "schemaVersion": 1,
        "candidateCount": candidate_count,
        "normalizedCount": normalized_count,
        "unresolvedCount": unresolved_count,
        "excludedCount": excluded_count,
        "unaccountedCount": candidate_count - accounted,
        "records": sorted(records, key=lambda item: item["candidateId"]),
    }


def _write_jsonl(path: Path, items: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = "".join(json.dumps(item, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n" for item in items)
    path.write_text(text, encoding="utf-8", newline="\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reconciliation", type=Path, required=True)
    parser.add_argument("--evidence-manifest", type=Path, required=True)
    parser.add_argument("--source-manifest", type=Path, required=True)
    args = parser.parse_args(argv)
    evidence_manifest = load_ui_evidence_manifest(args.evidence_manifest)
    if args.input.resolve() != evidence_manifest.raw_path:
        raise ValueError("--input differs from the hash-bound UI raw path")
    manifest_payload = json.loads(args.source_manifest.read_text(encoding="utf-8"))
    source_manifest = SourceManifest.load(args.source_manifest)
    repository_hash = _mapping("source manifest ghidra", manifest_payload.get("ghidra")).get(
        "repositorySha256"
    )
    repository_sha = _sha256("source manifest repository hash", repository_hash)
    message_data_sha = _sha256(
        "source manifest message data hash",
        _mapping("source manifest messageData", manifest_payload.get("messageData")).get("sha256"),
    )
    if evidence_manifest.repository_sha256 != repository_sha:
        raise ValueError("UI and source manifests bind different Ghidra repositories")
    raw = json.loads(args.input.read_text(encoding="utf-8"))
    rows = build_ui_inventory(
        raw,
        expected_exporter_sha256=evidence_manifest.exporter_sha256,
        expected_repository_sha256=repository_sha,
        expected_message_data_sha256=message_data_sha,
    )
    normalized = normalize_ui_inventory(rows)
    reconciliation = build_ui_reconciliation(raw, rows)
    if reconciliation["unaccountedCount"] != 0:
        raise ValueError("UI reconciliation left unaccounted candidates")
    _write_jsonl(args.output, normalized)
    args.reconciliation.parent.mkdir(parents=True, exist_ok=True)
    args.reconciliation.write_text(
        json.dumps(reconciliation, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(canonical_json({
        "status": "PASS",
        "rowCount": len(rows),
        "candidateCount": reconciliation["candidateCount"],
        "unresolvedCount": reconciliation["unresolvedCount"],
        "unaccountedCount": reconciliation["unaccountedCount"],
        "verifiedSourcePathCount": len(source_manifest.verified_paths),
    }), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
