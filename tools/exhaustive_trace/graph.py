"""Build and serialize the deterministic typed exhaustive-trace graph."""

from __future__ import annotations

import hashlib
import json
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

from .inventories import InventoryBundle
from .io import canonical_json
from .model import ALLOWED_PROVENANCE, TraceEdge, TraceNode, freeze_json


NODE_KINDS = frozenset(
    {
        "INVENTORY_ROW", "FIELD", "STATE_LOCATION", "AUTHORITY_COMPONENT", "EVENT",
        "DATABASE_TABLE", "VISIBLE_SURFACE", "NAME_TOKEN", "UNRESOLVED_REFERENCE",
        "STRING_LITERAL", "FUNCTION_MEMBER", "EXTERNAL_ARTIFACT",
    }
)
STRICT_EDGE_JOIN_BASIS = frozenset(
    {
        "DIRECT_TYPED_REFERENCE", "DIRECT_ADDRESS_REFERENCE", "EXPLICIT_REGISTRY_BINDING",
        "EXPLICIT_CONSUMER_BINDING", "CORROBORATED_TYPED_REFERENCE",
        "SINGLE_SOURCE_CANDIDATE", "NAME_EQUALITY", "UNRESOLVED_REFERENCE",
        "STRUCTURAL_OBLIGATION", "EXACT_SIBLING_CODE", "EXACT_FUNCTION_TOKEN",
    }
)
GRAPH_AUDIT = {
    "nameMatchIsIdentity": False,
    "candidateEdgesPromoteStates": False,
    "nearestFunctionFallback": False,
}


def _sha(value: Any) -> str:
    return hashlib.sha256(canonical_json(_plain(value)).encode("utf-8")).hexdigest().upper()


def _plain(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {key: _plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain(item) for item in value]
    return value


def _text_list(value: Iterable[str]) -> tuple[str, ...]:
    return tuple(sorted(set(value)))


def _enumerate_join_references(rows: Sequence[Mapping[str, Any]]) -> tuple[str, ...]:
    refs: set[str] = set()
    for row in rows:
        key = row["key"]
        if row["inventory"] == "FUNCTION" and row.get("rowKind") == "INDIVIDUAL_FUNCTION":
            for section, items in (
                ("callees/direct", row.get("callees", {}).get("direct", [])),
                ("callees/indirectCallsites", row.get("callees", {}).get("indirectCallsites", [])),
                ("callers/direct", row.get("callers", {}).get("direct", [])),
                ("globalStructureFields/reads", row.get("globalStructureFields", {}).get("reads", [])),
                ("globalStructureFields/writes", row.get("globalStructureFields", {}).get("writes", [])),
                (
                    "globalStructureFields/stringReferences",
                    row.get("globalStructureFields", {}).get("stringReferences", []),
                ),
                (
                    "classification/upstreamReferences",
                    row.get("classification", {}).get("upstreamReferences", []),
                ),
            ):
                refs.update(f"inventory:{key}/{section}/{index}" for index, _ in enumerate(items))
        elif row["inventory"] == "PROTOCOL":
            refs.update(
                f"inventory:{key}/relations/{index}"
                for index, _ in enumerate(row.get("relations", []))
            )
            for sibling_name in ("request", "response", "notify"):
                refs.update(
                    f"inventory:{key}/siblings/{sibling_name}/{index}"
                    for index, _ in enumerate(
                        row.get("siblings", {}).get(sibling_name, {}).get("codes", [])
                    )
                )
            refs.update(
                f"inventory:{key}/facts/{index}"
                for index, fact in enumerate(row.get("facts", []))
                if fact.get("kind") in {"DISPATCH_HELPER", "OUTBOUND_BINDER", "PARSER_HELPER"}
            )
            for role in ("parser", "dispatcher", "serializer"):
                refs.update(
                    f"inventory:{key}/ownership/{role}/functions/{index}"
                    for index, _ in enumerate(
                        row.get("ownership", {}).get(role, {}).get("functions", [])
                    )
                )
        elif row["inventory"] == "UI":
            refs.update(
                f"inventory:{key}/builder/functions/{index}"
                for index, _ in enumerate(row.get("builder", {}).get("functions", []))
            )
            if row.get("builder", {}).get("constructor"):
                refs.add(f"inventory:{key}/builder/constructor")
            for section, field_name in (
                ("handler", "functions"),
                ("enablement", "writers"),
                ("label", "consumerFunctions"),
                ("childManagers", "targetKeys"),
            ):
                refs.update(
                    f"inventory:{key}/{section}/{field_name}/{index}"
                    for index, _ in enumerate(row.get(section, {}).get(field_name, []))
                )
            for section in ("enablement", "visibility"):
                for field_name in ("stateFields", "predicates"):
                    refs.update(
                        f"inventory:{key}/{section}/{field_name}/{index}"
                        for index, _ in enumerate(row.get(section, {}).get(field_name, []))
                    )
            for field_name in ("types", "predicates"):
                refs.update(
                    f"inventory:{key}/event/{field_name}/{index}"
                    for index, _ in enumerate(row.get("event", {}).get(field_name, []))
                )
        elif row["inventory"] == "ENTITY":
            refs.update(
                f"inventory:{key}/layout/fields/{index}"
                for index, _ in enumerate((row.get("layout") or {}).get("fields", []))
            )
        elif row["inventory"] == "RESOURCE":
            refs.update(
                f"inventory:{key}/loader/functions/{index}"
                for index, _ in enumerate((row.get("loader") or {}).get("functions", []))
            )
            if (row.get("source") or {}).get("processLaunch"):
                refs.add(f"inventory:{key}/source/processLaunch")
            if (row.get("source") or {}).get("externalDocumentOpen"):
                refs.add(f"inventory:{key}/source/externalDocumentOpen")
            if (row.get("source") or {}).get("inboundLaunch"):
                refs.add(f"inventory:{key}/source/inboundLaunch")
        elif row["inventory"] == "AUTHORITY":
            refs.add(f"inventory:{key}/sourceKey")
    return tuple(sorted(refs))


def _node_record(node: TraceNode) -> dict[str, Any]:
    payload = {
        "recordType": "NODE",
        "key": node.key,
        "nodeKind": node.kind,
        "label": node.label,
        "provenance": node.provenance,
        "confidence": node.confidence,
        "disposition": node.disposition,
        "evidence": list(node.evidence),
        "sourceRefs": list(node.source_refs),
        "attributes": _plain(node.attributes),
    }
    payload["contentSha256"] = _sha(payload)
    return payload


def _edge_semantic(edge: TraceEdge) -> dict[str, Any]:
    return {
        "source": edge.source,
        "relation": edge.relation,
        "target": edge.target,
        "edgeClass": edge.edge_class,
        "disposition": edge.disposition,
        "provenance": edge.provenance,
        "confidence": edge.confidence,
        "joinBasis": edge.join_basis,
        "evidence": list(edge.evidence),
        "sourceRefs": list(edge.source_refs),
        "candidateId": edge.candidate_id,
    }


def _edge_record(edge: TraceEdge) -> dict[str, Any]:
    payload = {"recordType": "EDGE", **_edge_semantic(edge)}
    payload["edgeId"] = _sha(_edge_semantic(edge))
    return payload


@dataclass(frozen=True)
class TraceGraph:
    nodes: tuple[TraceNode, ...]
    edges: tuple[TraceEdge, ...]
    expected_source_keys: tuple[str, ...] = ()
    expected_join_references: tuple[str, ...] = ()
    source_rows: tuple[Mapping[str, Any], ...] = ()
    conservation: Mapping[str, Any] = field(init=False)
    nodes_sha256: str = field(init=False)
    edges_sha256: str = field(init=False)
    graph_surface_sha256: str = field(init=False)

    def __post_init__(self) -> None:
        nodes = tuple(sorted(tuple(self.nodes), key=lambda item: (item.key.casefold(), item.key)))
        edges = tuple(sorted(tuple(self.edges), key=lambda item: _edge_record(item)["edgeId"]))
        source_rows = tuple(freeze_json(_plain(row)) for row in self.source_rows)
        object.__setattr__(self, "nodes", nodes)
        object.__setattr__(self, "edges", edges)
        object.__setattr__(self, "source_rows", source_rows)
        keys: dict[str, str] = {}
        exact_keys: set[str] = set()
        for node in nodes:
            if node.kind not in NODE_KINDS:
                raise ValueError(f"unsupported graph node kind: {node.kind}")
            folded = node.key.casefold()
            if folded in keys:
                raise ValueError(f"duplicate graph node key: {keys[folded]} / {node.key}")
            keys[folded] = node.key
            exact_keys.add(node.key)
            if not node.source_refs:
                raise ValueError(f"graph node lacks sourceRefs: {node.key}")
            if node.confidence not in {"HIGH", "MEDIUM", "LOW", "UNKNOWN"}:
                raise ValueError(f"graph node confidence mismatch: {node.key}")
            if node.disposition not in {"PROVEN", "CANDIDATE", "UNRESOLVED", "SOURCE_CONFLICT"}:
                raise ValueError(f"graph node disposition mismatch: {node.key}")
        edge_ids: set[str] = set()
        candidate_ids: dict[str, str] = {}
        relation_counts: dict[str, int] = {}
        disposition_counts: dict[str, int] = {}
        join_reference_dispositions: dict[str, list[str]] = {}
        expected_join_refs = set(self.expected_join_references)
        for edge in edges:
            if edge.source not in exact_keys or edge.target not in exact_keys:
                raise ValueError(f"dangling graph edge: {edge.source} -{edge.relation}-> {edge.target}")
            if not edge.source_refs or not edge.candidate_id:
                raise ValueError("graph edge requires sourceRefs and candidateId")
            folded_candidate = edge.candidate_id.casefold()
            if folded_candidate in candidate_ids:
                raise ValueError(
                    f"duplicate graph candidateId: {candidate_ids[folded_candidate]} / {edge.candidate_id}"
                )
            candidate_ids[folded_candidate] = edge.candidate_id
            if edge.join_basis not in STRICT_EDGE_JOIN_BASIS:
                raise ValueError(f"unsupported graph join basis: {edge.join_basis}")
            edge_id = _edge_record(edge)["edgeId"]
            if edge_id in edge_ids:
                raise ValueError(f"duplicate graph edge claim: {edge_id}")
            edge_ids.add(edge_id)
            relation_counts[edge.relation] = relation_counts.get(edge.relation, 0) + 1
            disposition_counts[edge.disposition] = disposition_counts.get(edge.disposition, 0) + 1
            for source_ref in edge.source_refs:
                if source_ref not in expected_join_refs:
                    continue
                join_reference_dispositions.setdefault(source_ref, []).append(edge.disposition)
            if edge.relation == "NAME_MATCH" and not (
                edge.edge_class == "CANDIDATE"
                and edge.disposition == "CANDIDATE"
                and edge.provenance == "INFERRED"
                and edge.confidence == "LOW"
                and edge.join_basis == "NAME_EQUALITY"
            ):
                raise ValueError("NAME_MATCH must remain a low-confidence inferred candidate")
            if edge.relation == "IDENTIFIES" and not (
                edge.edge_class == "SEMANTIC"
                and edge.disposition == "PROVEN"
                and edge.confidence == "HIGH"
                and edge.join_basis
                in {
                    "DIRECT_TYPED_REFERENCE", "EXPLICIT_REGISTRY_BINDING",
                    "EXPLICIT_CONSUMER_BINDING", "CORROBORATED_TYPED_REFERENCE",
                }
            ):
                raise ValueError("IDENTIFIES identity edge lacks a proven typed join")
        expected = {key.casefold(): key for key in self.expected_source_keys}
        source_nodes = {
            node.key.casefold(): node.key for node in nodes if node.kind == "INVENTORY_ROW"
        }
        missing = sorted(set(expected) - set(source_nodes))
        extra = sorted(set(source_nodes) - set(expected)) if expected else []
        if missing or extra:
            raise ValueError(f"inventory row node conservation mismatch; missing={missing}, extra={extra}")
        kind_counts: dict[str, int] = {}
        for node in nodes:
            kind_counts[node.kind] = kind_counts.get(node.kind, 0) + 1
            for source_ref in node.source_refs:
                if source_ref not in expected_join_refs:
                    continue
                join_reference_dispositions.setdefault(source_ref, []).append(node.disposition)
        missing_join_refs = sorted(expected_join_refs - set(join_reference_dispositions))
        if missing_join_refs:
            raise ValueError(
                f"unaccounted join references: count={len(missing_join_refs)} first={missing_join_refs[:3]}"
            )
        join_disposition_counts: dict[str, int] = {}
        disposition_rank = {
            "PROVEN": 0,
            "CANDIDATE": 1,
            "UNRESOLVED": 2,
            "SOURCE_CONFLICT": 3,
        }
        for dispositions in join_reference_dispositions.values():
            disposition = max(dispositions, key=disposition_rank.__getitem__)
            join_disposition_counts[disposition] = join_disposition_counts.get(disposition, 0) + 1
        normalized_edge_count = (
            disposition_counts.get("PROVEN", 0) + disposition_counts.get("CANDIDATE", 0)
        )
        conservation = {
            "sourceRowCount": len(expected) if expected else len(source_nodes),
            "sourceRowNodes": len(source_nodes),
            "unrepresentedSourceRows": len(missing),
            "derivedNodeCount": len(nodes) - len(source_nodes),
            "unresolvedReferenceNodeCount": kind_counts.get("UNRESOLVED_REFERENCE", 0),
            "nodeCount": len(nodes),
            "nodeCountByKind": dict(sorted(kind_counts.items())),
            "edgeCount": len(edges),
            "edgeCandidateCount": len(candidate_ids),
            "joinCandidateCount": len(expected_join_refs),
            "joinReferencesSha256": _sha(sorted(expected_join_refs)),
            "joinCandidateCountByDisposition": dict(sorted(join_disposition_counts.items())),
            "normalizedJoinCandidateCount": (
                join_disposition_counts.get("PROVEN", 0)
                + join_disposition_counts.get("CANDIDATE", 0)
            ),
            "unresolvedJoinCandidateCount": join_disposition_counts.get("UNRESOLVED", 0),
            "sourceConflictJoinCandidateCount": join_disposition_counts.get("SOURCE_CONFLICT", 0),
            "normalizedEdgeCount": normalized_edge_count,
            "edgeCountByRelation": dict(sorted(relation_counts.items())),
            "edgeCountByDisposition": dict(sorted(disposition_counts.items())),
            "unresolvedEdgeCount": disposition_counts.get("UNRESOLVED", 0),
            "sourceConflictEdgeCount": disposition_counts.get("SOURCE_CONFLICT", 0),
            "danglingEdgeCount": 0,
            "duplicateNodeKeyCount": 0,
            "duplicateEdgeIdCount": 0,
            "unaccountedJoinCandidates": len(expected_join_refs) - len(join_reference_dispositions),
        }
        object.__setattr__(self, "conservation", freeze_json(conservation))
        nodes_hash = _sha([_node_record(node) for node in nodes])
        edges_hash = _sha([_edge_record(edge) for edge in edges])
        object.__setattr__(self, "nodes_sha256", nodes_hash)
        object.__setattr__(self, "edges_sha256", edges_hash)
        object.__setattr__(
            self,
            "graph_surface_sha256",
            _sha(
                {
                    "schemaVersion": 1,
                    "audit": GRAPH_AUDIT,
                    "conservation": conservation,
                    "nodesSha256": nodes_hash,
                    "edgesSha256": edges_hash,
                }
            ),
        )

    def node(self, key: str) -> TraceNode:
        for node in self.nodes:
            if node.key == key:
                return node
        raise KeyError(key)

    def edge_types(self) -> frozenset[str]:
        return frozenset(edge.relation for edge in self.edges)


class _GraphBuilder:
    def __init__(self, rows: Sequence[Mapping[str, Any]]) -> None:
        self.rows = tuple(rows)
        self.node_values: dict[str, dict[str, Any]] = {}
        self.edges: list[TraceEdge] = []
        self.row_by_key = {str(row["key"]): row for row in self.rows}
        self.function_by_name: dict[str, str] = {}
        self.function_by_address: dict[str, str] = {}
        self.function_name_conflicts: dict[str, set[str]] = {}
        self.function_address_conflicts: dict[str, set[str]] = {}

    @staticmethod
    def _register_exact(
        index: dict[str, str],
        conflicts: dict[str, set[str]],
        token: str,
        target: str,
    ) -> None:
        if token in conflicts:
            conflicts[token].add(target)
            return
        prior = index.get(token)
        if prior is not None and prior != target:
            conflicts[token] = {prior, target}
            del index[token]
            return
        index[token] = target

    def add_node(
        self,
        key: str,
        kind: str,
        label: str,
        evidence: Iterable[str],
        *,
        provenance: str,
        confidence: str,
        disposition: str,
        source_refs: Iterable[str],
        attributes: Mapping[str, Any] | None = None,
    ) -> None:
        evidence_set = set(evidence)
        refs_set = set(source_refs)
        if not evidence_set or not refs_set:
            raise ValueError(f"derived graph node lacks evidence/source refs: {key}")
        prior = self.node_values.get(key)
        value = {
            "key": key, "kind": kind, "label": label, "evidence": evidence_set,
            "provenance": provenance, "confidence": confidence, "disposition": disposition,
            "source_refs": refs_set, "attributes": dict(attributes or {}),
        }
        if prior is None:
            self.node_values[key] = value
            return
        for field_name in ("kind", "label", "provenance", "confidence", "disposition"):
            if prior[field_name] != value[field_name]:
                raise ValueError(f"conflicting derived node metadata for {key}: {field_name}")
        if prior["attributes"] != value["attributes"]:
            raise ValueError(f"conflicting derived node metadata for {key}: attributes")
        prior["evidence"].update(evidence_set)
        prior["source_refs"].update(refs_set)

    def add_edge(
        self,
        source: str,
        relation: str,
        target: str,
        evidence: Iterable[str],
        *,
        provenance: str,
        confidence: str,
        disposition: str,
        edge_class: str,
        join_basis: str,
        source_refs: Iterable[str],
        candidate_id: str,
    ) -> None:
        self.edges.append(
            TraceEdge(
                source, relation, target, _text_list(evidence), provenance=provenance,
                confidence=confidence, disposition=disposition, edge_class=edge_class,
                join_basis=join_basis, source_refs=_text_list(source_refs),
                candidate_id=candidate_id,
            )
        )

    def primary_nodes(self) -> None:
        for row in self.rows:
            primary_evidence = list(row["evidence"])
            primary_refs = [f"inventory:{row['key']}"]
            upstream_references = (
                row.get("classification", {}).get("upstreamReferences", [])
                if row["inventory"] == "FUNCTION"
                else []
            )
            for index, upstream in enumerate(upstream_references):
                primary_refs.append(
                    f"inventory:{row['key']}/classification/upstreamReferences/{index}"
                )
                primary_evidence.extend(upstream.get("evidence", []))
            attributes = {
                "inventory": row["inventory"],
                "rowKind": row.get("rowKind", "MESSAGE" if row["inventory"] == "PROTOCOL" else "UNKNOWN"),
                "reachability": row["reachability"],
                "recoveryDisposition": row["recoveryDisposition"],
                "states": dict(row["states"]),
                "firstMissingBoundary": row.get("firstMissingBoundary"),
                "implementationDisposition": row.get("implementationDisposition"),
                "sourceRowSha256": _sha(row),
                "upstreamReferenceCount": len(upstream_references),
            }
            self.add_node(
                row["key"], "INVENTORY_ROW", row["name"], primary_evidence,
                provenance=row["provenance"], confidence="HIGH", disposition="PROVEN",
                source_refs=primary_refs, attributes=attributes,
            )
        for row in self.rows:
            if row["inventory"] != "FUNCTION":
                continue
            if row.get("rowKind") == "INDIVIDUAL_FUNCTION":
                address = str(row.get("address", "")).upper().removeprefix("0X")
                if address:
                    self._register_exact(
                        self.function_by_address, self.function_address_conflicts,
                        address, row["key"],
                    )
                self._register_exact(
                    self.function_by_name, self.function_name_conflicts,
                    str(row["name"]), row["key"],
                )
            else:
                for member in row.get("identity", {}).get("members", []):
                    address = str(member.get("address", "")).upper().removeprefix("0X")
                    if address:
                        self._register_exact(
                            self.function_by_address, self.function_address_conflicts,
                            address, row["key"],
                        )
                    name = member.get("name")
                    if isinstance(name, str) and name:
                        self._register_exact(
                            self.function_by_name, self.function_name_conflicts,
                            name, row["key"],
                        )
        for reference_kind, conflicts in (
            ("FUNCTION_NAME", self.function_name_conflicts),
            ("FUNCTION_ADDRESS", self.function_address_conflicts),
        ):
            for token, candidate_keys in sorted(conflicts.items()):
                conflict_key = f"SOURCE_CONFLICT:{reference_kind}:{_sha(token)[:24]}"
                refs = tuple(f"inventory:{key}" for key in sorted(candidate_keys))
                evidence = tuple(
                    item
                    for key in sorted(candidate_keys)
                    for item in self.row_by_key[key]["evidence"]
                )
                self.add_node(
                    conflict_key, "UNRESOLVED_REFERENCE", token, evidence,
                    provenance="UNKNOWN", confidence="UNKNOWN", disposition="SOURCE_CONFLICT",
                    source_refs=refs,
                    attributes={
                        "referenceKind": f"{reference_kind}_CONFLICT",
                        "token": token,
                        "candidateKeys": sorted(candidate_keys),
                    },
                )
                for candidate_key in sorted(candidate_keys):
                    self.add_edge(
                        candidate_key, "MENTIONS", conflict_key,
                        self.row_by_key[candidate_key]["evidence"], provenance="UNKNOWN",
                        confidence="UNKNOWN", disposition="SOURCE_CONFLICT",
                        edge_class="STRUCTURAL", join_basis="UNRESOLVED_REFERENCE",
                        source_refs=(f"inventory:{candidate_key}",),
                        candidate_id=(
                            f"{reference_kind}_CONFLICT:{token}:{candidate_key}"
                        ),
                    )

    def unresolved_function(self, token: str, evidence: Iterable[str], source_ref: str) -> str:
        normalized = token.upper().removeprefix("0X").removeprefix("FUN_")
        key = f"UNRESOLVED:FUNCTION_ADDRESS:{normalized}"
        self.add_node(
            key, "UNRESOLVED_REFERENCE", token, evidence, provenance="UNKNOWN",
            confidence="UNKNOWN", disposition="UNRESOLVED", source_refs=(source_ref,),
            attributes={"referenceKind": "FUNCTION_ADDRESS", "token": token},
        )
        return key

    def resolve_function(self, token: str, evidence: Iterable[str], source_ref: str) -> tuple[str, bool]:
        conflicts = self.function_name_conflicts.get(token)
        normalized = token.upper().removeprefix("0X").removeprefix("FUN_")
        conflict_kind = "FUNCTION_NAME"
        if not conflicts:
            conflicts = self.function_address_conflicts.get(normalized)
            conflict_kind = "FUNCTION_ADDRESS"
        if conflicts:
            conflict_token = token if conflict_kind == "FUNCTION_NAME" else normalized
            key = f"SOURCE_CONFLICT:{conflict_kind}:{_sha(conflict_token)[:24]}"
            self.add_node(
                key, "UNRESOLVED_REFERENCE", conflict_token, evidence, provenance="UNKNOWN",
                confidence="UNKNOWN", disposition="SOURCE_CONFLICT", source_refs=(source_ref,),
                attributes={
                    "referenceKind": f"{conflict_kind}_CONFLICT",
                    "token": conflict_token,
                    "candidateKeys": sorted(conflicts),
                },
            )
            return key, False
        if token in self.function_by_name:
            return self.function_by_name[token], True
        if normalized in self.function_by_address:
            return self.function_by_address[normalized], True
        return self.unresolved_function(token, evidence, source_ref), False

    @staticmethod
    def resolution_metadata(target: str, resolved: bool, proven_basis: str) -> tuple[str, str, str]:
        if resolved:
            return "HIGH", "PROVEN", proven_basis
        if target.startswith("SOURCE_CONFLICT:"):
            return "UNKNOWN", "SOURCE_CONFLICT", "UNRESOLVED_REFERENCE"
        return "UNKNOWN", "UNRESOLVED", "UNRESOLVED_REFERENCE"

    def function_edges(self) -> None:
        caller_refs: dict[tuple[str, str, str], tuple[str, tuple[str, ...]]] = {}
        for target_row in self.rows:
            if target_row["inventory"] != "FUNCTION":
                continue
            for index, caller in enumerate(target_row.get("callers", {}).get("direct", [])):
                source_address = str(caller.get("sourceAddress", "")).upper().removeprefix("0X")
                source_key = self.function_by_address.get(source_address)
                if source_key is None:
                    raise ValueError(
                        f"caller source is not an exact unambiguous function: {source_address}"
                    )
                mirror_key = (source_key, target_row["key"], str(caller.get("callsite")))
                if mirror_key in caller_refs:
                    raise ValueError(f"duplicate caller mirror reference: {mirror_key}")
                caller_refs[mirror_key] = (
                    f"inventory:{target_row['key']}/callers/direct/{index}",
                    _text_list(caller.get("evidence", target_row["evidence"])),
                )
        for row in self.rows:
            if row["inventory"] != "FUNCTION" or row.get("rowKind") != "INDIVIDUAL_FUNCTION":
                continue
            source = row["key"]
            for index, call in enumerate(row.get("callees", {}).get("direct", [])):
                evidence = call.get("evidence", row["evidence"])
                ref = f"inventory:{source}/callees/direct/{index}"
                target, resolved = self.resolve_function(str(call["targetAddress"]), evidence, ref)
                edge_refs = [ref]
                edge_evidence = list(evidence)
                if resolved:
                    mirror = caller_refs.pop(
                        (source, target, str(call.get("callsite"))), None
                    )
                    if mirror is not None:
                        mirror_ref, mirror_evidence = mirror
                        edge_refs.append(mirror_ref)
                        edge_evidence.extend(mirror_evidence)
                confidence, disposition, join_basis = self.resolution_metadata(
                    target, resolved, "DIRECT_ADDRESS_REFERENCE"
                )
                self.add_edge(
                    source, "CALLS", target, edge_evidence, provenance=row["provenance"],
                    confidence=confidence, disposition=disposition,
                    edge_class="STRUCTURAL", join_basis=join_basis,
                    source_refs=edge_refs,
                    candidate_id=f"FUNCTION_CALL:{source}:{call.get('callsite')}:{call['targetAddress']}",
                )
            for index, call in enumerate(row.get("callees", {}).get("indirectCallsites", [])):
                callsite = str(call.get("callsite", f"INDEX{index}"))
                ref = f"inventory:{source}/callees/indirectCallsites/{index}"
                key = f"UNRESOLVED:INDIRECT_CALLSITE:{source}:{callsite}"
                evidence = call.get("evidence", row["evidence"])
                self.add_node(
                    key, "UNRESOLVED_REFERENCE", f"indirect call {callsite}", evidence,
                    provenance="UNKNOWN", confidence="UNKNOWN", disposition="UNRESOLVED",
                    source_refs=(ref,), attributes={"referenceKind": "INDIRECT_CALLSITE", "callsite": callsite},
                )
                self.add_edge(
                    source, "CALLS", key, evidence, provenance=row["provenance"],
                    confidence="UNKNOWN", disposition="UNRESOLVED", edge_class="STRUCTURAL",
                    join_basis="UNRESOLVED_REFERENCE", source_refs=(ref,),
                    candidate_id=f"INDIRECT_CALL:{source}:{callsite}:{index}",
                )
            fields = row.get("globalStructureFields", {})
            for relation, field_name in (("READS", "reads"), ("WRITES", "writes")):
                for index, access in enumerate(fields.get(field_name, [])):
                    target_address = str(access.get("targetAddress", "")).upper().removeprefix("0X")
                    if not target_address:
                        continue
                    ref = f"inventory:{source}/globalStructureFields/{field_name}/{index}"
                    evidence = access.get("evidence", row["evidence"])
                    state_key = f"STATE:ADDRESS:{target_address}"
                    self.add_node(
                        state_key, "STATE_LOCATION", access.get("targetSymbol") or target_address,
                        evidence, provenance=row["provenance"], confidence="MEDIUM",
                        disposition="CANDIDATE", source_refs=(ref,),
                        attributes={"address": target_address, "symbol": access.get("targetSymbol")},
                    )
                    self.add_edge(
                        source, relation, state_key, evidence, provenance=row["provenance"],
                        confidence="MEDIUM", disposition="CANDIDATE", edge_class="SEMANTIC",
                        join_basis="DIRECT_ADDRESS_REFERENCE", source_refs=(ref,),
                        candidate_id=f"{relation}:{source}:{access.get('referenceAddress')}:{target_address}:{index}",
                    )
            for index, string_ref in enumerate(fields.get("stringReferences", [])):
                string_address = str(string_ref.get("stringAddress", "")).upper().removeprefix("0X")
                if not string_address:
                    continue
                ref = f"inventory:{source}/globalStructureFields/stringReferences/{index}"
                evidence = string_ref.get("evidence", row["evidence"])
                string_key = f"STRING:ADDRESS:{string_address}"
                value = string_ref.get("value")
                label = str(value) if value is not None and str(value).strip() else string_address
                self.add_node(
                    string_key, "STRING_LITERAL", label,
                    evidence, provenance=row["provenance"], confidence="HIGH",
                    disposition="PROVEN", source_refs=(ref,),
                    attributes={"address": string_address, "value": value},
                )
                self.add_edge(
                    source, "MENTIONS", string_key, evidence, provenance=row["provenance"],
                    confidence="HIGH", disposition="PROVEN", edge_class="STRUCTURAL",
                    join_basis="DIRECT_ADDRESS_REFERENCE", source_refs=(ref,),
                    candidate_id=(
                        f"STRING_REFERENCE:{source}:{string_ref.get('referenceAddress')}:{string_address}:{index}"
                    ),
                )
        if caller_refs:
            first = next(iter(caller_refs))
            raise ValueError(
                f"caller references were not matched to callee edges: count={len(caller_refs)} first={first}"
            )

    def protocol_edges(self) -> None:
        protocol_keys = {row["key"] for row in self.rows if row["inventory"] == "PROTOCOL"}
        for row in self.rows:
            if row["inventory"] != "PROTOCOL":
                continue
            ownership_refs: dict[tuple[str, str], tuple[str, tuple[str, ...]]] = {}
            for role, relation_type in (
                ("parser", "PARSES"),
                ("dispatcher", "DISPATCHES"),
                ("serializer", "SERIALIZES"),
            ):
                section = row.get("ownership", {}).get(role, {})
                for index, token in enumerate(section.get("functions", [])):
                    ownership_key = (relation_type, str(token))
                    if ownership_key in ownership_refs:
                        raise ValueError(f"duplicate protocol ownership reference: {ownership_key}")
                    ownership_refs[ownership_key] = (
                        f"inventory:{row['key']}/ownership/{role}/functions/{index}",
                        _text_list(section.get("evidence", row["evidence"])),
                    )
            for index, relation in enumerate(row.get("relations", [])):
                ref = f"inventory:{row['key']}/relations/{index}"
                evidence = relation.get("evidence", row["evidence"])
                edge_refs = [ref]
                edge_evidence = list(evidence)
                ownership = ownership_refs.pop(
                    (str(relation["type"]), str(relation["function"])), None
                )
                if ownership is not None:
                    ownership_ref, ownership_evidence = ownership
                    edge_refs.append(ownership_ref)
                    edge_evidence.extend(ownership_evidence)
                function, resolved = self.resolve_function(str(relation["function"]), evidence, ref)
                confidence, disposition, join_basis = self.resolution_metadata(
                    function, resolved, "EXACT_FUNCTION_TOKEN"
                )
                self.add_edge(
                    function, relation["type"], row["key"], edge_evidence,
                    provenance=row["provenance"], confidence=confidence,
                    disposition=disposition, edge_class="SEMANTIC", join_basis=join_basis,
                    source_refs=edge_refs, candidate_id=f"PROTOCOL_RELATION:{row['key']}:{index}",
                )
            if ownership_refs:
                raise ValueError(
                    f"protocol ownership references lack relation mirrors: {row['key']} {sorted(ownership_refs)}"
                )
            code_space = row.get("codeSpace") or row["key"].split(":")[1]
            for sibling_name, relation_name in (
                ("request", "REQUEST_SIBLING"), ("response", "RESPONSE_SIBLING"),
                ("notify", "NOTIFY_SIBLING"),
            ):
                sibling = row.get("siblings", {}).get(sibling_name, {})
                for index, code in enumerate(sibling.get("codes", [])):
                    target = f"PROTOCOL:{code_space}:{code}"
                    ref = f"inventory:{row['key']}/siblings/{sibling_name}/{index}"
                    if target not in protocol_keys:
                        unresolved = f"UNRESOLVED:PROTOCOL:{code_space}:{code}"
                        self.add_node(
                            unresolved, "UNRESOLVED_REFERENCE", target,
                            sibling.get("evidence", row["evidence"]), provenance="UNKNOWN",
                            confidence="UNKNOWN", disposition="UNRESOLVED", source_refs=(ref,),
                            attributes={"referenceKind": "PROTOCOL_SIBLING", "target": target},
                        )
                        target = unresolved
                    self.add_edge(
                        row["key"], relation_name, target, sibling.get("evidence", row["evidence"]),
                        provenance=row["provenance"], confidence="HIGH" if target in protocol_keys else "UNKNOWN",
                        disposition="PROVEN" if target in protocol_keys else "UNRESOLVED",
                        edge_class="STRUCTURAL", join_basis="EXACT_SIBLING_CODE" if target in protocol_keys else "UNRESOLVED_REFERENCE",
                        source_refs=(ref,), candidate_id=f"PROTOCOL_SIBLING:{row['key']}:{sibling_name}:{code}:{index}",
                    )
            for index, fact in enumerate(row.get("facts", [])):
                if fact.get("kind") not in {"DISPATCH_HELPER", "OUTBOUND_BINDER", "PARSER_HELPER"}:
                    continue
                ref = f"inventory:{row['key']}/facts/{index}"
                evidence = fact.get("evidence", row["evidence"])
                function, resolved = self.resolve_function(str(fact.get("value")), evidence, ref)
                confidence, disposition, join_basis = self.resolution_metadata(
                    function, resolved, "EXACT_FUNCTION_TOKEN"
                )
                self.add_edge(
                    function, "MENTIONS", row["key"], evidence, provenance=row["provenance"],
                    confidence=confidence, disposition=disposition, edge_class="STRUCTURAL",
                    join_basis=join_basis,
                    source_refs=(ref,), candidate_id=f"PROTOCOL_FACT:{row['key']}:{index}",
                )

    def ui_edges(self) -> None:
        for row in self.rows:
            if row["inventory"] != "UI":
                continue
            key = row["key"]
            builder = row.get("builder", {})
            for index, token in enumerate(builder.get("functions", [])):
                ref = f"inventory:{key}/builder/functions/{index}"
                function, resolved = self.resolve_function(token, builder.get("evidence", row["evidence"]), ref)
                confidence, disposition, join_basis = self.resolution_metadata(
                    function, resolved, "EXPLICIT_CONSUMER_BINDING"
                )
                self.add_edge(
                    function, "BUILDS", key, builder.get("evidence", row["evidence"]),
                    provenance=row["provenance"], confidence=confidence,
                    disposition=disposition, edge_class="STRUCTURAL", join_basis=join_basis,
                    source_refs=(ref,), candidate_id=f"UI_BUILDER:{key}:{index}",
                )
            constructor = builder.get("constructor")
            if isinstance(constructor, str) and constructor:
                ref = f"inventory:{key}/builder/constructor"
                function, resolved = self.resolve_function(constructor, builder.get("evidence", row["evidence"]), ref)
                confidence, disposition, join_basis = self.resolution_metadata(
                    function, resolved, "EXPLICIT_CONSUMER_BINDING"
                )
                self.add_edge(
                    function, "CONSTRUCTS", key, builder.get("evidence", row["evidence"]),
                    provenance=row["provenance"], confidence=confidence,
                    disposition=disposition, edge_class="STRUCTURAL", join_basis=join_basis,
                    source_refs=(ref,), candidate_id=f"UI_CONSTRUCTOR:{key}",
                )
            for index, token in enumerate(row.get("handler", {}).get("functions", [])):
                ref = f"inventory:{key}/handler/functions/{index}"
                function, resolved = self.resolve_function(token, row["handler"].get("evidence", row["evidence"]), ref)
                confidence, disposition, join_basis = self.resolution_metadata(
                    function, resolved, "EXPLICIT_CONSUMER_BINDING"
                )
                self.add_edge(
                    key, "TRIGGERS", function, row["handler"].get("evidence", row["evidence"]),
                    provenance=row["provenance"], confidence=confidence,
                    disposition=disposition, edge_class="SEMANTIC", join_basis=join_basis,
                    source_refs=(ref,), candidate_id=f"UI_HANDLER:{key}:{index}",
                )
            for index, token in enumerate(row.get("enablement", {}).get("writers", [])):
                ref = f"inventory:{key}/enablement/writers/{index}"
                function, resolved = self.resolve_function(token, row["enablement"].get("evidence", row["evidence"]), ref)
                confidence, disposition, join_basis = self.resolution_metadata(
                    function, resolved, "EXPLICIT_CONSUMER_BINDING"
                )
                self.add_edge(
                    function, "ENABLES", key, row["enablement"].get("evidence", row["evidence"]),
                    provenance=row["provenance"], confidence=confidence,
                    disposition=disposition, edge_class="SEMANTIC", join_basis=join_basis,
                    source_refs=(ref,), candidate_id=f"UI_ENABLEMENT:{key}:{index}",
                )
            for index, token in enumerate(row.get("label", {}).get("consumerFunctions", [])):
                ref = f"inventory:{key}/label/consumerFunctions/{index}"
                function, resolved = self.resolve_function(token, row["label"].get("evidence", row["evidence"]), ref)
                confidence, disposition, join_basis = self.resolution_metadata(
                    function, resolved, "EXPLICIT_CONSUMER_BINDING"
                )
                self.add_edge(
                    function, "APPLIES", key, row["label"].get("evidence", row["evidence"]),
                    provenance=row["provenance"], confidence=confidence,
                    disposition=disposition, edge_class="SEMANTIC", join_basis=join_basis,
                    source_refs=(ref,), candidate_id=f"UI_LABEL_CONSUMER:{key}:{index}",
                )
            for index, target in enumerate(row.get("childManagers", {}).get("targetKeys", [])):
                ref = f"inventory:{key}/childManagers/targetKeys/{index}"
                if target not in self.row_by_key:
                    unresolved = f"UNRESOLVED:UI:{_sha(target)[:24]}"
                    self.add_node(
                        unresolved, "UNRESOLVED_REFERENCE", target,
                        row["childManagers"].get("evidence", row["evidence"]), provenance="UNKNOWN",
                        confidence="UNKNOWN", disposition="UNRESOLVED", source_refs=(ref,),
                        attributes={"referenceKind": "UI_CHILD", "target": target},
                    )
                    target = unresolved
                self.add_edge(
                    key, "PARENT_OF", target, row["childManagers"].get("evidence", row["evidence"]),
                    provenance=row["provenance"], confidence="HIGH" if target in self.row_by_key else "UNKNOWN",
                    disposition="PROVEN" if target in self.row_by_key else "UNRESOLVED",
                    edge_class="SEMANTIC", join_basis="EXPLICIT_REGISTRY_BINDING" if target in self.row_by_key else "UNRESOLVED_REFERENCE",
                    source_refs=(ref,), candidate_id=f"UI_CHILD:{key}:{index}",
                )
            for section_name in ("enablement", "visibility"):
                section = row.get(section_name, {}) or {}
                evidence = section.get("evidence", row["evidence"])
                for index, token in enumerate(section.get("stateFields", [])):
                    ref = f"inventory:{key}/{section_name}/stateFields/{index}"
                    target = f"UNRESOLVED:UI_STATE_FIELD:{_sha(str(token))[:24]}"
                    self.add_node(
                        target, "UNRESOLVED_REFERENCE", str(token), evidence,
                        provenance="UNKNOWN", confidence="UNKNOWN", disposition="UNRESOLVED",
                        source_refs=(ref,),
                        attributes={"referenceKind": "UI_STATE_FIELD", "token": token},
                    )
                    self.add_edge(
                        key, "READS", target, evidence, provenance=row["provenance"],
                        confidence="UNKNOWN", disposition="UNRESOLVED", edge_class="SEMANTIC",
                        join_basis="UNRESOLVED_REFERENCE", source_refs=(ref,),
                        candidate_id=f"UI_STATE_FIELD:{key}:{section_name}:{index}:{token}",
                    )
                for index, token in enumerate(section.get("predicates", [])):
                    self._ui_predicate_edge(row, section_name, index, token, evidence)
            event = row.get("event", {}) or {}
            event_evidence = event.get("evidence", row["evidence"])
            namespace = str(event.get("namespace") or "UNKNOWN")
            for index, event_type in enumerate(event.get("types", [])):
                ref = f"inventory:{key}/event/types/{index}"
                event_key = f"EVENT:{_sha({'namespace': namespace, 'type': event_type})[:32]}"
                proven = event.get("status") == "PROVEN" and namespace != "UNKNOWN"
                self.add_node(
                    event_key, "EVENT", f"{namespace}:{event_type}", event_evidence,
                    provenance=row["provenance"], confidence="HIGH" if proven else "LOW",
                    disposition="PROVEN" if proven else "CANDIDATE", source_refs=(ref,),
                    attributes={"namespace": namespace, "type": event_type},
                )
                self.add_edge(
                    key, "MENTIONS", event_key, event_evidence, provenance=row["provenance"],
                    confidence="HIGH" if proven else "LOW",
                    disposition="PROVEN" if proven else "CANDIDATE", edge_class="STRUCTURAL",
                    join_basis="DIRECT_TYPED_REFERENCE" if proven else "SINGLE_SOURCE_CANDIDATE",
                    source_refs=(ref,), candidate_id=f"UI_EVENT_TYPE:{key}:{index}:{event_type}",
                )
            for index, token in enumerate(event.get("predicates", [])):
                self._ui_predicate_edge(row, "event", index, token, event_evidence)

    def _ui_predicate_edge(
        self,
        row: Mapping[str, Any],
        section_name: str,
        index: int,
        token: Any,
        evidence: Iterable[str],
    ) -> None:
        source = row["key"]
        ref = f"inventory:{source}/{section_name}/predicates/{index}"
        token_text = str(token)
        target, resolved = self.resolve_function(token_text, evidence, ref)
        confidence, disposition, join_basis = self.resolution_metadata(
            target, resolved, "EXACT_FUNCTION_TOKEN"
        )
        self.add_edge(
            source, "MENTIONS", target, evidence, provenance=row["provenance"],
            confidence=confidence, disposition=disposition, edge_class="STRUCTURAL",
            join_basis=join_basis,
            source_refs=(ref,),
            candidate_id=f"UI_PREDICATE:{source}:{section_name}:{index}:{token_text}",
        )

    def entity_edges(self) -> None:
        for row in self.rows:
            if row["inventory"] != "ENTITY":
                continue
            layout = row.get("layout") or {}
            for index, layout_field in enumerate(layout.get("fields", [])):
                field_key = layout_field.get("key")
                if not isinstance(field_key, str) or not field_key:
                    continue
                ref = f"inventory:{row['key']}/layout/fields/{index}"
                evidence = layout_field.get("evidence", row["evidence"])
                self.add_node(
                    field_key, "FIELD", layout_field.get("name") or field_key, evidence,
                    provenance=row["provenance"], confidence="MEDIUM",
                    disposition="CANDIDATE" if layout_field.get("status") != "PROVEN" else "PROVEN",
                    source_refs=(ref,), attributes={"parentEntityKey": row["key"], "layout": dict(layout_field)},
                )
                self.add_edge(
                    row["key"], "PARENT_OF", field_key, evidence, provenance=row["provenance"],
                    confidence="HIGH", disposition="PROVEN", edge_class="SEMANTIC",
                    join_basis="DIRECT_TYPED_REFERENCE", source_refs=(ref,),
                    candidate_id=f"ENTITY_FIELD:{row['key']}:{field_key}",
                )

    def resource_edges(self) -> None:
        process_claims: dict[tuple[str, str], dict[str, Any]] = {}

        def register_process_claim(
            source: str,
            target: str,
            claim: Mapping[str, Any],
            evidence: Iterable[str],
            source_ref: str,
        ) -> None:
            signature = {
                field: claim.get(field)
                for field in (
                    "status", "api", "callsite", "triggerCallsite", "targetCommand",
                    "workingDirectory", "targetRelativePosixPath", "targetSha256",
                    "configOverrideStatus", "gateSemantics", "runtimeObservationStatus",
                )
            }
            key = (source, target)
            prior = process_claims.get(key)
            if prior is None:
                process_claims[key] = {
                    "signature": signature,
                    "evidence": set(evidence),
                    "sourceRefs": {source_ref},
                }
                return
            if prior["signature"] != signature:
                raise ValueError(
                    f"conflicting process launch claims: {source} -> {target}"
                )
            if source_ref in prior["sourceRefs"]:
                raise ValueError(
                    f"duplicate process launch claim source ref: {source_ref}"
                )
            prior["evidence"].update(evidence)
            prior["sourceRefs"].add(source_ref)

        for row in self.rows:
            if row["inventory"] != "RESOURCE":
                continue
            loader = row.get("loader") or {}
            evidence = loader.get("evidence", row["evidence"])
            for index, token in enumerate(loader.get("functions", [])):
                ref = f"inventory:{row['key']}/loader/functions/{index}"
                function, resolved = self.resolve_function(str(token), evidence, ref)
                if resolved and loader.get("status") == "PROVEN":
                    confidence, disposition = "HIGH", "PROVEN"
                    edge_class, join_basis = "SEMANTIC", "EXACT_FUNCTION_TOKEN"
                elif resolved:
                    confidence, disposition = "LOW", "CANDIDATE"
                    edge_class, join_basis = "CANDIDATE", "SINGLE_SOURCE_CANDIDATE"
                else:
                    confidence = "UNKNOWN"
                    disposition = (
                        "SOURCE_CONFLICT"
                        if function.startswith("SOURCE_CONFLICT:")
                        else "UNRESOLVED"
                    )
                    edge_class, join_basis = "CANDIDATE", "UNRESOLVED_REFERENCE"
                self.add_edge(
                    function, "LOADS", row["key"], evidence,
                    provenance=row["provenance"], confidence=confidence,
                    disposition=disposition, edge_class=edge_class, join_basis=join_basis,
                    source_refs=(ref,),
                    candidate_id=f"RESOURCE_LOADER:{row['key']}:{index}:{token}",
                )
            process_launch = (row.get("source") or {}).get("processLaunch")
            if process_launch:
                ref = f"inventory:{row['key']}/source/processLaunch"
                target = process_launch.get("targetRowKey")
                if (
                    process_launch.get("status") not in {"PROVEN", "PROVEN_STATIC_DEFAULT"}
                    or target not in self.row_by_key
                ):
                    raise ValueError(f"proven process launch target is unresolved: {row['key']}")
                process_evidence = process_launch.get("evidence", row["evidence"])
                register_process_claim(
                    row["key"], target, process_launch, process_evidence, ref
                )
            inbound_launch = (row.get("source") or {}).get("inboundLaunch")
            if inbound_launch:
                ref = f"inventory:{row['key']}/source/inboundLaunch"
                launcher = inbound_launch.get("launcherRowKey")
                if inbound_launch.get("status") not in {
                    "PROVEN", "PROVEN_STATIC_DEFAULT"
                } or launcher not in self.row_by_key:
                    raise ValueError(f"proven inbound launch source is unresolved: {row['key']}")
                launch_evidence = inbound_launch.get("evidence", row["evidence"])
                register_process_claim(
                    launcher, row["key"], inbound_launch, launch_evidence, ref
                )
            document_open = (row.get("source") or {}).get("externalDocumentOpen")
            if document_open:
                ref = f"inventory:{row['key']}/source/externalDocumentOpen"
                if document_open.get("status") != "PROVEN":
                    raise ValueError(f"external document opener is not proven: {row['key']}")
                opener_key = str(document_open.get("openerKey", ""))
                opener_evidence = document_open.get("evidence", row["evidence"])
                self.add_node(
                    opener_key, "EXTERNAL_ARTIFACT", str(document_open.get("openerName")),
                    opener_evidence, provenance="ORIGINAL_OBSERVED", confidence="HIGH",
                    disposition="PROVEN", source_refs=(ref,), attributes={
                        "sha256": document_open.get("openerSha256"),
                        "byteSize": document_open.get("openerByteSize"),
                    },
                )
                self.add_edge(
                    opener_key, "OPENS_DOCUMENT", row["key"], opener_evidence,
                    provenance="ORIGINAL_OBSERVED", confidence="HIGH", disposition="PROVEN",
                    edge_class="SEMANTIC", join_basis="DIRECT_TYPED_REFERENCE",
                    source_refs=(ref,),
                    candidate_id=f"DOCUMENT_OPEN:{opener_key}:{row['key']}",
                )
        for (source, target), claim in sorted(process_claims.items()):
            refs = claim["sourceRefs"]
            self.add_edge(
                source, "LAUNCHES_PROCESS", target, claim["evidence"],
                provenance="ORIGINAL_OBSERVED", confidence="HIGH", disposition="PROVEN",
                edge_class="SEMANTIC",
                join_basis=(
                    "CORROBORATED_TYPED_REFERENCE"
                    if len(refs) > 1 else "DIRECT_TYPED_REFERENCE"
                ),
                source_refs=refs,
                candidate_id=f"PROCESS_LAUNCH:{source}:{target}",
            )

    def authority_edges(self) -> None:
        for row in self.rows:
            if row["inventory"] != "AUTHORITY":
                continue
            target = row.get("sourceKey")
            ref = f"inventory:{row['key']}/sourceKey"
            if target not in self.row_by_key:
                unresolved = f"UNRESOLVED:AUTHORITY_SOURCE:{_sha(target)[:24]}"
                self.add_node(
                    unresolved, "UNRESOLVED_REFERENCE", str(target), row["evidence"],
                    provenance="UNKNOWN", confidence="UNKNOWN", disposition="UNRESOLVED",
                    source_refs=(ref,), attributes={"referenceKind": "AUTHORITY_SOURCE", "target": target},
                )
                target = unresolved
            self.add_edge(
                row["key"], "OBLIGATION_FOR", target, row["evidence"],
                provenance="NEW_DESIGN", confidence="HIGH" if target in self.row_by_key else "UNKNOWN",
                disposition="PROVEN" if target in self.row_by_key else "UNRESOLVED",
                edge_class="STRUCTURAL", join_basis="STRUCTURAL_OBLIGATION" if target in self.row_by_key else "UNRESOLVED_REFERENCE",
                source_refs=(ref,), candidate_id=f"AUTHORITY_SOURCE:{row['key']}",
            )

    def name_edges(self) -> None:
        groups: dict[str, list[Mapping[str, Any]]] = {}
        for row in self.rows:
            normalized = unicodedata.normalize("NFC", str(row["name"]).strip()).casefold()
            if normalized:
                groups.setdefault(normalized, []).append(row)
        for normalized, members in sorted(groups.items()):
            if len(members) < 2:
                continue
            token_hash = _sha(normalized)
            key = f"NAME_TOKEN:{token_hash}"
            refs = tuple(f"inventory:{row['key']}/name" for row in members)
            evidence = tuple(f"graph:name-equality:{row['key']}" for row in members)
            self.add_node(
                key, "NAME_TOKEN", normalized, evidence, provenance="INFERRED",
                confidence="LOW", disposition="CANDIDATE", source_refs=refs,
                attributes={"normalizedName": normalized, "memberCount": len(members)},
            )
            for row in members:
                self.add_edge(
                    row["key"], "NAME_MATCH", key, (f"graph:name-equality:{row['key']}",),
                    provenance="INFERRED", confidence="LOW", disposition="CANDIDATE",
                    edge_class="CANDIDATE", join_basis="NAME_EQUALITY",
                    source_refs=(f"inventory:{row['key']}/name",),
                    candidate_id=f"NAME_MATCH:{row['key']}:{token_hash}",
                )

    def build(self) -> TraceGraph:
        self.primary_nodes()
        self.function_edges()
        self.protocol_edges()
        self.ui_edges()
        self.entity_edges()
        self.resource_edges()
        self.authority_edges()
        self.name_edges()
        nodes = tuple(
            TraceNode(
                value["key"], value["kind"], value["label"], _text_list(value["evidence"]),
                provenance=value["provenance"], confidence=value["confidence"],
                disposition=value["disposition"], source_refs=_text_list(value["source_refs"]),
                attributes=value["attributes"],
            )
            for value in self.node_values.values()
        )
        return TraceGraph(
            nodes,
            tuple(self.edges),
            tuple(row["key"] for row in self.rows),
            _enumerate_join_references(self.rows),
            tuple(self.rows),
        )


def build_graph(rows: Sequence[Mapping[str, Any]]) -> TraceGraph:
    keys = [row.get("key") for row in rows]
    if any(not isinstance(key, str) or not key for key in keys):
        raise ValueError("graph input rows require keys")
    if len({key.casefold() for key in keys}) != len(keys):
        raise ValueError("graph input rows contain duplicate/case-colliding keys")
    return _GraphBuilder(rows).build()


def _source_payload(bundle: InventoryBundle) -> dict[str, Any]:
    return {
        name: {
            "inventory": source.inventory,
            "filename": source.filename,
            "fileSha256": source.file_sha256,
            "canonicalRowsSha256": source.canonical_rows_sha256,
            "rowCount": source.row_count,
            "reconciliationFilename": source.reconciliation_filename,
            "reconciliationSha256": source.reconciliation_sha256,
            "candidateCount": source.candidate_count,
            "normalizedCount": source.normalized_count,
            "unresolvedCount": source.unresolved_count,
            "excludedCount": source.excluded_count,
            "unaccountedCount": source.unaccounted_count,
        }
        for name, source in sorted(bundle.sources.items())
    }


def _bundle_sha_from_manifest(manifest: Mapping[str, Any]) -> str:
    return _sha(
        {
            "schemaVersion": 1,
            "sourceManifestSha256": manifest.get("sourceManifestSha256"),
            "clientSha256": manifest.get("clientSha256"),
            "messageDataSha256": manifest.get("messageDataSha256"),
            "sources": manifest.get("inventorySources"),
        }
    )


def _validate_graph_bundle(graph: TraceGraph, bundle: InventoryBundle) -> None:
    expected_rows = {row["key"]: row for row in bundle.rows}
    source_nodes = {node.key: node for node in graph.nodes if node.kind == "INVENTORY_ROW"}
    if set(source_nodes) != set(expected_rows):
        raise ValueError("graph source rows do not match inventory bundle")
    for key, row in expected_rows.items():
        if source_nodes[key].attributes.get("sourceRowSha256") != _sha(row):
            raise ValueError(f"graph source row hash differs from inventory bundle: {key}")


def graph_jsonl(graph: TraceGraph, bundle: InventoryBundle) -> str:
    _validate_graph_bundle(graph, bundle)
    manifest = {
        "recordType": "GRAPH_MANIFEST",
        "schemaVersion": 1,
        "bundleSha256": bundle.bundle_sha256,
        "sourceManifestSha256": bundle.source_manifest_sha256,
        "clientSha256": bundle.client_sha256,
        "messageDataSha256": bundle.message_data_sha256,
        "inventorySources": _source_payload(bundle),
        "audit": GRAPH_AUDIT,
        "conservation": _plain(graph.conservation),
        "nodesSha256": graph.nodes_sha256,
        "edgesSha256": graph.edges_sha256,
        "graphSurfaceSha256": graph.graph_surface_sha256,
    }
    records = [canonical_json(manifest)]
    records.extend(canonical_json(_node_record(node)) for node in graph.nodes)
    records.extend(canonical_json(_edge_record(edge)) for edge in graph.edges)
    return "".join(records)


def load_graph_jsonl(path: str | Path, *, bundle: InventoryBundle) -> TraceGraph:
    data = Path(path).read_bytes()
    if not data.endswith(b"\n") or b"\r" in data:
        raise ValueError("graph JSONL must use canonical LF records")
    records: list[Mapping[str, Any]] = []
    for number, line in enumerate(data.splitlines(keepends=True), 1):
        try:
            record = json.loads(line.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError(f"graph JSONL record {number} is invalid") from error
        if not isinstance(record, Mapping) or canonical_json(record).encode("utf-8") != line:
            raise ValueError(f"graph JSONL record {number} is not canonical")
        records.append(record)
    if not records or records[0].get("recordType") != "GRAPH_MANIFEST":
        raise ValueError("graph manifest is missing")
    manifest = records[0]
    if manifest.get("schemaVersion") != 1:
        raise ValueError("graph manifest schemaVersion mismatch")
    if manifest.get("audit") != GRAPH_AUDIT:
        raise ValueError("graph manifest audit contract mismatch")
    if manifest.get("bundleSha256") != _bundle_sha_from_manifest(manifest):
        raise ValueError("graph manifest bundle/source hash mismatch")
    if manifest.get("bundleSha256") != bundle.bundle_sha256:
        raise ValueError("graph manifest differs from expected inventory bundle")
    if manifest.get("inventorySources") != _source_payload(bundle):
        raise ValueError("graph inventory source manifest differs from expected bundle")
    for field_name, expected_value in (
        ("sourceManifestSha256", bundle.source_manifest_sha256),
        ("clientSha256", bundle.client_sha256),
        ("messageDataSha256", bundle.message_data_sha256),
    ):
        if manifest.get(field_name) != expected_value:
            raise ValueError(f"graph manifest {field_name} differs from expected bundle")
    nodes: list[TraceNode] = []
    edges: list[TraceEdge] = []
    for record in records[1:]:
        if record.get("recordType") == "NODE":
            content = dict(record); claimed = content.pop("contentSha256", None)
            if claimed != _sha(content):
                raise ValueError("graph node content hash mismatch")
            nodes.append(
                TraceNode(
                    record["key"], record["nodeKind"], record["label"], tuple(record["evidence"]),
                    provenance=record["provenance"], confidence=record["confidence"],
                    disposition=record["disposition"], source_refs=tuple(record["sourceRefs"]),
                    attributes=record["attributes"],
                )
            )
        elif record.get("recordType") == "EDGE":
            semantic = {key: value for key, value in record.items() if key not in {"recordType", "edgeId"}}
            if record.get("edgeId") != _sha(semantic):
                raise ValueError("graph edge hash mismatch")
            edges.append(
                TraceEdge(
                    record["source"], record["relation"], record["target"], tuple(record["evidence"]),
                    provenance=record["provenance"], confidence=record["confidence"],
                    disposition=record["disposition"], edge_class=record["edgeClass"],
                    join_basis=record["joinBasis"], source_refs=tuple(record["sourceRefs"]),
                    candidate_id=record["candidateId"],
                )
            )
        else:
            raise ValueError("unknown graph JSONL record type")
    expected_keys = tuple(node.key for node in nodes if node.kind == "INVENTORY_ROW")
    graph = TraceGraph(
        tuple(nodes),
        tuple(edges),
        expected_keys,
        _enumerate_join_references(bundle.rows),
        tuple(bundle.rows),
    )
    if manifest.get("nodesSha256") != graph.nodes_sha256:
        raise ValueError("graph nodes hash mismatch")
    if manifest.get("edgesSha256") != graph.edges_sha256:
        raise ValueError("graph edges hash mismatch")
    if manifest.get("graphSurfaceSha256") != graph.graph_surface_sha256:
        raise ValueError("graph surface hash mismatch")
    if manifest.get("conservation") != _plain(graph.conservation):
        raise ValueError("graph conservation mismatch")
    _validate_graph_bundle(graph, bundle)
    return graph


__all__ = [
    "NODE_KINDS", "TraceGraph", "build_graph", "graph_jsonl", "load_graph_jsonl",
]
