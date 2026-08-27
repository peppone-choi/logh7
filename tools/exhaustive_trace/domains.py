"""Deterministic routing of exhaustive-trace rows into sixteen domain packages."""

from __future__ import annotations

import hashlib
import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from types import MappingProxyType
from typing import Any, Mapping, Sequence

from .coverage import CoverageFatal, CoverageReport, CoverageRow
from .io import canonical_json
from .model import freeze_json


SCHEMA_VERSION = 1
ROUTING_POLICY_VERSION = "TASK11-1"
TOPOLOGY_PLAN = "docs/superpowers/plans/2026-08-27-original-world-topology-full-trace.md"

DOMAIN_SLUGS = MappingProxyType(
    {
        "D01": "launcher-update-config-data-root",
        "D02": "account-auth-lobby-session-character",
        "D03": "faction-calendar-rank-office-authority",
        "D04": "world-topology-systems-planets-fortresses-grids",
        "D05": "fleets-units-ships-troops-fighters-arms",
        "D06": "strategy-navigation-warp-search-encounter-fog",
        "D07": "bases-institutions-spots-rooms-facilities",
        "D08": "commands-orders-suggestions-mail-messenger",
        "D09": "grid-spot-unicast-tactical-communication",
        "D10": "economy-production-construction-repair-supply-cargo",
        "D11": "tactical-entry-field-deployment-combat-retreat",
        "D12": "politics-personnel-diplomacy-governance",
        "D13": "growth-rewards-ranking-victory-session-end",
        "D14": "offline-ai-timeout-disconnect-reconnect-replay",
        "D15": "sound-cursor-localization-hud-information",
        "D16": "administration-moderation-publication-backup-operations",
    }
)

ENTITY_DOMAINS = MappingProxyType(
    {
        "ACCOUNT": "D02", "ADMINISTRATIVE_RECORD": "D16", "AUTHORITY_CARD": "D03",
        "BASE": "D07", "BATTLE": "D11", "CARD": "D08", "CARD_COMMAND": "D08",
        "CHARACTER": "D02", "CHARACTER_CREATION_CHARGE_SLOT": "D02",
        "CHARACTER_PARENTAGE": "D02", "CHAT_CONTEXT": "D09", "CORPS": "D05",
        "CREW": "D05", "EQUIPMENT": "D05", "EVENT_RECORD": "D14", "FACTION": "D03",
        "FIGHTER": "D05", "FIGHTER_FORMATION": "D05", "FIGHTER_TEMPLATE": "D05",
        "FLAGSHIP_ASSIGNMENT": "D05", "FLEET": "D05", "FORMATION": "D05",
        "FORTRESS": "D04", "GRID_CELL": "D04", "GRID_TYPE": "D04",
        "INSTITUTION": "D07", "LOCATION_PRESENCE": "D07", "MAIL": "D08",
        "MAIL_ADDRESS": "D08", "MESSENGER": "D08", "OFFICE": "D03",
        "OFFICE_SEAT": "D03", "ORDER": "D08", "OUTFIT": "D05", "PACKAGE": "D10",
        "PLANET": "D04", "PRODUCTION_JOB": "D10", "PRODUCTION_ORDER": "D10",
        "RANK": "D03", "RANKING": "D13", "REPAIR_SUPPLY_JOB": "D10",
        "REPLY": "D08", "RESOURCE_COMMODITY": "D10", "RESOURCE_STOCK": "D10",
        "ROLE_STATUS_CLASS": "D03", "ROUTE_EDGE": "D04", "SESSION": "D02",
        "SESSION_TERMINAL": "D13", "SHIP": "D05", "SHIP_TEMPLATE": "D05",
        "SHIP_UNIT_INSTANCE": "D05", "SPECIAL_BODY": "D04",
        "SPECIAL_CELESTIAL_BODY": "D04", "SPOT": "D07", "STAR_SYSTEM": "D04",
        "STRATEGIC_MISSION": "D06", "STRATEGY_PLAN": "D06", "SUGGESTION": "D08",
        "SYSTEM": "D04", "TACTICAL_FIELD": "D11", "TACTICAL_GROUP": "D11",
        "TACTICAL_OBSTACLE": "D11", "TACTICS_GRID": "D11", "TROOP": "D05",
        "TROOP_TEMPLATE": "D05", "TROOP_UNIT": "D05", "UNIT": "D05",
        "WAREHOUSE": "D10", "WAREHOUSE_ITEM": "D10", "WEAPON": "D05",
        "WORLD_TIME": "D03",
    }
)

# Ordered from the most specific collision-sensitive surface to the broadest one.
PROTOCOL_RULES = (
    ("D09", r"GlobalChat|GridChat|SpotChat|UnicastChat|Communication|WillMessage"),
    ("D14", r"Offline|Reconnect|Replay|Timeout|Disconnect|TransactionSimpleDataEnd"),
    ("D13", r"Ranking|Ending|Victory|Defeat|Reward|Growth|Achievement|MissionResult"),
    ("D12", r"Politics|Personnel|Diplom|Arrest|Coup|Govern|Dismiss|Resign|Occupation"),
    ("D10", r"Warehouse|Package|Supply|Repair|Fuel|Production|Construction|PrivateAccountRate|DistributePriority|UnloadTroop|LoadTroop"),
    ("D11", r"Tactics|Tactical|Fight|Fought|Attack|Shoot|MoveShip|MovedTroop|TurnShip|TurnedShip|ParallelMoveShip|ReverseShip|Stop|Control|Shield|PositionUnit|FileFleet|AirBattle|Sortie|EvacuateTroops|ChangeMode|LandCombat|Confusion|Morale|BlackHole"),
    ("D08", r"Suggestion|Admission|OrderSuggest|Mail|Messenger|Reply"),
    ("D07", r"InformationBase|StaticInformationBase|Institution|EncourageBase"),
    ("D06", r"MoveGrid|MovedGrid|EnterGrid|LeaveOutGrid|Warp|Search|Strategy|ReturnBase|MovedBase|PositionBase|Mission"),
    ("D05", r"UnitShip|UnitTroop|Fighter|Arms|InformationUnit|Outfit|Corps|FlagShip|Flagship|Reorganization"),
    ("D04", r"GridType|InformationGrid|StaticInformationGrid|Planet|Fortress|Celestial|StarSystem"),
    ("D03", r"Card|PowerDistribution|(?<!ing)Rank(?!ing)|Office|Authority|Available.*Seat"),
    ("D02", r"Login|Lobby|CharacterID|CharacterEntry|CharacterCharge|GenerateCharacter|UnChargeCharacter|InformationCharacter"),
    ("D16", r"Admin|Moderation|Backup|Publication"),
)

RESOURCE_DOMAINS = MappingProxyType(
    {
        "FONT": ("D15", ()), "CURSOR": ("D15", ()), "SOUND": ("D15", ()),
        "MESSAGE": ("D15", ()), "DOCUMENTATION": ("D01", ()),
        "EXECUTABLE": ("D01", ()), "CONFIGURATION": ("D01", ()),
        "PORTRAIT": ("D02", ("D12",)), "SPOT_BACKGROUND": ("D07", ("D15",)),
        "MAP": ("D04", ("D06", "D11")), "MODEL": ("D05", ("D04", "D11")),
        "TEXTURE": ("D15", ("D07", "D11")),
    }
)
UI_LABEL_DOMAINS = MappingProxyType(
    {
        "キャラクター情報": "D02",
        "艦艇情報": "D05",
        "旗艦情報": "D05",
        "戦隊情報": "D05",
        "部隊情報": "D05",
        "陸戦隊情報": "D05",
        "惑星要塞情報": "D04",
    }
)

FALLBACK_PRIMARY = MappingProxyType(
    {"PROTOCOL": "D02", "UI": "D15", "ENTITY": "D16", "RESOURCE": "D01", "FUNCTION": "D01", "AUTHORITY": "D16"}
)
FALLBACK_CANDIDATES = MappingProxyType(
    {
        "PROTOCOL": tuple(domain for domain in DOMAIN_SLUGS if domain not in {"D01", "D15"}),
        "UI": tuple(domain for domain in DOMAIN_SLUGS if domain not in {"D01", "D16"}),
        "ENTITY": tuple(DOMAIN_SLUGS),
        "RESOURCE": ("D01", "D04", "D05", "D06", "D07", "D11", "D15"),
        "FUNCTION": tuple(DOMAIN_SLUGS),
        "AUTHORITY": tuple(domain for domain in DOMAIN_SLUGS if domain not in {"D01", "D15"}),
    }
)
PROPAGATED_RELATIONS = frozenset(
    {"PARSES", "SERIALIZES", "DISPATCHES", "BUILDS", "CONSTRUCTS", "TRIGGERS", "APPLIES", "ENABLES", "OBLIGATION_FOR"}
)
PROVEN_PROPAGATION_JOIN_BASES = frozenset(
    {
        "DIRECT_TYPED_REFERENCE", "DIRECT_ADDRESS_REFERENCE", "EXPLICIT_REGISTRY_BINDING",
        "EXPLICIT_CONSUMER_BINDING", "CORROBORATED_TYPED_REFERENCE",
        "STRUCTURAL_OBLIGATION", "EXACT_SIBLING_CODE", "EXACT_FUNCTION_TOKEN",
    }
)

ROUTING_POLICY = MappingProxyType(
    {
        "version": ROUTING_POLICY_VERSION,
        "entityTypeMap": dict(ENTITY_DOMAINS),
        "protocolRules": PROTOCOL_RULES,
        "uiLabelMap": dict(UI_LABEL_DOMAINS),
        "resourceCategoryMap": {key: [primary, *secondary] for key, (primary, secondary) in RESOURCE_DOMAINS.items()},
        "propagatedRelations": tuple(sorted(PROPAGATED_RELATIONS)),
        "candidateEdgesProveRouting": False,
        "nameMatchProvesRouting": False,
        "callGraphProvesRouting": False,
        "fallbackPrimary": dict(FALLBACK_PRIMARY),
    }
)


def _sha(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest().upper()


ROUTING_POLICY_SHA256 = _sha(ROUTING_POLICY)


def _plain(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {key: _plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain(item) for item in value]
    return value


def _text(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be non-empty text")
    return value


def _object_no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate domain JSON key: {key}")
        result[key] = value
    return result


def _is_link_or_reparse(path: Path) -> bool:
    try:
        stat = path.lstat()
    except OSError:
        return False
    return path.is_symlink() or bool(getattr(stat, "st_file_attributes", 0) & 0x400)


def _reject_link_chain(path: Path, label: str) -> None:
    for component in (*reversed(path.parents), path):
        if component == Path(component.anchor):
            continue
        if (component.exists() or component.is_symlink()) and _is_link_or_reparse(component):
            raise ValueError(f"{label} contains a link or reparse point: {component}")


def _read_canonical(path: Path, label: str) -> Mapping[str, Any]:
    data = path.read_bytes()
    if data.startswith(b"\xef\xbb\xbf") or not data.endswith(b"\n") or b"\r" in data:
        raise ValueError(f"{label} must be canonical UTF-8 LF JSON")
    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=_object_no_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"{label} is invalid JSON") from error
    if not isinstance(value, Mapping) or canonical_json(value).encode("utf-8") != data:
        raise ValueError(f"{label} is not canonical JSON")
    return value


@dataclass(frozen=True)
class PlanReference:
    path: str
    sha256: str


@dataclass(frozen=True)
class DomainDefinition:
    id: str
    slug: str
    hard_dependencies: tuple[str, ...]
    plan_refs: tuple[PlanReference, ...]


@dataclass(frozen=True)
class DomainConfig:
    definitions: tuple[DomainDefinition, ...]
    topological_order: tuple[str, ...]
    config_sha256: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "definitions", tuple(self.definitions))
        object.__setattr__(self, "topological_order", tuple(self.topological_order))

    @property
    def by_id(self) -> Mapping[str, DomainDefinition]:
        return MappingProxyType({item.id: item for item in self.definitions})


@dataclass(frozen=True)
class RouteAssignment:
    row_key: str
    primary_domain: str
    secondary_domains: tuple[str, ...]
    candidate_domains: tuple[str, ...]
    disposition: str
    basis: str
    evidence: tuple[str, ...]


@dataclass(frozen=True)
class DomainPackageSet:
    files: tuple[tuple[str, bytes], ...]
    route_surface_sha256: str
    package_set_sha256: str
    primary_count: int
    unresolved_count: int
    coverage_gate_status: str
    conservation: Mapping[str, Any]

    def __post_init__(self) -> None:
        object.__setattr__(self, "files", tuple(sorted(self.files)))
        object.__setattr__(self, "conservation", freeze_json(self.conservation))

    @property
    def packages(self) -> tuple[str, ...]:
        return tuple(name.removesuffix(".json") for name, _ in self.files)


def _topological_order(dependencies: Mapping[str, tuple[str, ...]]) -> tuple[str, ...]:
    indegree = {domain: len(values) for domain, values in dependencies.items()}
    consumers: dict[str, list[str]] = {domain: [] for domain in dependencies}
    for domain, requirements in dependencies.items():
        for requirement in requirements:
            consumers[requirement].append(domain)
    ready = sorted(domain for domain, degree in indegree.items() if degree == 0)
    order: list[str] = []
    while ready:
        domain = ready.pop(0)
        order.append(domain)
        for consumer in sorted(consumers[domain]):
            indegree[consumer] -= 1
            if indegree[consumer] == 0:
                ready.append(consumer)
                ready.sort()
    if len(order) != len(dependencies):
        raise ValueError("domain hard dependency cycle detected")
    return tuple(order)


def load_domain_config(path: str | Path, *, project_root: str | Path) -> DomainConfig:
    """Load the exact canonical D01-D16 configuration and hash its plan references."""

    config_path = Path(os.path.abspath(path))
    _reject_link_chain(config_path, "domain configuration")
    value = _read_canonical(config_path, "domain configuration")
    if set(value) != {"recordType", "schemaVersion", "domains"}:
        raise ValueError("domain configuration schema mismatch")
    if value.get("recordType") != "DOMAIN_CONFIGURATION" or value.get("schemaVersion") != 1:
        raise ValueError("domain configuration identity mismatch")
    raw_domains = value.get("domains")
    if not isinstance(raw_domains, list) or len(raw_domains) != 16:
        raise ValueError("domain configuration must contain exactly sixteen domains")
    declared_root = Path(os.path.abspath(project_root))
    _reject_link_chain(declared_root, "project root")
    root = declared_root.resolve()
    definitions: list[DomainDefinition] = []
    seen: set[str] = set()
    dependencies: dict[str, tuple[str, ...]] = {}
    for raw in raw_domains:
        if not isinstance(raw, Mapping) or set(raw) != {"id", "slug", "hardDependencies", "planRefs"}:
            raise ValueError("domain definition schema mismatch")
        domain_id = _text(raw.get("id"), "domain id")
        slug = _text(raw.get("slug"), "domain slug")
        if domain_id in seen:
            raise ValueError("duplicate domain id")
        seen.add(domain_id)
        if DOMAIN_SLUGS.get(domain_id) != slug:
            raise ValueError("domain id/slug set differs from D01-D16 contract")
        raw_dependencies = raw.get("hardDependencies")
        if not isinstance(raw_dependencies, list) or any(not isinstance(item, str) for item in raw_dependencies):
            raise ValueError("domain hard dependency list mismatch")
        dependency_tuple = tuple(raw_dependencies)
        if len(dependency_tuple) != len(set(dependency_tuple)) or domain_id in dependency_tuple:
            raise ValueError("duplicate or self domain dependency")
        raw_refs = raw.get("planRefs")
        if not isinstance(raw_refs, list) or any(not isinstance(item, str) for item in raw_refs):
            raise ValueError("domain planRefs must be a list of paths")
        if domain_id == "D04" and raw_refs != [TOPOLOGY_PLAN]:
            raise ValueError("D04 must bind the exact world topology plan")
        plan_refs: list[PlanReference] = []
        for relative in raw_refs:
            relative_path = Path(_text(relative, "plan reference"))
            if relative_path.is_absolute() or ".." in relative_path.parts:
                raise ValueError("plan reference must remain inside project root")
            declared_ref = root / relative_path
            _reject_link_chain(declared_ref, "plan reference")
            resolved = declared_ref.resolve()
            try:
                resolved.relative_to(root)
            except ValueError as error:
                raise ValueError("plan reference escapes project root") from error
            if not resolved.is_file():
                raise ValueError(f"plan reference is missing: {relative}")
            plan_refs.append(PlanReference(relative_path.as_posix(), hashlib.sha256(resolved.read_bytes()).hexdigest().upper()))
        dependencies[domain_id] = dependency_tuple
        definitions.append(DomainDefinition(domain_id, slug, dependency_tuple, tuple(plan_refs)))
    if seen != set(DOMAIN_SLUGS):
        raise ValueError("domain configuration ID set differs from exact D01-D16 contract")
    for domain, requirements in dependencies.items():
        unknown = sorted(set(requirements) - seen)
        if unknown:
            raise ValueError(f"unknown domain dependency for {domain}: {unknown}")
    order = _topological_order(dependencies)
    definitions.sort(key=lambda item: item.id)
    return DomainConfig(tuple(definitions), order, hashlib.sha256(config_path.read_bytes()).hexdigest().upper())


def _route(primary: str, secondary: Sequence[str], disposition: str, basis: str, evidence: Sequence[str], candidates: Sequence[str] | None = None, *, row_key: str) -> RouteAssignment:
    secondary_tuple = tuple(sorted(set(secondary) - {primary}))
    candidate_tuple = tuple(sorted(set(candidates or (primary, *secondary_tuple)) | {primary}))
    return RouteAssignment(
        row_key,
        primary,
        secondary_tuple,
        candidate_tuple,
        disposition,
        basis,
        tuple(sorted(set(evidence))) or (f"routing-policy:{ROUTING_POLICY_VERSION}",),
    )


def _explicit_routes(rows: Sequence[Mapping[str, Any]]) -> dict[str, RouteAssignment]:
    routes: dict[str, RouteAssignment] = {}
    for row in rows:
        key = str(row["key"])
        inventory = row.get("inventory")
        if inventory == "ENTITY" and row.get("entityType") in ENTITY_DOMAINS:
            entity_type = str(row["entityType"])
            domain = ENTITY_DOMAINS[entity_type]
            routes[key] = _route(domain, (), "PROVEN", "ENTITY_TYPE", (f"entityType:{entity_type}", *row.get("evidence", ())), row_key=key)
        elif inventory == "PROTOCOL" and row.get("semanticNameStatus") == "DIRECT":
            matches = [domain for domain, pattern in PROTOCOL_RULES if re.search(pattern, str(row.get("name", "")), re.IGNORECASE)]
            if matches:
                primary = matches[0]
                disposition = "PROVEN" if len(set(matches)) == 1 else "UNRESOLVED"
                routes[key] = _route(primary, matches[1:], disposition, "DIRECT_PROTOCOL_SEMANTIC_NAME", ("semanticNameStatus:DIRECT", f"semanticName:{row.get('name')}", *row.get("evidence", ())), row_key=key)
        elif inventory == "UI" and row.get("rowKind") == "MENU_ROW":
            label = row.get("label")
            if isinstance(label, Mapping) and label.get("status") == "BOUND_CONSUMER":
                text = str(label.get("text", ""))
                domain = UI_LABEL_DOMAINS.get(text)
                if domain is not None:
                    routes[key] = _route(domain, (), "PROVEN", "BOUND_UI_LABEL", ("label.status:BOUND_CONSUMER", f"label.text:{text}", *label.get("evidence", ())), row_key=key)
        elif inventory == "RESOURCE":
            category = row.get("category")
            category_value = category.get("value") if isinstance(category, Mapping) else None
            if category_value in RESOURCE_DOMAINS:
                primary, secondary = RESOURCE_DOMAINS[str(category_value)]
                category_status = category.get("status")
                disposition = "PROVEN" if category_status == "PROVEN" and not secondary else "UNRESOLVED"
                routes[key] = _route(primary, secondary, disposition, "TYPED_RESOURCE_CATEGORY", (f"category.value:{category_value}", f"category.status:{category_status}", *category.get("evidence", ())), row_key=key)
    # Authority is a typed exact join and never routes from its display name or lost-server status.
    for row in rows:
        if row.get("inventory") != "AUTHORITY":
            continue
        key = str(row["key"])
        source_key = row.get("sourceKey")
        source = routes.get(str(source_key))
        if source is None:
            continue
        routes[key] = _route(
            source.primary_domain,
            source.secondary_domains,
            source.disposition,
            "AUTHORITY_SOURCE_KEY",
            (f"sourceKey:{source_key}", *row.get("evidence", ())),
            source.candidate_domains,
            row_key=key,
        )
    return routes


def _all_routes(graph: Any, rows: Sequence[Mapping[str, Any]]) -> tuple[RouteAssignment, ...]:
    by_key = {str(row["key"]): row for row in rows}
    routes = _explicit_routes(rows)
    singleton_proven = {
        key: route.primary_domain
        for key, route in routes.items()
        if route.disposition == "PROVEN" and not route.secondary_domains
    }
    neighbors: dict[str, list[tuple[str, str]]] = {}
    for edge in getattr(graph, "edges", ()):
        if (
            getattr(edge, "relation", None) not in PROPAGATED_RELATIONS
            or getattr(edge, "disposition", None) != "PROVEN"
            or getattr(edge, "edge_class", None) == "CANDIDATE"
            or getattr(edge, "confidence", None) != "HIGH"
            or getattr(edge, "join_basis", None) not in PROVEN_PROPAGATION_JOIN_BASES
            or getattr(edge, "provenance", None) not in {"ORIGINAL_OBSERVED", "ORIGINAL_MANUAL", "NEW_DESIGN"}
            or edge.source not in by_key
            or edge.target not in by_key
        ):
            continue
        evidence = f"edge:{edge.candidate_id}:{edge.relation}"
        neighbors.setdefault(edge.source, []).append((edge.target, evidence))
        neighbors.setdefault(edge.target, []).append((edge.source, evidence))
    additions: dict[str, RouteAssignment] = {}
    for key in sorted(by_key):
        if key in routes:
            continue
        votes: dict[str, list[str]] = {}
        for neighbor, evidence in neighbors.get(key, ()):
            domain = singleton_proven.get(neighbor)
            if domain:
                votes.setdefault(domain, []).append(evidence)
        if not votes:
            continue
        candidates = sorted(votes)
        primary = candidates[0]
        evidence = tuple(item for domain in candidates for item in sorted(votes[domain]))
        additions[key] = _route(
            primary,
            candidates[1:],
            "PROVEN" if len(candidates) == 1 else "UNRESOLVED",
            "PROVEN_TYPED_EDGE_SINGLE_WAVE",
            evidence,
            candidates,
            row_key=key,
        )
    routes.update(additions)
    # A second authority-source application may inherit a route established by the one edge wave.
    for row in rows:
        key = str(row["key"])
        if row.get("inventory") != "AUTHORITY" or key in routes:
            continue
        source_key = str(row.get("sourceKey", ""))
        source = routes.get(source_key)
        if source:
            routes[key] = _route(source.primary_domain, source.secondary_domains, source.disposition, "AUTHORITY_SOURCE_KEY_PROPAGATED", (f"sourceKey:{source_key}", *row.get("evidence", ())), source.candidate_domains, row_key=key)
    for row in rows:
        key = str(row["key"])
        if key in routes:
            continue
        inventory = str(row.get("inventory"))
        if inventory not in FALLBACK_PRIMARY:
            raise ValueError(f"unsupported inventory for domain fallback: {inventory}")
        routes[key] = _route(
            FALLBACK_PRIMARY[inventory],
            (),
            "UNRESOLVED",
            "INVENTORY_FALLBACK_PROVISIONAL",
            (f"routing-fallback:{inventory}:{FALLBACK_PRIMARY[inventory]}", *row.get("evidence", ())),
            FALLBACK_CANDIDATES[inventory],
            row_key=key,
        )
    return tuple(sorted(routes.values(), key=lambda item: (item.row_key.casefold(), item.row_key)))


def _fatal_payload(item: CoverageFatal) -> dict[str, Any]:
    return {"ruleId": item.rule_id, "rowKey": item.row_key, "path": item.path, "evidence": list(item.evidence), "detail": item.detail}


def _coverage_binding(report: CoverageReport) -> dict[str, Any]:
    return {
        "coverageSurfaceSha256": report.coverage_surface_sha256,
        "rowResultsSha256": report.row_results_sha256,
        "fatalSurfaceSha256": report.fatal_surface_sha256,
        "fatalStructuralCount": len(report.fatals),
        "evidenceGapCount": len(report.gaps),
    }


def _assignment_payload(route: RouteAssignment, row: Mapping[str, Any], coverage: CoverageRow) -> dict[str, Any]:
    return {
        "rowKey": route.row_key,
        "inventory": row["inventory"],
        "primaryDomain": route.primary_domain,
        "secondaryDomains": list(route.secondary_domains),
        "candidateDomains": list(route.candidate_domains),
        "routingDisposition": route.disposition,
        "routingBasis": route.basis,
        "routingEvidence": list(route.evidence),
        "coverageVerdict": coverage.verdict,
        "firstMissingBoundary": coverage.first_missing_boundary,
        "allMissingBoundaries": list(coverage.all_missing_boundaries),
        "coverageFatals": [_fatal_payload(item) for item in coverage.fatals],
        "recoveryDisposition": coverage.recovery_disposition,
    }


def build_domain_packages(graph: Any, coverage: CoverageReport, config: DomainConfig) -> DomainPackageSet:
    """Build sixteen deterministic packages without promoting coverage or routing evidence."""

    rows = tuple(getattr(graph, "source_rows", ()))
    row_by_key = {str(row["key"]): row for row in rows}
    if len(row_by_key) != len(rows):
        raise ValueError("domain routing source rows contain duplicate keys")
    expected_count = getattr(graph, "conservation", {}).get("sourceRowNodes", len(rows))
    if expected_count != len(rows):
        raise ValueError("domain routing graph source-row conservation mismatch")
    coverage_by_key = {row.row_key: row for row in coverage.rows}
    if set(coverage_by_key) != set(row_by_key):
        raise ValueError("domain routing coverage row set differs from graph")
    graph_surface = getattr(graph, "graph_surface_sha256", None)
    if coverage.graph_binding.get("graphSurfaceSha256") != graph_surface:
        raise ValueError("domain routing graph/coverage binding mismatch")
    routes = _all_routes(graph, rows)
    if {route.row_key for route in routes} != set(row_by_key) or len(routes) != len(rows):
        raise ValueError("domain routing did not assign every row exactly once")
    route_records = [
        {
            "rowKey": route.row_key,
            "primaryDomain": route.primary_domain,
            "secondaryDomains": list(route.secondary_domains),
            "candidateDomains": list(route.candidate_domains),
            "routingDisposition": route.disposition,
            "routingBasis": route.basis,
            "evidence": list(route.evidence),
        }
        for route in routes
    ]
    route_surface_sha = _sha(route_records)
    route_by_key = {route.row_key: route for route in routes}
    cross_domain_dependencies: list[dict[str, Any]] = []
    for definition in config.definitions:
        for dependency in definition.hard_dependencies:
            cross_domain_dependencies.append(
                {
                    "kind": "HARD",
                    "sourceDomain": definition.id,
                    "targetDomain": dependency,
                    "relation": "DEPENDS_ON",
                    "disposition": "CONFIGURED",
                    "sourceKey": None,
                    "targetKey": None,
                    "candidateId": None,
                    "evidence": [f"domain-config:{definition.id}:hardDependency:{dependency}"],
                }
            )
    for edge in getattr(graph, "edges", ()):
        source_route = route_by_key.get(getattr(edge, "source", ""))
        target_route = route_by_key.get(getattr(edge, "target", ""))
        if source_route is None or target_route is None:
            continue
        if source_route.primary_domain == target_route.primary_domain:
            continue
        cross_domain_dependencies.append(
            {
                "kind": "SOFT",
                "sourceDomain": source_route.primary_domain,
                "targetDomain": target_route.primary_domain,
                "relation": edge.relation,
                "disposition": edge.disposition,
                "sourceKey": edge.source,
                "targetKey": edge.target,
                "candidateId": edge.candidate_id,
                "evidence": list(edge.evidence),
            }
        )
    cross_domain_dependencies.sort(
        key=lambda item: (
            item["kind"], item["sourceDomain"], item["targetDomain"],
            item["relation"], item["sourceKey"] or "", item["targetKey"] or "",
            item["candidateId"] or "",
        )
    )
    graph_binding = _plain(coverage.graph_binding)
    coverage_binding = _coverage_binding(coverage)
    primary_by_domain: dict[str, list[dict[str, Any]]] = {domain: [] for domain in DOMAIN_SLUGS}
    secondary_by_domain: dict[str, list[str]] = {domain: [] for domain in DOMAIN_SLUGS}
    unresolved_by_domain: dict[str, list[dict[str, Any]]] = {domain: [] for domain in DOMAIN_SLUGS}
    for route in routes:
        row = row_by_key[route.row_key]
        coverage_row = coverage_by_key[route.row_key]
        primary_by_domain[route.primary_domain].append(_assignment_payload(route, row, coverage_row))
        for domain in route.secondary_domains:
            secondary_by_domain[domain].append(route.row_key)
        if route.disposition != "PROVEN":
            unresolved_by_domain[route.primary_domain].append(
                {
                    "rowKey": route.row_key,
                    "provisionalPrimary": route.primary_domain,
                    "candidateDomains": list(route.candidate_domains),
                    "routingBasis": route.basis,
                    "reason": "typed domain identity is absent, candidate, or conflicts across domains",
                    "evidence": list(route.evidence),
                }
            )
    config_by_id = config.by_id
    cores: dict[str, dict[str, Any]] = {}
    for domain_id in sorted(DOMAIN_SLUGS):
        definition = config_by_id[domain_id]
        primary = sorted(primary_by_domain[domain_id], key=lambda item: (item["rowKey"].casefold(), item["rowKey"]))
        secondary = sorted(set(secondary_by_domain[domain_id]), key=lambda item: (item.casefold(), item))
        unresolved = sorted(unresolved_by_domain[domain_id], key=lambda item: (item["rowKey"].casefold(), item["rowKey"]))
        cores[domain_id] = {
            "recordType": "DOMAIN_PACKAGE",
            "schemaVersion": SCHEMA_VERSION,
            "routingPolicy": {"version": ROUTING_POLICY_VERSION, "sha256": ROUTING_POLICY_SHA256},
            "graphBinding": graph_binding,
            "coverageBinding": coverage_binding,
            "coverageGate": {
                "status": "STRUCTURAL_FATAL" if coverage.fatals else "PASS",
                "globalFatals": [_fatal_payload(item) for item in coverage.global_fatals],
            },
            "domain": {
                "id": definition.id,
                "slug": definition.slug,
                "hardDependencies": list(definition.hard_dependencies),
                "planRefs": [{"path": item.path, "sha256": item.sha256} for item in definition.plan_refs],
            },
            "topologicalOrder": list(config.topological_order),
            "crossDomainDependencies": cross_domain_dependencies,
            "primaryRows": primary,
            "secondaryRowKeys": secondary,
            "crossDomainUnresolved": unresolved,
            "conservation": {
                "globalSourceRowCount": len(rows),
                "primaryRowCount": len(primary),
                "secondaryRowCount": len(secondary),
                "crossDomainUnresolvedCount": len(unresolved),
                "crossDomainDependencyCount": len(cross_domain_dependencies),
            },
            "bindings": {
                "configSha256": config.config_sha256,
                "routeSurfaceSha256": route_surface_sha,
            },
        }
    core_hashes = {f"{domain}.json": _sha(core) for domain, core in sorted(cores.items())}
    package_set_sha = _sha(
        {
            "schemaVersion": SCHEMA_VERSION,
            "routingPolicySha256": ROUTING_POLICY_SHA256,
            "routeSurfaceSha256": route_surface_sha,
            "packageCoreSha256ByFile": core_hashes,
        }
    )
    files: list[tuple[str, bytes]] = []
    for domain_id, core in sorted(cores.items()):
        payload = dict(core)
        payload["bindings"] = {
            **core["bindings"],
            "packageCoreSha256": core_hashes[f"{domain_id}.json"],
            "packageSetSha256": package_set_sha,
            "packageCoreSha256ByFile": core_hashes,
        }
        files.append((f"{domain_id}.json", canonical_json(payload).encode("utf-8")))
    unresolved_count = sum(route.disposition != "PROVEN" for route in routes)
    coverage_gate_status = "STRUCTURAL_FATAL" if coverage.fatals else "PASS"
    conservation = {
        "domainCount": len(files),
        "sourceRowCount": len(rows),
        "primaryAssignmentCount": len(routes),
        "unresolvedRoutingCount": unresolved_count,
        "crossDomainDependencyCount": len(cross_domain_dependencies),
    }
    return DomainPackageSet(
        tuple(files), route_surface_sha, package_set_sha, len(routes), unresolved_count,
        coverage_gate_status, conservation,
    )


def domain_package_files(package_set: DomainPackageSet) -> Mapping[str, bytes]:
    return MappingProxyType(dict(package_set.files))


def load_domain_packages(directory: str | Path, *, graph: Any, coverage: CoverageReport, config: DomainConfig) -> DomainPackageSet:
    """Require the exact sixteen canonical files and reproduce them independently."""

    root = Path(os.path.abspath(directory))
    _reject_link_chain(root, "domain package directory")
    if not root.is_dir():
        raise ValueError("domain package directory is missing")
    expected_names = {f"D{number:02d}.json" for number in range(1, 17)}
    actual_names = {item.name for item in root.iterdir()}
    missing = sorted(expected_names - actual_names)
    extra = sorted(actual_names - expected_names)
    if missing or extra:
        raise ValueError(f"domain package artifact file set mismatch; missing={missing}, extra={extra}")
    actual: dict[str, bytes] = {}
    for name in sorted(expected_names):
        path = root / name
        _reject_link_chain(path, name)
        value = _read_canonical(path, name)
        actual[name] = canonical_json(value).encode("utf-8")
    expected = build_domain_packages(graph, coverage, config)
    expected_files = dict(expected.files)
    for name in sorted(expected_names):
        if actual[name] != expected_files[name]:
            raise ValueError(f"domain package hash/binding/reproducibility mismatch: {name}")
    return expected


__all__ = [
    "DOMAIN_SLUGS", "ROUTING_POLICY_SHA256", "TOPOLOGY_PLAN",
    "DomainConfig", "DomainDefinition", "DomainPackageSet", "PlanReference", "RouteAssignment",
    "build_domain_packages", "domain_package_files", "load_domain_config", "load_domain_packages",
]
