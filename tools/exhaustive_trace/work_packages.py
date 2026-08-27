"""Fail-closed implementation and validation planning from Task 11 domain packages."""

from __future__ import annotations

import hashlib
import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from types import MappingProxyType
from typing import Any, Mapping, Sequence

from .domains import DOMAIN_SLUGS, DomainPackageSet
from .io import canonical_json
from .model import ImplementationTarget, freeze_json


SCHEMA_VERSION = 1
POLICY_VERSION = "TASK12-1"
FEATURE_UNIT_KINDS = (
    "reverse_contract", "versioned_contract", "authority_server",
    "legacy_gateway", "new_client", "database_replay", "content_admin",
    "qa_independent_review",
)
UNIT_TARGETS = MappingProxyType({
    "reverse_contract": (),
    "versioned_contract": ("CONTRACT",),
    "authority_server": ("SERVER",),
    "legacy_gateway": ("LEGACY_GATEWAY",),
    "new_client": ("NEW_CLIENT",),
    "database_replay": ("DATABASE",),
    "content_admin": ("CONTENT_ADMIN",),
    "qa_independent_review": ("QA", "INDEPENDENT_REVIEW"),
})
TARGETS = tuple(target.value for target in ImplementationTarget)
ALLOWED_CANDIDATE_PROVENANCE = frozenset({"INFERRED", "NEW_DESIGN", "AUTHORED_PLACEHOLDER"})


def _sha(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest().upper()


def _text(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be non-empty text")
    return value


def _plain(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {key: _plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain(item) for item in value]
    return value


def _object_no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate work-package JSON key: {key}")
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


@dataclass(frozen=True)
class FeatureDefinition:
    feature_key: str
    domain: str
    source_row_keys: tuple[str, ...]
    provenance: str
    recovery_disposition: str
    first_missing_boundary: str
    evidence: tuple[str, ...]
    original_fact_status: str = "UNADJUDICATED"

    def __post_init__(self) -> None:
        _text(self.feature_key, "feature key")
        if not self.feature_key.startswith("FEATURE:"):
            raise ValueError("feature key must use FEATURE namespace")
        if self.domain not in DOMAIN_SLUGS:
            raise ValueError("feature domain must be D01-D16")
        if self.provenance not in ALLOWED_CANDIDATE_PROVENANCE:
            raise ValueError("candidate feature provenance cannot claim an original fact")
        if self.original_fact_status != "UNADJUDICATED":
            raise ValueError("candidate feature original fact status must remain UNADJUDICATED")
        for name, values in (("source row keys", self.source_row_keys), ("feature evidence", self.evidence)):
            if not isinstance(values, (list, tuple)) or not values or any(not isinstance(v, str) or not v.strip() for v in values):
                raise ValueError(f"{name} must contain non-empty text")
        if len(self.source_row_keys) != len(set(self.source_row_keys)):
            raise ValueError("duplicate feature source row")
        object.__setattr__(self, "source_row_keys", tuple(sorted(
            self.source_row_keys, key=lambda item: (item.casefold(), item)
        )))
        object.__setattr__(self, "evidence", tuple(sorted(set(self.evidence))))
        _text(self.recovery_disposition, "feature recovery disposition")
        _text(self.first_missing_boundary, "feature first missing boundary")


@dataclass(frozen=True)
class WorkUnit:
    unit_id: str
    kind: str
    domain: str
    path_key: str
    source_row_keys: tuple[str, ...]
    first_missing_boundary: str
    missing_boundaries: tuple[str, ...]
    question: str
    input_evidence: tuple[str, ...]
    expected_output: str
    verifier_argv: tuple[str, ...]
    mutation_scope: Mapping[str, Any]
    mutates_runtime: bool
    live_input_count: int
    live_slice: Mapping[str, Any]
    faction_role_matrix: Mapping[str, Any]
    static_exporter_requirements: tuple[str, ...]
    offline_replay_inputs: tuple[str, ...]
    independent_review_required: bool
    forbidden_retry: str
    recovery_disposition: str
    targets: tuple[str, ...]
    implementation_target_matrix: Mapping[str, Any]
    depends_on_unit_ids: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "source_row_keys", tuple(self.source_row_keys))
        object.__setattr__(self, "missing_boundaries", tuple(self.missing_boundaries))
        object.__setattr__(self, "input_evidence", tuple(self.input_evidence))
        object.__setattr__(self, "verifier_argv", tuple(self.verifier_argv))
        object.__setattr__(self, "mutation_scope", freeze_json(self.mutation_scope))
        object.__setattr__(self, "live_slice", freeze_json(self.live_slice))
        object.__setattr__(self, "faction_role_matrix", freeze_json(self.faction_role_matrix))
        object.__setattr__(self, "static_exporter_requirements", tuple(self.static_exporter_requirements))
        object.__setattr__(self, "offline_replay_inputs", tuple(self.offline_replay_inputs))
        object.__setattr__(self, "targets", tuple(self.targets))
        object.__setattr__(self, "implementation_target_matrix", freeze_json(self.implementation_target_matrix))
        object.__setattr__(self, "depends_on_unit_ids", tuple(self.depends_on_unit_ids))


@dataclass(frozen=True)
class FeatureWorkPackage:
    feature_key: str
    domain: str
    provenance: str
    recovery_disposition: str
    first_missing_boundary: str
    source_row_keys: tuple[str, ...]
    evidence: tuple[str, ...]
    original_fact_status: str
    coverage_promotion: bool
    units: tuple[WorkUnit, ...]

    def __post_init__(self) -> None:
        object.__setattr__(self, "source_row_keys", tuple(self.source_row_keys))
        object.__setattr__(self, "evidence", tuple(self.evidence))
        object.__setattr__(self, "units", tuple(self.units))


@dataclass(frozen=True)
class WorkPackagePlan:
    bindings: Mapping[str, Any]
    coverage_gate_status: str
    global_fatals: tuple[Mapping[str, Any], ...]
    feature_ledger_status: str
    confirmed_features: tuple[FeatureWorkPackage, ...]
    candidate_feature_packages: tuple[FeatureWorkPackage, ...]
    recovery_units: tuple[WorkUnit, ...]
    conservation: Mapping[str, Any]
    recovery_surface_sha256: str
    feature_surface_sha256: str
    plan_surface_sha256: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "bindings", freeze_json(self.bindings))
        object.__setattr__(self, "global_fatals", tuple(freeze_json(item) for item in self.global_fatals))
        object.__setattr__(self, "confirmed_features", tuple(self.confirmed_features))
        object.__setattr__(self, "candidate_feature_packages", tuple(self.candidate_feature_packages))
        object.__setattr__(self, "recovery_units", tuple(self.recovery_units))
        object.__setattr__(self, "conservation", freeze_json(self.conservation))


def _decode_packages(package_set: DomainPackageSet) -> tuple[list[Mapping[str, Any]], dict[str, Mapping[str, Any]], Mapping[str, Any]]:
    files = dict(package_set.files)
    expected = {f"D{i:02d}.json" for i in range(1, 17)}
    if set(files) != expected:
        raise ValueError("work-package input must contain exact D01-D16 files")
    payloads = []
    rows: dict[str, Mapping[str, Any]] = {}
    common: Mapping[str, Any] | None = None
    for name in sorted(files):
        try:
            value = json.loads(files[name].decode("utf-8"), object_pairs_hook=_object_no_duplicates)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError(f"invalid domain package: {name}") from error
        if not isinstance(value, Mapping) or canonical_json(value).encode("utf-8") != files[name]:
            raise ValueError(f"noncanonical domain package: {name}")
        domain_id = value.get("domain", {}).get("id")
        if domain_id != name[:-5]:
            raise ValueError("domain package filename/domain mismatch")
        signature = {
            "packageSetSha256": value.get("bindings", {}).get("packageSetSha256"),
            "routeSurfaceSha256": value.get("bindings", {}).get("routeSurfaceSha256"),
            "configSha256": value.get("bindings", {}).get("configSha256"),
            "routingPolicySha256": value.get("routingPolicy", {}).get("sha256"),
            "graphSurfaceSha256": value.get("graphBinding", {}).get("graphSurfaceSha256"),
            "coverageSurfaceSha256": value.get("coverageBinding", {}).get("coverageSurfaceSha256"),
            "coverageGate": value.get("coverageGate"),
            "topologicalOrder": value.get("topologicalOrder"),
        }
        if common is None:
            common = signature
        elif signature != common:
            raise ValueError("domain package bindings disagree")
        primary = value.get("primaryRows")
        if not isinstance(primary, list):
            raise ValueError("domain primaryRows must be a list")
        for row in primary:
            if not isinstance(row, Mapping):
                raise ValueError("domain primary row must be an object")
            key = _text(row.get("rowKey"), "row key")
            if key in rows:
                raise ValueError("duplicate primary row key")
            if row.get("primaryDomain") != domain_id:
                raise ValueError("primary row/domain mismatch")
            rows[key] = row
        payloads.append(value)
    assert common is not None
    if common["packageSetSha256"] != package_set.package_set_sha256:
        raise ValueError("domain package-set binding mismatch")
    if common["routeSurfaceSha256"] != package_set.route_surface_sha256:
        raise ValueError("domain route-surface binding mismatch")
    return payloads, rows, common


def _mutation_scope(*, repository_targets: Sequence[str] = ()) -> Mapping[str, Any]:
    return {
        "targetSystem": "REPOSITORY" if repository_targets else "NONE",
        "repositoryMutation": bool(repository_targets),
        "repositoryTargets": list(repository_targets),
        "observedRuntimeMutation": False,
        "originalBinaryWrite": False,
        "processMemoryWrite": False,
        "oracleServerMutation": False,
        "oracleProtocolMutation": False,
        "oracleDatabaseMutation": False,
        "vmLifecycleMutation": False,
    }


def _live_slice() -> Mapping[str, Any]:
    return {
        "bootstrapOnly": True,
        "semanticPlayerActionCount": 0,
        "semanticActions": [],
        "attemptLimit": 0,
        "automaticRetry": False,
        "permitRequired": True,
        "freshPidHwndRequired": True,
        "singleWriterRequired": True,
    }


def _faction_role_matrix() -> Mapping[str, Any]:
    return {
        "status": "UNRESOLVED",
        "requiredFactions": ["EMPIRE", "ALLIANCE"],
        "requiredRoleCoverage": "ALL_ORIGINAL_ROLES",
        "evidence": ["goal:both-factions-and-role-parity"],
    }


def _unknown_matrix(unit_id: str, evidence: Sequence[str]) -> Mapping[str, Any]:
    return {
        target: {
            "status": "UNKNOWN",
            "reason": "target applicability awaits recovery of the first missing boundary",
            "evidence": list(evidence),
            "ownerUnitId": unit_id,
        }
        for target in TARGETS
    }


def _required_matrix(owner_by_target: Mapping[str, str], evidence: Sequence[str]) -> Mapping[str, Any]:
    return {
        target: {
            "status": "REQUIRED",
            "reason": f"candidate feature planning requires explicit {target} closure",
            "evidence": list(evidence),
            "ownerUnitId": owner_by_target[target],
        }
        for target in TARGETS
    }


def _recovery_unit(domain: str, row: Mapping[str, Any], package_set_sha: str) -> WorkUnit:
    key = str(row["rowKey"])
    boundary = _text(row.get("firstMissingBoundary"), "first missing boundary")
    evidence = tuple(sorted(set((f"domain-package:{package_set_sha}:row:{key}", *row.get("routingEvidence", ())))))
    digest = hashlib.sha256(f"{domain}\0{boundary}\0{key}".encode("utf-8")).hexdigest()[:16].upper()
    unit_id = f"RECOVERY:{domain}:{boundary}:{digest}"
    return WorkUnit(
        unit_id=unit_id,
        kind="reverse_contract",
        domain=domain,
        path_key=key,
        source_row_keys=(key,),
        first_missing_boundary=boundary,
        missing_boundaries=(boundary,),
        question=f"What exact original evidence closes {boundary} for {key}?",
        input_evidence=evidence,
        expected_output=f"A hash-bound adjudication for {key} at boundary {boundary}, without evidence-state promotion.",
        verifier_argv=("python", "-m", "unittest", "tests.tools.exhaustive_trace.test_work_packages"),
        mutation_scope=_mutation_scope(),
        mutates_runtime=False,
        live_input_count=0,
        live_slice=_live_slice(),
        faction_role_matrix=_faction_role_matrix(),
        static_exporter_requirements=(f"Export only evidence needed for {boundary}; retain exact inventory key {key}.",),
        offline_replay_inputs=(f"domain-package-row:{key}",),
        independent_review_required=True,
        forbidden_retry="Do not auto-click, auto-retry, mutate the oracle, or erase the unresolved row.",
        recovery_disposition=_text(row.get("recoveryDisposition"), "recovery disposition"),
        targets=(),
        implementation_target_matrix=_unknown_matrix(unit_id, evidence),
    )


def _feature_package(feature: FeatureDefinition, rows: Mapping[str, Mapping[str, Any]]) -> FeatureWorkPackage:
    for key in feature.source_row_keys:
        if key not in rows:
            raise ValueError(f"unknown feature source row: {key}")
        row = rows[key]
        if row.get("primaryDomain") != feature.domain:
            raise ValueError(f"feature source domain mismatch: {key}")
        if row.get("firstMissingBoundary") != feature.first_missing_boundary:
            raise ValueError(f"feature source first missing boundary mismatch: {key}")
        if row.get("recoveryDisposition") != feature.recovery_disposition:
            raise ValueError(f"feature source recovery disposition mismatch: {key}")
    unit_ids = {kind: f"{feature.feature_key}:{kind.upper()}" for kind in FEATURE_UNIT_KINDS}
    owner_by_target = {
        target: unit_ids[kind]
        for kind in FEATURE_UNIT_KINDS
        for target in UNIT_TARGETS[kind]
    }
    evidence = tuple(sorted(set((*feature.evidence, *(f"source-row:{key}" for key in feature.source_row_keys)))))
    matrix = _required_matrix(owner_by_target, evidence)
    units: list[WorkUnit] = []
    for index, kind in enumerate(FEATURE_UNIT_KINDS):
        targets = UNIT_TARGETS[kind]
        repository_targets = targets if kind not in {"reverse_contract", "qa_independent_review"} else ()
        if kind == "reverse_contract":
            dependencies: tuple[str, ...] = ()
        elif kind == "versioned_contract":
            dependencies = (unit_ids["reverse_contract"],)
        elif kind == "qa_independent_review":
            dependencies = tuple(unit_ids[item] for item in FEATURE_UNIT_KINDS[:-1])
        else:
            dependencies = (unit_ids["versioned_contract"],)
        units.append(WorkUnit(
            unit_id=unit_ids[kind],
            kind=kind,
            domain=feature.domain,
            path_key=feature.feature_key,
            source_row_keys=feature.source_row_keys,
            first_missing_boundary=feature.first_missing_boundary,
            missing_boundaries=(feature.first_missing_boundary,),
            question=f"What evidence and implementation output closes {kind} for {feature.feature_key}?",
            input_evidence=evidence,
            expected_output=f"A versioned {kind} deliverable for {feature.feature_key}, retaining INFERRED/UNADJUDICATED status.",
            verifier_argv=("python", "-m", "unittest", "tests.tools.exhaustive_trace.test_work_packages"),
            mutation_scope=_mutation_scope(repository_targets=repository_targets),
            mutates_runtime=False,
            live_input_count=0,
            live_slice=_live_slice(),
            faction_role_matrix=_faction_role_matrix(),
            static_exporter_requirements=("Use only hash-bound source rows; do not infer identity from display names alone.",),
            offline_replay_inputs=tuple(f"source-row:{key}" for key in feature.source_row_keys),
            independent_review_required=True,
            forbidden_retry="No automatic click or retry; any future live slice requires a fresh identity and separate receipt.",
            recovery_disposition=feature.recovery_disposition,
            targets=targets,
            implementation_target_matrix=matrix,
            depends_on_unit_ids=dependencies,
        ))
    return FeatureWorkPackage(
        feature.feature_key, feature.domain, feature.provenance,
        feature.recovery_disposition, feature.first_missing_boundary,
        feature.source_row_keys, feature.evidence, feature.original_fact_status,
        False, tuple(units),
    )


def _unit_payload(unit: WorkUnit) -> Mapping[str, Any]:
    return {
        "unitId": unit.unit_id,
        "kind": unit.kind,
        "domain": unit.domain,
        "pathKey": unit.path_key,
        "sourceRowKeys": list(unit.source_row_keys),
        "firstMissingBoundary": unit.first_missing_boundary,
        "missingBoundaries": list(unit.missing_boundaries),
        "question": unit.question,
        "inputEvidence": list(unit.input_evidence),
        "expectedOutput": unit.expected_output,
        "verifierArgv": list(unit.verifier_argv),
        "mutationScope": _plain(unit.mutation_scope),
        "mutatesRuntime": unit.mutates_runtime,
        "liveInputCount": unit.live_input_count,
        "liveSlice": _plain(unit.live_slice),
        "factionRoleMatrix": _plain(unit.faction_role_matrix),
        "staticExporterRequirements": list(unit.static_exporter_requirements),
        "offlineReplayInputs": list(unit.offline_replay_inputs),
        "independentReviewRequired": unit.independent_review_required,
        "forbiddenRetry": unit.forbidden_retry,
        "recoveryDisposition": unit.recovery_disposition,
        "targets": list(unit.targets),
        "implementationTargetMatrix": _plain(unit.implementation_target_matrix),
        "dependsOnUnitIds": list(unit.depends_on_unit_ids),
    }


def _feature_payload(package: FeatureWorkPackage) -> Mapping[str, Any]:
    return {
        "featureKey": package.feature_key,
        "domain": package.domain,
        "provenance": package.provenance,
        "recoveryDisposition": package.recovery_disposition,
        "firstMissingBoundary": package.first_missing_boundary,
        "sourceRowKeys": list(package.source_row_keys),
        "evidence": list(package.evidence),
        "originalFactStatus": package.original_fact_status,
        "coveragePromotion": package.coverage_promotion,
        "units": [_unit_payload(unit) for unit in package.units],
    }


def build_work_packages(package_set: DomainPackageSet, *, candidate_features: Sequence[FeatureDefinition] = ()) -> WorkPackagePlan:
    """Build planning units without promoting routing, coverage, or original facts."""

    payloads, rows, common = _decode_packages(package_set)
    recovery_units = tuple(
        _recovery_unit(str(payload["domain"]["id"]), row, package_set.package_set_sha256)
        for payload in payloads
        for row in payload["primaryRows"]
        if row.get("coverageVerdict") != "PASS"
    )
    recovery_units = tuple(sorted(recovery_units, key=lambda unit: (
        list(common["topologicalOrder"]).index(unit.domain), unit.first_missing_boundary,
        unit.path_key.casefold(), unit.path_key,
    )))
    seen_features: set[str] = set()
    features: list[FeatureWorkPackage] = []
    for definition in sorted(candidate_features, key=lambda item: (item.domain, item.feature_key.casefold(), item.feature_key)):
        if definition.feature_key.casefold() in seen_features:
            raise ValueError("duplicate candidate feature key")
        seen_features.add(definition.feature_key.casefold())
        features.append(_feature_package(definition, rows))
    global_fatals = tuple(common["coverageGate"].get("globalFatals", ()))
    fatal_ids = {item.get("ruleId") for item in global_fatals if isinstance(item, Mapping)}
    feature_status = "ABSENT" if "FEATURE_REACHABILITY_LEDGER_ABSENT" in fatal_ids else "UNPROVIDED"
    recovery_payloads = [_unit_payload(unit) for unit in recovery_units]
    feature_payloads = [_feature_payload(item) for item in features]
    open_keys = {unit.source_row_keys[0] for unit in recovery_units}
    routing_unresolved = {key for key, row in rows.items() if row.get("routingDisposition") != "PROVEN"}
    boundary_counts: dict[str, int] = {}
    disposition_counts: dict[str, int] = {}
    for unit in recovery_units:
        boundary_counts[unit.first_missing_boundary] = boundary_counts.get(unit.first_missing_boundary, 0) + 1
        disposition_counts[unit.recovery_disposition] = disposition_counts.get(unit.recovery_disposition, 0) + 1
    conservation = {
        "domainCount": 16,
        "sourceRowCount": len(rows),
        "openRowCount": len(open_keys),
        "recoveryUnitCount": len(recovery_units),
        "recoveryCoveredRowCount": len(open_keys),
        "uncoveredOpenRowCount": 0,
        "routingUnresolvedRowCount": len(routing_unresolved),
        "confirmedGameplayFeatureCount": 0,
        "candidateGameplayFeatureCount": len(features),
        "candidateFeatureUnitCount": sum(len(item.units) for item in features),
        "firstMissingBoundaryCounts": dict(sorted(boundary_counts.items())),
        "recoveryDispositionCounts": dict(sorted(disposition_counts.items())),
        "maxLiveInputCount": max((unit.live_input_count for unit in (*recovery_units, *(u for f in features for u in f.units))), default=0),
        "automaticRetryUnitCount": 0,
        "runtimeMutationUnitCount": 0,
    }
    bindings = {
        "packageSetSha256": package_set.package_set_sha256,
        "routeSurfaceSha256": package_set.route_surface_sha256,
        "configSha256": common["configSha256"],
        "routingPolicySha256": common["routingPolicySha256"],
        "graphSurfaceSha256": common["graphSurfaceSha256"],
        "coverageSurfaceSha256": common["coverageSurfaceSha256"],
    }
    recovery_sha = _sha(recovery_payloads)
    feature_sha = _sha(feature_payloads)
    surface = {
        "policyVersion": POLICY_VERSION,
        "bindings": bindings,
        "coverageGateStatus": common["coverageGate"]["status"],
        "globalFatals": list(global_fatals),
        "featureLedgerStatus": feature_status,
        "confirmedFeatures": [],
        "candidateFeaturePackages": feature_payloads,
        "recoveryUnits": recovery_payloads,
        "conservation": conservation,
        "recoverySurfaceSha256": recovery_sha,
        "featureSurfaceSha256": feature_sha,
    }
    return WorkPackagePlan(
        bindings, common["coverageGate"]["status"], global_fatals, feature_status,
        (), tuple(features), recovery_units, conservation, recovery_sha, feature_sha,
        _sha(surface),
    )


def _plan_payload(plan: WorkPackagePlan) -> Mapping[str, Any]:
    return {
        "recordType": "DOMAIN_PLAN_INPUTS",
        "schemaVersion": SCHEMA_VERSION,
        "policy": {"version": POLICY_VERSION, "sha256": _sha({
            "version": POLICY_VERSION, "featureUnitKinds": list(FEATURE_UNIT_KINDS),
            "unitTargets": {key: list(value) for key, value in UNIT_TARGETS.items()},
            "candidateProvenance": sorted(ALLOWED_CANDIDATE_PROVENANCE),
        })},
        "bindings": _plain(plan.bindings),
        "coverageGate": {"status": plan.coverage_gate_status, "globalFatals": [_plain(item) for item in plan.global_fatals]},
        "featureLedger": {
            "status": plan.feature_ledger_status,
            "confirmedFeatureCount": len(plan.confirmed_features),
            "coveragePromotion": False,
            "reason": "No FEATURE source rows are present in the bound Task 11 packages.",
        },
        "confirmedFeatures": [_feature_payload(item) for item in plan.confirmed_features],
        "candidateFeaturePackages": [_feature_payload(item) for item in plan.candidate_feature_packages],
        "recoveryUnits": [_unit_payload(unit) for unit in plan.recovery_units],
        "conservation": _plain(plan.conservation),
        "recoverySurfaceSha256": plan.recovery_surface_sha256,
        "featureSurfaceSha256": plan.feature_surface_sha256,
        "planSurfaceSha256": plan.plan_surface_sha256,
    }


def work_packages_json(plan: WorkPackagePlan) -> str:
    return canonical_json(_plan_payload(plan))


def _validate_unit_shape(unit: Mapping[str, Any], *, recovery: bool) -> None:
    if recovery and (not isinstance(unit.get("sourceRowKeys"), list) or len(unit["sourceRowKeys"]) != 1):
        raise ValueError("recovery unit must contain exactly one source row")
    if not isinstance(unit.get("missingBoundaries"), list) or len(unit["missingBoundaries"]) != 1:
        raise ValueError("unit must contain exactly one first missing boundary")
    if unit.get("missingBoundaries", [None])[0] != unit.get("firstMissingBoundary"):
        raise ValueError("unit first missing boundary mismatch")
    for name in ("question", "expectedOutput", "forbiddenRetry"):
        _text(unit.get(name), name)
    for name in ("inputEvidence", "verifierArgv"):
        values = unit.get(name)
        if not isinstance(values, list) or not values or any(not isinstance(v, str) or not v.strip() for v in values):
            raise ValueError(f"{name} must contain non-empty text")
    count = unit.get("liveInputCount")
    if type(count) is not int or count not in {0, 1}:
        raise ValueError("liveInputCount must be integer zero or one")
    live = unit.get("liveSlice")
    if not isinstance(live, Mapping) or live.get("automaticRetry") is not False:
        raise ValueError("automaticRetry must be false")
    if count != live.get("semanticPlayerActionCount"):
        raise ValueError("liveInputCount and semantic action count differ")
    if count == 0 and live.get("semanticActions") != []:
        raise ValueError("zero-input unit cannot declare semantic actions")
    scope = unit.get("mutationScope")
    if not isinstance(scope, Mapping):
        raise ValueError("mutationScope is required")
    for field_name in (
        "originalBinaryWrite", "processMemoryWrite", "oracleServerMutation",
        "oracleProtocolMutation", "oracleDatabaseMutation", "vmLifecycleMutation",
    ):
        if scope.get(field_name) is not False:
            raise ValueError(f"{field_name} must be false")
    if unit.get("mutatesRuntime") is not False:
        raise ValueError("Task12 units must not mutate runtime")
    matrix = unit.get("implementationTargetMatrix")
    if not isinstance(matrix, Mapping) or set(matrix) != set(TARGETS):
        raise ValueError("implementation target matrix must contain exact targets")
    for target, entry in matrix.items():
        if not isinstance(entry, Mapping) or entry.get("status") not in {"REQUIRED", "UNKNOWN", "NOT_APPLICABLE"}:
            raise ValueError("implementation target matrix status mismatch")
        if entry.get("status") == "NOT_APPLICABLE":
            if not isinstance(entry.get("reason"), str) or not entry["reason"].strip() or not entry.get("evidence"):
                raise ValueError(f"NOT_APPLICABLE target {target} requires reason and evidence")


def validate_work_package_payload(value: Mapping[str, Any], *, packages: DomainPackageSet,
                                  candidate_features: Sequence[FeatureDefinition] = ()) -> WorkPackagePlan:
    """Validate local invariants, then reproduce the complete artifact from live inputs."""

    if not isinstance(value, Mapping):
        raise ValueError("work-package payload must be an object")
    for unit in value.get("recoveryUnits", ()):
        if not isinstance(unit, Mapping):
            raise ValueError("recovery unit must be an object")
        _validate_unit_shape(unit, recovery=True)
    for feature in value.get("candidateFeaturePackages", ()):
        if not isinstance(feature, Mapping):
            raise ValueError("candidate feature package must be an object")
        units = feature.get("units")
        if not isinstance(units, list) or [unit.get("kind") for unit in units if isinstance(unit, Mapping)] != list(FEATURE_UNIT_KINDS):
            raise ValueError("candidate feature unit kind order mismatch")
        for unit in units:
            _validate_unit_shape(unit, recovery=False)
    expected = build_work_packages(packages, candidate_features=candidate_features)
    if value != _plan_payload(expected):
        raise ValueError("work-package binding/hash/conservation/reproducibility mismatch")
    return expected


def load_work_packages_json(path: str | Path, *, packages: DomainPackageSet,
                            candidate_features: Sequence[FeatureDefinition] = ()) -> WorkPackagePlan:
    artifact = Path(os.path.abspath(path))
    _reject_link_chain(artifact, "work-package artifact")
    data = artifact.read_bytes()
    if data.startswith(b"\xef\xbb\xbf") or not data.endswith(b"\n") or b"\r" in data:
        raise ValueError("work-package artifact must be canonical UTF-8 LF JSON")
    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=_object_no_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("work-package artifact is invalid JSON") from error
    if not isinstance(value, Mapping) or canonical_json(value).encode("utf-8") != data:
        raise ValueError("work-package artifact is not canonical JSON")
    return validate_work_package_payload(value, packages=packages, candidate_features=candidate_features)


def inferred_move_grid_candidate(package_set: DomainPackageSet) -> tuple[FeatureDefinition, ...]:
    """Return a segregated planning candidate only when the exact observed notify anchor exists."""

    _, rows, _ = _decode_packages(package_set)
    anchor = "PROTOCOL:MESSAGE16:0x0B07"
    if anchor not in rows:
        return ()
    row = rows[anchor]
    if row.get("primaryDomain") != "D06" or row.get("routingDisposition") != "PROVEN":
        return ()
    return (FeatureDefinition(
        "FEATURE:MOVE_GRID", "D06", (anchor,), "INFERRED", "RECOVERABLE_STATIC",
        _text(row.get("firstMissingBoundary"), "move-grid first missing boundary"),
        ("planning-inference:NotifyMovedGrid-does-not-prove-request-identity", *tuple(row.get("routingEvidence", ()))),
        "UNADJUDICATED",
    ),)


__all__ = [
    "FEATURE_UNIT_KINDS", "FeatureDefinition", "FeatureWorkPackage", "WorkPackagePlan",
    "WorkUnit", "build_work_packages", "inferred_move_grid_candidate",
    "load_work_packages_json", "validate_work_package_payload", "work_packages_json",
]
