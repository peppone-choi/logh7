from __future__ import annotations

import json
import hashlib
import tempfile
import unittest
from pathlib import Path

from tools.exhaustive_trace.import_ui import (
    build_ui_inventory,
    build_ui_reconciliation,
    load_ui_evidence_manifest,
    normalize_ui_inventory,
)
from tools.exhaustive_trace.model import EvidenceState, Reachability


def complete_ui_export(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "schemaVersion": 1,
        "source": {
            "program": "g7mtclient.exe",
            "executableSha256": "BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16",
            "language": "x86:LE:32:default",
            "compiler": "windows",
            "imageBase": "00400000",
            "messageDataSha256": "5B3FAFBA7DD7230CDEB5F2FF9ACF9BBBE20FD95ADE25C425BC0D11AE645C383C",
        },
        "exporter": {
            "class": "ExportExhaustiveUi",
            "sha256": "A" * 64,
            "ghidraRepositorySha256": "B" * 64,
        },
        "surfaceSha256": "C" * 64,
        "successMarker": "EXPORT_EXHAUSTIVE_UI_OK",
        "rootModes": [],
        "managerConstructions": [],
        "managerLookupCandidates": [],
        "widgetConstructions": [],
        "menuRows": [],
        "descriptorLoaderCandidates": [],
        "labelCandidates": [],
        "eventCandidates": [],
        "handlerCandidates": [],
        "enablementCandidates": [],
        "visibilityCandidates": [],
        "childManagerCandidates": [],
        "inputSourceCandidates": [],
        "renderCandidates": [],
    }
    payload.update(overrides)
    return payload


def widget_candidate(**overrides: object) -> dict[str, object]:
    candidate: dict[str, object] = {
        "candidateId": "WIDGET:005180A0",
        "constructionSite": "0x005180A0",
        "builderFunction": "FUN_00518060",
        "modes": ["0x02"],
        "managerIds": ["0x16"],
        "category": "0x04",
        "index": 0,
        "constructor": "FUN_00503A10",
        "constructorDefaultHitTest": 0,
        "label": {
            "status": "UNKNOWN",
            "text": None,
            "source": None,
            "consumerFunctions": [],
            "evidence": ["raw:surface:C"],
        },
        "event": {
            "status": "UNKNOWN",
            "namespace": "UNKNOWN",
            "types": [],
            "predicates": [],
            "evidence": ["raw:surface:C"],
        },
        "handler": {
            "status": "UNKNOWN",
            "functions": [],
            "reason": "not yet joined",
            "evidence": ["raw:surface:C"],
        },
        "enablement": {
            "status": "CANDIDATE",
            "stateFields": ["widget+0x15"],
            "writers": [],
            "predicates": [],
            "evidence": ["raw:surface:C"],
        },
        "visibility": {
            "status": "UNKNOWN",
            "stateFields": [],
            "writers": [],
            "predicates": [],
            "evidence": ["raw:surface:C"],
        },
        "childManagers": {
            "status": "UNKNOWN",
            "targetKeys": [],
            "reason": "not yet joined",
            "evidence": ["raw:surface:C"],
        },
        "reachability": "UNKNOWN",
        "reachabilityEvidence": ["raw:surface:C"],
        "evidence": ["raw:widgetConstructions:0"],
    }
    candidate.update(overrides)
    return candidate


class UiInventoryTests(unittest.TestCase):
    def test_root_mode_and_manager_construction_become_rows(self) -> None:
        raw = complete_ui_export(
            rootModes=[
                {
                    "candidateId": "MODE:02",
                    "mode": "0x02",
                    "dispatchFunction": "FUN_0054E570",
                    "builderFunction": "FUN_004FF3C0",
                    "branchCallsite": "0x0054E642",
                    "evidence": ["raw:rootModes:0"],
                }
            ],
            managerConstructions=[
                {
                    "candidateId": "MANAGER:004FF412",
                    "constructionSite": "0x004FF412",
                    "builderFunction": "FUN_004FF3C0",
                    "modes": ["0x02"],
                    "managerId": "0x6E",
                    "constructor": "FUN_0050BB40",
                    "evidence": ["raw:managerConstructions:0"],
                }
            ],
        )
        keys = {row.row.key for row in build_ui_inventory(raw)}
        self.assertEqual(
            keys,
            {
                "UI:MODE:0x02:MANAGER:ROOT:CATEGORY:MODE_ROOT:INDEX:0000",
                "UI:MODE:0x02:MANAGER:0x6E:CATEGORY:MANAGER_ROOT:INDEX:0000",
            },
        )

    def test_widget_construction_becomes_canonical_ui_row(self) -> None:
        rows = build_ui_inventory(
            complete_ui_export(widgetConstructions=[widget_candidate()])
        )
        self.assertEqual(len(rows), 1)
        row = rows[0]
        self.assertEqual(
            row.row.key,
            "UI:MODE:0x02:MANAGER:0x16:CATEGORY:0x04:INDEX:0000",
        )
        self.assertTrue(row.row.states[EvidenceState.ENUMERATED])
        self.assertFalse(row.row.states[EvidenceState.PLAYER_VISIBLE])
        self.assertIs(row.row.reachability, Reachability.UNKNOWN)

    def test_menu_row_keeps_parent_widget_identity_and_row_index(self) -> None:
        candidate = widget_candidate(
            candidateId="MENU_ROW:0054B6B3:01",
            constructionSite="0x0054B6B3",
            builderFunction="FUN_0054B420",
            managerIds=["0x16"],
            category="0x04",
            index=0,
            row=1,
            label={
                "status": "BOUND_CONSUMER",
                "text": "艦船情報",
                "source": "constmsg:0x25:1",
                "consumerFunctions": ["FUN_00505B50"],
                "evidence": ["raw:menuRows:1"],
            },
        )
        row = build_ui_inventory(complete_ui_export(menuRows=[candidate]))[0]
        self.assertEqual(
            row.row.key,
            "UI:MODE:0x02:MANAGER:0x16:CATEGORY:0x04:INDEX:0000:ROW:0001",
        )
        self.assertEqual(row.row_kind.value, "MENU_ROW")
        self.assertEqual(row.identity["row"], "0001")

    def test_raw_source_metadata_must_match_frozen_client(self) -> None:
        cases = {
            "executableSha256": "D" * 64,
            "language": "x86:BE:32:default",
            "compiler": "gcc",
            "imageBase": "00500000",
        }
        for field, value in cases.items():
            with self.subTest(field=field):
                source = dict(complete_ui_export()["source"])
                source[field] = value
                with self.assertRaisesRegex(ValueError, "source"):
                    build_ui_inventory(complete_ui_export(source=source))

    def test_runtime_pointer_cannot_be_stable_ui_identity(self) -> None:
        raw = complete_ui_export(
            widgetConstructions=[widget_candidate(managerIds=["0x095D6EAC"])]
        )
        with self.assertRaisesRegex(ValueError, "manager ID"):
            build_ui_inventory(raw)

    def test_bound_label_requires_consuming_function(self) -> None:
        label = {
            "status": "BOUND_CONSUMER",
            "text": "情報",
            "source": "constmsg:0x25:0",
            "consumerFunctions": [],
            "evidence": ["raw:labelCandidates:0"],
        }
        with self.assertRaisesRegex(ValueError, "consumer"):
            build_ui_inventory(
                complete_ui_export(widgetConstructions=[widget_candidate(label=label)])
            )

    def test_interactive_reachable_row_requires_handler_proof(self) -> None:
        with self.assertRaisesRegex(ValueError, "reachable.*handler"):
            build_ui_inventory(
                complete_ui_export(
                    widgetConstructions=[
                        widget_candidate(
                            interactionKind="INTERACTIVE",
                            reachability="SHIPPED_REACHABLE",
                            reachabilityEvidence=["callpath:MODE2->UNKNOWN"],
                        )
                    ]
                )
            )

    def test_display_only_row_may_use_explicit_not_applicable_handler(self) -> None:
        candidate = widget_candidate(
            interactionKind="DISPLAY_ONLY",
            handler={
                "status": "NOT_APPLICABLE",
                "functions": [],
                "reason": "render-only descriptor",
                "evidence": ["raw:widgetConstructions:0"],
            },
        )
        self.assertEqual(
            build_ui_inventory(
                complete_ui_export(widgetConstructions=[candidate])
            )[0].interaction_kind.value,
            "DISPLAY_ONLY",
        )

    def test_constructor_default_disabled_is_not_promoted_to_dormant(self) -> None:
        row = build_ui_inventory(
            complete_ui_export(widgetConstructions=[widget_candidate()])
        )[0]
        self.assertEqual(row.enablement.status.value, "CANDIDATE")
        self.assertIs(row.row.reachability, Reachability.UNKNOWN)

    def test_unknown_state_cannot_claim_fields(self) -> None:
        candidate = widget_candidate()
        candidate["enablement"] = {
            "status": "UNKNOWN",
            "stateFields": ["widget+0x15"],
            "writers": [],
            "predicates": [],
            "evidence": ["raw:surface:C"],
        }
        with self.assertRaisesRegex(ValueError, "unknown.*claim"):
            build_ui_inventory(
                complete_ui_export(widgetConstructions=[candidate])
            )

    def test_reachable_interactive_row_needs_full_binding_and_draw_proof(self) -> None:
        candidate = widget_candidate(
            interactionKind="INTERACTIVE",
            reachability="SHIPPED_REACHABLE",
            reachabilityEvidence=["callpath:MODE2->MANAGER16->MENU0"],
            handler={
                "status": "PROVEN",
                "functions": ["FUN_0054BB50"],
                "reason": "downstream branch",
                "evidence": ["raw:handler"],
            },
        )
        with self.assertRaisesRegex(ValueError, "event.*enablement.*visibility"):
            build_ui_inventory(
                complete_ui_export(widgetConstructions=[candidate])
            )

    def test_unknown_event_handler_and_child_cannot_claim_bindings(self) -> None:
        candidate = widget_candidate()
        candidate["event"] = {
            "status": "UNKNOWN",
            "namespace": "INTERNAL_WIDGET",
            "types": ["0x0E"],
            "predicates": [],
            "evidence": ["raw:event"],
        }
        with self.assertRaisesRegex(ValueError, "unknown event"):
            build_ui_inventory(complete_ui_export(widgetConstructions=[candidate]))

        candidate = widget_candidate()
        candidate["handler"] = {
            "status": "UNKNOWN",
            "functions": ["FUN_0054BB50"],
            "reason": "not joined",
            "evidence": ["raw:handler"],
        }
        with self.assertRaisesRegex(ValueError, "unknown handler"):
            build_ui_inventory(complete_ui_export(widgetConstructions=[candidate]))

        candidate = widget_candidate()
        candidate["childManagers"] = {
            "status": "UNKNOWN",
            "targetKeys": ["UI:MODE:0x02:MANAGER:0x16:CATEGORY:MANAGER_ROOT:INDEX:0000"],
            "reason": "not joined",
            "evidence": ["raw:child"],
        }
        with self.assertRaisesRegex(ValueError, "unknown child"):
            build_ui_inventory(complete_ui_export(widgetConstructions=[candidate]))

    def test_unknown_top_level_collection_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "top-level fields"):
            build_ui_inventory(complete_ui_export(ghostWidgets=[]))

    def test_reconciliation_conserves_unjoined_candidates(self) -> None:
        raw = complete_ui_export(
            widgetConstructions=[widget_candidate()],
            labelCandidates=[
                {
                    "candidateId": "LABEL:0058D3C0",
                    "lookupSite": "0x0058D3C0",
                    "function": "FUN_0058D140",
                    "status": "UNJOINED",
                }
            ],
        )
        rows = build_ui_inventory(raw)
        reconciliation = build_ui_reconciliation(raw, rows)
        self.assertEqual(reconciliation["candidateCount"], 2)
        self.assertEqual(reconciliation["normalizedCount"], 1)
        self.assertEqual(reconciliation["unresolvedCount"], 1)
        self.assertEqual(reconciliation["unaccountedCount"], 0)

    def test_normalized_jsonl_is_deterministic(self) -> None:
        first = widget_candidate()
        second = widget_candidate(
            candidateId="WIDGET:00518100",
            constructionSite="0x00518100",
            index=1,
        )
        a = normalize_ui_inventory(
            build_ui_inventory(
                complete_ui_export(widgetConstructions=[first, second])
            )
        )
        b = normalize_ui_inventory(
            build_ui_inventory(
                complete_ui_export(widgetConstructions=[second, first])
            )
        )
        self.assertEqual(
            [json.dumps(item, sort_keys=True) for item in a],
            [json.dumps(item, sort_keys=True) for item in b],
        )

    def test_ui_evidence_manifest_rejects_raw_tampering(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            raw_path = root / "ui.json"
            exporter_path = root / "ExportExhaustiveUi.java"
            raw_path.write_bytes(b"{}\n")
            exporter_path.write_text("class ExportExhaustiveUi {}\n", encoding="utf-8")
            digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest().upper()
            manifest_path = root / "manifest.json"
            manifest_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "clientSha256": "BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16",
                        "ghidraRepositorySha256": "B" * 64,
                        "raw": {"path": str(raw_path), "sha256": digest(raw_path)},
                        "exporter": {"path": str(exporter_path), "sha256": digest(exporter_path)},
                    }
                ),
                encoding="utf-8",
            )
            self.assertEqual(load_ui_evidence_manifest(manifest_path).raw_path, raw_path.resolve())
            raw_path.write_bytes(b'{"tampered":true}\n')
            with self.assertRaisesRegex(ValueError, "raw hash mismatch"):
                load_ui_evidence_manifest(manifest_path)

    def test_ui_raw_rejects_different_message_data_hash(self) -> None:
        with self.assertRaisesRegex(ValueError, "message data"):
            build_ui_inventory(
                complete_ui_export(),
                expected_message_data_sha256="D" * 64,
            )


if __name__ == "__main__":
    unittest.main()
