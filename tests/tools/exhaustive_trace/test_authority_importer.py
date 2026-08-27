from __future__ import annotations

import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from tools.exhaustive_trace.import_authority import (
    AUTHORITY_SECTION_NAMES,
    build_authority_inventory,
    build_authority_reconciliation,
    build_authority_source,
    normalize_authority_inventory,
)


IMPLEMENTATION_TARGETS = {
    "CONTRACT",
    "SERVER",
    "LEGACY_GATEWAY",
    "NEW_CLIENT",
    "DATABASE",
    "CONTENT_ADMIN",
    "QA",
    "INDEPENDENT_REVIEW",
}


def states() -> dict[str, bool]:
    return {
        "ENUMERATED": True,
        "STATIC_MAPPED": False,
        "CODEC_PROVEN": False,
        "RUNTIME_OBSERVED": False,
        "PLAYER_VISIBLE": False,
        "AUTHORITY_PROVEN": False,
        "PERSISTENCE_PROVEN": False,
        "BOTH_FACTIONS": False,
        "INDEPENDENTLY_REVIEWED": False,
    }


def protocol_row(
    key: str = "PROTOCOL:MESSAGE16:0x0200",
    direction: str = "CLIENT_TO_SERVER",
) -> dict[str, object]:
    return {
        "key": key,
        "inventory": "PROTOCOL",
        "name": "UnknownProtocol_0x0200",
        "provenance": "ORIGINAL_OBSERVED",
        "reachability": "UNKNOWN",
        "states": states(),
        "direction": {"status": direction, "evidence": ["raw:fixture"]},
        "evidence": ["raw:fixture"],
    }


def protocol_row_with_siblings(
    key: str, direction: str, request_codes: list[str]
) -> dict[str, object]:
    row = protocol_row(key, direction)
    row["code"] = key.rsplit(":", 1)[-1]
    row["siblings"] = {"request": {"codes": request_codes}}
    return row


def entity_row(
    key: str = "ENTITY:TYPE:FLEET",
    *,
    state_bearing: bool = True,
) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "key": key,
        "inventory": "ENTITY",
        "rowKind": "ENTITY_TYPE",
        "entityType": "FLEET",
        "name": "fleet",
        "provenance": "ORIGINAL_OBSERVED",
        "reachability": "UNKNOWN",
        "states": states(),
        "stateBearing": state_bearing,
        "evidence": ["entity:fixture"],
    }


def event_record_row() -> dict[str, object]:
    row = entity_row("ENTITY:RECORD:CommandAdmitted", state_bearing=False)
    row["rowKind"] = "RECORD_TYPE"
    row["entityType"] = "EVENT_RECORD"
    row["recordType"] = "CommandAdmitted"
    return row


def ui_row() -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "key": "UI:MODE:0x02:MANAGER:0x0B:CATEGORY:TYPE1:INDEX:0000",
        "inventory": "UI",
        "rowKind": "WIDGET",
        "name": "Mode0x02_Manager0x0B_Widget0",
        "interactionKind": "INTERACTIVE",
        "provenance": "ORIGINAL_OBSERVED",
        "reachability": "UNKNOWN",
        "states": states(),
        "evidence": ["ui:fixture"],
    }


def write_jsonl(path: Path, rows: list[dict[str, object]]) -> None:
    path.write_text(
        "".join(
            json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
            for row in rows
        ),
        encoding="utf-8",
        newline="\n",
    )


def refresh_surface(raw: dict[str, object]) -> None:
    surface = {
        name: raw[name]
        for name in (
            "upstream",
            "sourceRoots",
            "sourceFileCandidates",
            "traceMarkerCandidates",
            "requirementCandidates",
            "conservation",
            "audit",
        )
    }
    payload = json.dumps(
        surface, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ) + "\n"
    raw["surfaceSha256"] = hashlib.sha256(payload.encode("utf-8")).hexdigest().upper()


class AuthorityImporterTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.server = self.root / "apps" / "server"
        self.contracts = self.root / "contracts"
        self.database = self.root / "db"
        self.server.mkdir(parents=True)
        self.contracts.mkdir()
        (self.database / "migrations").mkdir(parents=True)
        (self.database / "seeds").mkdir()
        self.protocol = self.root / "protocol.jsonl"
        self.entities = self.root / "entities.jsonl"
        self.ui = self.root / "ui.jsonl"
        write_jsonl(
            self.protocol,
            [
                protocol_row(),
                protocol_row("PROTOCOL:MESSAGE16:0x0201", "SERVER_TO_CLIENT"),
            ],
        )
        write_jsonl(
            self.entities,
            [
                entity_row(),
                entity_row("ENTITY:RECORD:WireOnly", state_bearing=False),
                event_record_row(),
            ],
        )
        write_jsonl(self.ui, [ui_row()])

    def tearDown(self) -> None:
        self.temp.cleanup()

    def build_raw(self, **kwargs: object) -> dict[str, object]:
        return build_authority_source(
            server_root=self.server,
            contracts_root=self.contracts,
            database_root=self.database,
            protocol_inventory=self.protocol,
            entity_inventory=self.entities,
            ui_inventory=self.ui,
            **kwargs,
        )

    def test_empty_roots_emit_every_protocol_and_state_bearing_entity_as_orphans(self) -> None:
        raw = self.build_raw()
        rows = normalize_authority_inventory(build_authority_inventory(raw))
        self.assertEqual(len(rows), 5)
        self.assertEqual(
            {row["rowKind"] for row in rows},
            {"PROTOCOL_PATH", "ENTITY_PATH", "EVENT_PATH", "CLIENT_BEHAVIOR_PATH"},
        )
        source_rows = [row for row in rows if row["rowKind"] != "CLIENT_BEHAVIOR_PATH"]
        self.assertTrue(all(row["disposition"] == "ORPHAN_CURRENT_SOURCE" for row in source_rows))
        self.assertEqual(
            {row["firstMissingBoundary"] for row in source_rows},
            {"COMMAND_HANDLER", "NOTIFICATION_FANOUT", "AUTHORITY_OWNER", "EMISSION_IDENTITY"},
        )

    def test_client_to_server_path_keeps_all_authority_sections_explicit(self) -> None:
        rows = normalize_authority_inventory(build_authority_inventory(self.build_raw()))
        row = next(row for row in rows if row["key"].endswith("0x0200"))
        self.assertEqual(set(row["sections"]), AUTHORITY_SECTION_NAMES)
        self.assertEqual(row["sections"]["commandHandler"]["status"], "MISSING_CURRENT_SOURCE")
        self.assertEqual(row["sections"]["validation"]["status"], "MISSING_CURRENT_SOURCE")
        self.assertEqual(row["sections"]["decision"]["status"], "MISSING_CURRENT_SOURCE")
        self.assertEqual(row["sections"]["persistence"]["status"], "MISSING_CURRENT_SOURCE")
        self.assertEqual(row["sections"]["idempotency"]["status"], "MISSING_CURRENT_SOURCE")

    def test_server_to_client_path_does_not_invent_an_input_handler(self) -> None:
        rows = normalize_authority_inventory(build_authority_inventory(self.build_raw()))
        row = next(row for row in rows if row["key"].endswith("0x0201"))
        for section in ("commandHandler", "parsing", "validation", "decision", "idempotency"):
            self.assertEqual(row["sections"][section]["status"], "NOT_APPLICABLE_DIRECTION")
            self.assertTrue(row["sections"][section]["reason"])
        self.assertEqual(
            row["sections"]["notificationFanout"]["status"], "MISSING_CURRENT_SOURCE"
        )

    def test_response_with_request_sibling_is_not_misclassified_as_request(self) -> None:
        write_jsonl(
            self.protocol,
            [
                protocol_row_with_siblings(
                    "PROTOCOL:MESSAGE16:0x0200", "CLIENT_TO_SERVER", ["0x0200"]
                ),
                protocol_row_with_siblings(
                    "PROTOCOL:MESSAGE16:0x0201", "SERVER_TO_CLIENT", ["0x0200"]
                ),
            ],
        )
        rows = normalize_authority_inventory(build_authority_inventory(self.build_raw()))
        request = next(row for row in rows if row["key"].endswith("0x0200"))
        response = next(row for row in rows if row["key"].endswith("0x0201"))
        self.assertEqual(request["protocolRole"], "REQUEST_PATH")
        self.assertEqual(response["protocolRole"], "SERVER_OUTPUT")

    def test_entity_path_requires_authority_persistence_and_restoration(self) -> None:
        rows = normalize_authority_inventory(build_authority_inventory(self.build_raw()))
        row = next(row for row in rows if row["rowKind"] == "ENTITY_PATH")
        for section in (
            "validation",
            "mutation",
            "event",
            "persistence",
            "checkpoint",
            "replayReducer",
            "reconnectProjection",
        ):
            self.assertEqual(row["sections"][section]["status"], "MISSING_CURRENT_SOURCE")
        self.assertEqual(
            set(row["entityLifecycle"]),
            {"create", "definition", "destroy", "query", "select", "terminal", "transfer", "update"},
        )
        self.assertTrue(
            all(value["status"] == "MISSING_CURRENT_SOURCE" for value in row["entityLifecycle"].values())
        )
        self.assertEqual(row["recoveryDisposition"], "ORIGINAL_SERVER_LOST")

    def test_event_record_candidate_requires_an_emitter_and_persistence_disposition(self) -> None:
        rows = normalize_authority_inventory(build_authority_inventory(self.build_raw()))
        row = next(row for row in rows if row["rowKind"] == "EVENT_PATH")
        self.assertEqual(row["sections"]["event"]["status"], "MISSING_CURRENT_SOURCE")
        self.assertEqual(row["sections"]["persistence"]["status"], "MISSING_CURRENT_SOURCE")
        self.assertEqual(row["firstMissingBoundary"], "EMISSION_IDENTITY")

    def test_ui_behavior_is_counterpart_unresolved_not_proven_client_only(self) -> None:
        rows = normalize_authority_inventory(build_authority_inventory(self.build_raw()))
        row = next(row for row in rows if row["rowKind"] == "CLIENT_BEHAVIOR_PATH")
        self.assertEqual(row["authorityCounterpart"]["status"], "UNRESOLVED")
        self.assertEqual(row["disposition"], "AUTHORITY_COUNTERPART_UNRESOLVED")
        self.assertNotEqual(row["disposition"], "CLIENT_ONLY_PROVEN")
        self.assertEqual(row["firstMissingBoundary"], "AUTHORITY_COUNTERPART_CLASSIFICATION")

    def test_rows_do_not_promote_any_runtime_authority_or_persistence_state(self) -> None:
        rows = normalize_authority_inventory(build_authority_inventory(self.build_raw()))
        for row in rows:
            self.assertEqual(row["reachability"], "UNKNOWN")
            self.assertEqual([name for name, value in row["states"].items() if value], ["ENUMERATED"])
            self.assertEqual(set(row["stateVerdicts"]), set(states()))
            self.assertEqual(row["stateVerdicts"]["ENUMERATED"], "PASS")
            self.assertTrue(
                all(
                    verdict == "UNSEEN"
                    for name, verdict in row["stateVerdicts"].items()
                    if name != "ENUMERATED"
                )
            )

    def test_every_row_has_all_implementation_targets(self) -> None:
        rows = normalize_authority_inventory(build_authority_inventory(self.build_raw()))
        for row in rows:
            self.assertEqual(set(row["implementationDisposition"]), IMPLEMENTATION_TARGETS)
            self.assertTrue(
                all(
                    value["status"] in {"REQUIRED", "NOT_APPLICABLE"}
                    and value["reason"]
                    for value in row["implementationDisposition"].values()
                )
            )

    def test_empty_source_roots_are_explicit_unresolved_candidates(self) -> None:
        raw = self.build_raw()
        rows = build_authority_inventory(raw)
        reconciliation = build_authority_reconciliation(raw, rows)
        roots = [record for record in reconciliation["records"] if record["collection"] == "sourceRoots"]
        self.assertEqual(len(roots), 3)
        self.assertTrue(all(record["status"] == "UNRESOLVED" for record in roots))
        self.assertTrue(all(record["firstMissingBoundary"] == "NO_SOURCE_FILES" for record in roots))
        self.assertEqual(reconciliation["unaccountedCount"], 0)

    def test_source_files_are_hash_bound_but_name_matches_do_not_join(self) -> None:
        source = self.server / "FleetCommandHandler.cs"
        source.write_text("public sealed class FleetCommandHandler {}\n", encoding="utf-8")
        raw = self.build_raw()
        rows = normalize_authority_inventory(build_authority_inventory(raw))
        fleet = next(row for row in rows if row["key"] == "AUTHORITY:ENTITY:TYPE:FLEET")
        self.assertEqual(fleet["sections"]["commandHandler"]["status"], "NOT_APPLICABLE_ROW_KIND")
        self.assertEqual(fleet["sections"]["mutation"]["status"], "MISSING_CURRENT_SOURCE")
        self.assertEqual(raw["conservation"]["sourceFiles"], 1)
        self.assertRegex(raw["sourceFileCandidates"][0]["sha256"], r"^[0-9A-F]{64}$")

    def test_explicit_trace_marker_links_as_candidate_not_proof(self) -> None:
        self.server.joinpath("Handler.cs").write_text(
            "// LOGH7_TRACE target=AUTHORITY:PROTOCOL:MESSAGE16:0x0200 "
            "role=COMMAND_HANDLER status=IMPLEMENTED\n"
            "public sealed class Handler {}\n",
            encoding="utf-8",
        )
        rows = normalize_authority_inventory(build_authority_inventory(self.build_raw()))
        row = next(row for row in rows if row["key"].endswith("0x0200"))
        self.assertEqual(row["sections"]["commandHandler"]["status"], "SOURCE_CANDIDATE")
        self.assertFalse(row["states"]["STATIC_MAPPED"])
        self.assertFalse(row["states"]["AUTHORITY_PROVEN"])

    def test_stub_marker_remains_an_orphan(self) -> None:
        self.server.joinpath("Handler.cs").write_text(
            "// LOGH7_TRACE target=AUTHORITY:PROTOCOL:MESSAGE16:0x0200 "
            "role=COMMAND_HANDLER status=STUB\n"
            "throw new NotImplementedException();\n",
            encoding="utf-8",
        )
        rows = normalize_authority_inventory(build_authority_inventory(self.build_raw()))
        row = next(row for row in rows if row["key"].endswith("0x0200"))
        self.assertEqual(row["sections"]["commandHandler"]["status"], "STUB")
        self.assertEqual(row["firstMissingBoundary"], "IMPLEMENTATION_STUB")
        self.assertEqual(row["disposition"], "ORPHAN_CURRENT_SOURCE")

    def test_conflicting_markers_are_not_silently_selected(self) -> None:
        marker = (
            "// LOGH7_TRACE target=AUTHORITY:PROTOCOL:MESSAGE16:0x0200 "
            "role=VALIDATION status=IMPLEMENTED\n"
        )
        self.server.joinpath("A.cs").write_text(marker, encoding="utf-8")
        self.server.joinpath("B.cs").write_text(marker, encoding="utf-8")
        rows = normalize_authority_inventory(build_authority_inventory(self.build_raw()))
        row = next(row for row in rows if row["key"].endswith("0x0200"))
        self.assertEqual(row["sections"]["validation"]["status"], "SOURCE_CONFLICT")
        self.assertEqual(len(row["sections"]["validation"]["sourceCandidateIds"]), 2)

    def test_complete_marker_set_still_does_not_claim_authority_or_persistence(self) -> None:
        lines = [
            f"// LOGH7_TRACE target=AUTHORITY:PROTOCOL:MESSAGE16:0x0200 role={role} status=IMPLEMENTED"
            for role in (
                "COMMAND_HANDLER",
                "PARSING",
                "VALIDATION",
                "ACCEPT_DECISION",
                "REJECT_DECISION",
                "MUTATION",
                "EVENT",
                "RESPONSE",
                "NOTIFY",
                "FANOUT",
                "PERSISTENCE",
                "CHECKPOINT",
                "REPLAY_REDUCER",
                "RECONNECT_PROJECTION",
                "IDEMPOTENCY",
                "ADMIN_MUTATION",
            )
        ]
        self.server.joinpath("Complete.cs").write_text("\n".join(lines) + "\n", encoding="utf-8")
        row = next(
            row
            for row in normalize_authority_inventory(build_authority_inventory(self.build_raw()))
            if row["key"].endswith("0x0200")
        )
        self.assertFalse(row["states"]["AUTHORITY_PROVEN"])
        self.assertFalse(row["states"]["PERSISTENCE_PROVEN"])
        self.assertEqual(row["disposition"], "SOURCE_CANDIDATES_UNVERIFIED")

    def test_response_notify_and_recipient_fanout_are_independent(self) -> None:
        self.server.joinpath("Output.cs").write_text(
            "// LOGH7_TRACE target=AUTHORITY:PROTOCOL:MESSAGE16:0x0201 "
            "role=RESPONSE status=IMPLEMENTED\n",
            encoding="utf-8",
        )
        row = next(
            row
            for row in normalize_authority_inventory(build_authority_inventory(self.build_raw()))
            if row["key"].endswith("0x0201")
        )
        self.assertEqual(row["sections"]["response"]["status"], "SOURCE_CANDIDATE")
        self.assertEqual(row["sections"]["notify"]["status"], "MISSING_CURRENT_SOURCE")
        self.assertEqual(
            row["sections"]["notificationFanout"]["status"], "MISSING_CURRENT_SOURCE"
        )

    def test_explicit_not_applicable_requires_reason_and_evidence(self) -> None:
        self.server.joinpath("ReadOnly.cs").write_text(
            "// LOGH7_TRACE target=AUTHORITY:PROTOCOL:MESSAGE16:0x0200 "
            "role=MUTATION status=NOT_APPLICABLE reason=read_only_query\n",
            encoding="utf-8",
        )
        row = next(
            row
            for row in normalize_authority_inventory(build_authority_inventory(self.build_raw()))
            if row["key"].endswith("0x0200")
        )
        section = row["sections"]["mutation"]
        self.assertEqual(section["status"], "NOT_APPLICABLE_EXPLICIT")
        self.assertEqual(section["reason"], "read_only_query")
        self.assertEqual(len(section["sourceCandidateIds"]), 1)

    def test_entity_lifecycle_marker_can_close_static_slot(self) -> None:
        self.server.joinpath("Fleet.cs").write_text(
            "// LOGH7_TRACE target=AUTHORITY:ENTITY:TYPE:FLEET "
            "role=LIFECYCLE_CREATE status=IMPLEMENTED\n",
            encoding="utf-8",
        )
        row = next(
            row
            for row in normalize_authority_inventory(build_authority_inventory(self.build_raw()))
            if row["rowKind"] == "ENTITY_PATH"
        )
        self.assertEqual(row["entityLifecycle"]["create"]["status"], "SOURCE_CANDIDATE")
        self.assertEqual(row["entityLifecycle"]["destroy"]["status"], "MISSING_CURRENT_SOURCE")

    def test_event_emitter_marker_can_close_static_identity(self) -> None:
        self.server.joinpath("Emitter.cs").write_text(
            "// LOGH7_TRACE target=AUTHORITY:EVENT_CANDIDATE:ENTITY:RECORD:CommandAdmitted "
            "role=EMISSION_IDENTITY status=IMPLEMENTED\n",
            encoding="utf-8",
        )
        row = next(
            row
            for row in normalize_authority_inventory(build_authority_inventory(self.build_raw()))
            if row["rowKind"] == "EVENT_PATH"
        )
        self.assertEqual(row["emissionIdentity"]["status"], "SOURCE_CANDIDATE")

    def test_ui_counterpart_marker_is_typed_candidate_not_client_only_proof(self) -> None:
        target = "AUTHORITY:CLIENT_BEHAVIOR:UI:MODE:0x02:MANAGER:0x0B:CATEGORY:TYPE1:INDEX:0000"
        self.contracts.joinpath("UiAuthority.txt").write_text(
            f"// LOGH7_TRACE target={target} role=AUTHORITY_COUNTERPART "
            "status=IMPLEMENTED counterpart=AUTHORITY:PROTOCOL:MESSAGE16:0x0200\n",
            encoding="utf-8",
        )
        row = next(
            row
            for row in normalize_authority_inventory(build_authority_inventory(self.build_raw()))
            if row["rowKind"] == "CLIENT_BEHAVIOR_PATH"
        )
        self.assertEqual(row["authorityCounterpart"]["status"], "SOURCE_CANDIDATE")
        self.assertEqual(
            row["authorityCounterpart"]["counterpartKey"],
            "AUTHORITY:PROTOCOL:MESSAGE16:0x0200",
        )
        self.assertNotEqual(row["disposition"], "CLIENT_ONLY_PROVEN")

    def test_complete_static_markers_advance_first_missing_to_runtime_evidence(self) -> None:
        roles = (
            "COMMAND_HANDLER", "PARSING", "VALIDATION", "ACCEPT_DECISION",
            "REJECT_DECISION", "MUTATION", "EVENT", "RESPONSE", "NOTIFY",
            "FANOUT", "PERSISTENCE", "CHECKPOINT", "REPLAY_REDUCER",
            "RECONNECT_PROJECTION", "IDEMPOTENCY", "ADMIN_MUTATION",
        )
        self.server.joinpath("Complete.cs").write_text(
            "\n".join(
                f"// LOGH7_TRACE target=AUTHORITY:PROTOCOL:MESSAGE16:0x0200 "
                f"role={role} status=IMPLEMENTED"
                for role in roles
            ) + "\n",
            encoding="utf-8",
        )
        row = next(
            row
            for row in normalize_authority_inventory(build_authority_inventory(self.build_raw()))
            if row["key"].endswith("0x0200")
        )
        self.assertEqual(row["firstMissingBoundary"], "RUNTIME_AUTHORITY_EVIDENCE")

    def test_legacy_candidate_root_is_opt_in_and_never_current_authority(self) -> None:
        legacy = self.root / "legacy"
        legacy.mkdir()
        legacy.joinpath("Legacy.cs").write_text(
            "// LOGH7_TRACE target=AUTHORITY:PROTOCOL:MESSAGE16:0x0200 "
            "role=COMMAND_HANDLER status=IMPLEMENTED\n",
            encoding="utf-8",
        )
        without = self.build_raw()
        self.assertEqual(without["conservation"]["legacySourceFiles"], 0)
        with_legacy = self.build_raw(legacy_candidate_root=legacy)
        self.assertEqual(with_legacy["conservation"]["legacySourceFiles"], 1)
        marker = with_legacy["traceMarkerCandidates"][0]
        self.assertEqual(marker["provenance"], "LEGACY_CANDIDATE")
        rows = normalize_authority_inventory(build_authority_inventory(with_legacy))
        row = next(row for row in rows if row["key"].endswith("0x0200"))
        self.assertEqual(row["sections"]["commandHandler"]["status"], "MISSING_CURRENT_SOURCE")
        self.assertEqual(len(row["legacyCandidates"]), 1)

    def test_unknown_marker_target_and_role_are_conserved_unresolved(self) -> None:
        self.server.joinpath("Unknown.cs").write_text(
            "// LOGH7_TRACE target=AUTHORITY:PROTOCOL:MESSAGE16:0xDEAD "
            "role=UNKNOWN_ROLE status=IMPLEMENTED\n",
            encoding="utf-8",
        )
        raw = self.build_raw()
        rows = build_authority_inventory(raw)
        reconciliation = build_authority_reconciliation(raw, rows)
        markers = [
            record
            for record in reconciliation["records"]
            if record["collection"] == "traceMarkerCandidates"
        ]
        self.assertEqual(len(markers), 1)
        self.assertEqual(markers[0]["status"], "UNRESOLVED")
        self.assertEqual(markers[0]["firstMissingBoundary"], "TRACE_TARGET_OR_ROLE")

    def test_raw_surface_and_candidate_ids_are_fail_closed(self) -> None:
        raw = self.build_raw()
        raw["surfaceSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "surface"):
            build_authority_inventory(raw)

        raw = self.build_raw()
        duplicate = copy.deepcopy(raw["requirementCandidates"][0])
        raw["requirementCandidates"].append(duplicate)
        refresh_surface(raw)
        with self.assertRaisesRegex(ValueError, "candidateId"):
            build_authority_inventory(raw)

    def test_upstream_tampering_is_rejected_even_with_refreshed_surface(self) -> None:
        raw = self.build_raw()
        raw["upstream"]["protocol"]["sha256"] = "0" * 64
        raw["upstream"]["protocol"]["rowCount"] = 999999
        refresh_surface(raw)
        with self.assertRaisesRegex(ValueError, "upstream"):
            build_authority_inventory(raw)

    def test_root_symlink_is_rejected_before_resolution(self) -> None:
        linked = self.root / "linked-server"
        try:
            linked.symlink_to(self.server, target_is_directory=True)
        except OSError as error:
            self.skipTest(f"directory symlink unavailable: {error}")
        with self.assertRaisesRegex(ValueError, "link|reparse"):
            build_authority_source(
                server_root=linked,
                contracts_root=self.contracts,
                database_root=self.database,
                protocol_inventory=self.protocol,
                entity_inventory=self.entities,
                ui_inventory=self.ui,
            )

    def test_empty_directory_names_change_the_bound_tree_surface(self) -> None:
        raw_a = self.build_raw()
        (self.database / "migrations").rename(self.database / "alpha")
        (self.database / "seeds").rename(self.database / "beta")
        raw_b = self.build_raw()
        db_a = next(root for root in raw_a["sourceRoots"] if root["label"] == "DATABASE")
        db_b = next(root for root in raw_b["sourceRoots"] if root["label"] == "DATABASE")
        self.assertNotEqual(db_a["treeSha256"], db_b["treeSha256"])
        self.assertNotEqual(raw_a["surfaceSha256"], raw_b["surfaceSha256"])

    def test_normalizer_rejects_state_verdict_contradiction(self) -> None:
        row = build_authority_inventory(self.build_raw())[0]
        row["states"]["AUTHORITY_PROVEN"] = True
        with self.assertRaisesRegex(ValueError, "state|verdict"):
            normalize_authority_inventory([row])

    def test_normalizer_rejects_nested_schema_and_non_text_evidence_damage(self) -> None:
        rows = build_authority_inventory(self.build_raw())
        protocol = next(row for row in rows if row["rowKind"] == "PROTOCOL_PATH")
        damaged = copy.deepcopy(protocol)
        damaged["sections"]["decision"].pop("accepted")
        with self.assertRaisesRegex(ValueError, "decision|section|schema"):
            normalize_authority_inventory([damaged])

        entity = next(row for row in rows if row["rowKind"] == "ENTITY_PATH")
        damaged = copy.deepcopy(entity)
        damaged["entityLifecycle"] = {}
        with self.assertRaisesRegex(ValueError, "lifecycle|schema"):
            normalize_authority_inventory([damaged])

        ui = next(row for row in rows if row["rowKind"] == "CLIENT_BEHAVIOR_PATH")
        damaged = copy.deepcopy(ui)
        damaged["authorityCounterpart"] = {"status": "PROVEN_RUNTIME", "reason": "x"}
        with self.assertRaisesRegex(ValueError, "counterpart|schema|status"):
            normalize_authority_inventory([damaged])

        damaged = copy.deepcopy(protocol)
        damaged["evidence"] = [123]
        with self.assertRaisesRegex(ValueError, "evidence|text"):
            normalize_authority_inventory([damaged])

    def test_reconciliation_rejects_fabricated_and_multiply_assigned_candidates(self) -> None:
        raw = self.build_raw()
        rows = build_authority_inventory(raw)
        rows[0]["sourceCandidateIds"].append("FABRICATED:CANDIDATE")
        with self.assertRaisesRegex(ValueError, "candidate|assignment"):
            build_authority_reconciliation(raw, rows)
        rows = build_authority_inventory(raw)
        shared = rows[0]["sourceCandidateIds"][0]
        rows[1]["sourceCandidateIds"].append(shared)
        with self.assertRaisesRegex(ValueError, "candidate|assignment"):
            build_authority_reconciliation(raw, rows)

    def test_marker_source_file_reference_must_resolve(self) -> None:
        self.server.joinpath("Handler.cs").write_text(
            "// LOGH7_TRACE target=AUTHORITY:PROTOCOL:MESSAGE16:0x0200 "
            "role=COMMAND_HANDLER status=IMPLEMENTED\n",
            encoding="utf-8",
        )
        raw = self.build_raw()
        raw["traceMarkerCandidates"][0]["sourceFileCandidateId"] = "SOURCE_FILE:MISSING"
        refresh_surface(raw)
        with self.assertRaisesRegex(ValueError, "sourceFileCandidateId|source file"):
            build_authority_inventory(raw)

    def test_marker_cannot_be_reassigned_to_an_unrelated_existing_file(self) -> None:
        self.server.joinpath("A.cs").write_text(
            "// LOGH7_TRACE target=AUTHORITY:PROTOCOL:MESSAGE16:0x0200 "
            "role=COMMAND_HANDLER status=IMPLEMENTED\n",
            encoding="utf-8",
        )
        self.server.joinpath("B.cs").write_text("// unrelated\n", encoding="utf-8")
        raw = self.build_raw()
        other = next(
            item["candidateId"] for item in raw["sourceFileCandidates"] if item["path"] == "B.cs"
        )
        raw["traceMarkerCandidates"][0]["sourceFileCandidateId"] = other
        refresh_surface(raw)
        with self.assertRaisesRegex(ValueError, "marker|source file|candidateId"):
            build_authority_inventory(raw)

    def test_source_file_drift_after_scan_is_rejected(self) -> None:
        source = self.server / "Handler.cs"
        source.write_text(
            "// LOGH7_TRACE target=AUTHORITY:PROTOCOL:MESSAGE16:0x0200 "
            "role=COMMAND_HANDLER status=IMPLEMENTED\n",
            encoding="utf-8",
        )
        raw = self.build_raw()
        source.write_text("// changed after scan\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "source|tree|hash|drift"):
            build_authority_inventory(raw)

    def test_ancestor_symlink_component_is_rejected(self) -> None:
        real_parent = self.root / "real-parent"
        real_server = real_parent / "server"
        real_server.mkdir(parents=True)
        linked_parent = self.root / "linked-parent"
        try:
            linked_parent.symlink_to(real_parent, target_is_directory=True)
        except OSError as error:
            self.skipTest(f"directory symlink unavailable: {error}")
        with self.assertRaisesRegex(ValueError, "link|reparse"):
            build_authority_source(
                server_root=linked_parent / "server",
                contracts_root=self.contracts,
                database_root=self.database,
                protocol_inventory=self.protocol,
                entity_inventory=self.entities,
                ui_inventory=self.ui,
            )

    def test_normalization_and_reconciliation_are_deterministic(self) -> None:
        raw = self.build_raw()
        rows_a = normalize_authority_inventory(build_authority_inventory(raw))
        shuffled = copy.deepcopy(raw)
        shuffled["requirementCandidates"] = list(reversed(shuffled["requirementCandidates"]))
        refresh_surface(shuffled)
        rows_b = normalize_authority_inventory(build_authority_inventory(shuffled))
        self.assertEqual(rows_a, rows_b)
        reconciliation_a = build_authority_reconciliation(raw, build_authority_inventory(raw))
        reconciliation_b = build_authority_reconciliation(shuffled, build_authority_inventory(shuffled))
        self.assertNotEqual(
            reconciliation_a["sourceSurfaceSha256"], reconciliation_b["sourceSurfaceSha256"]
        )
        reconciliation_a.pop("sourceSurfaceSha256")
        reconciliation_b.pop("sourceSurfaceSha256")
        self.assertEqual(reconciliation_a, reconciliation_b)


if __name__ == "__main__":
    unittest.main()
