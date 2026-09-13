# 2026-09-04 UnitShip player-visible playable vertical

## Result

`PLAYER_VISIBLE_PASS / AUTHORITY_PROVEN (one WARP) / DB_WRITE_PROVEN / CLEAN_SHUTDOWN`

The original client now enters the strategy scene with a real ship model joined to authoritative unit 2 / character 2. In the same build, the player can open the authority card, start WARP, select grid 102, confirm, receive a successful authority response, and see the client report grid-selection completion. The run-local PostgreSQL copy records unit 2 moving from cell 101 to 102 with one move command and `OriginalGridUnitMoved` event 25.

This is a playable vertical, not full original-game parity. Direct ship selection and BASE-target commands remain open as described below.

## Implementation

- `0x030A -> 0x030B` now emits a compact static UnitShip template instead of the old oversized zero fill.
- Added exact `0x033A` request and `0x033B` response codecs. `0x033B` records are 47 bytes and bind unit id to character id.
- World entry proactively pushes `0x033B` after `0x0325`/`0x0323`, because a native `0x033A` request has not yet been observed.
- Added an explicit `0x033A` handler with requested-id projection.
- Promoted the observed `0x1200 selector 0x001E -> 0x1207 NotifySimpleInformationUnit` mapping from an environment probe to the default authority path.
- Added a PID/HWND-bound, one-shot Escape helper for the original client's normal exit path.
- Extended the owned-run cleanup scope to 2026-09-04 run ids.

## Automated verification

Fresh final command:

```powershell
$env:MSBuildSDKsPath=$null
$env:DOTNET_ROOT='C:\Program Files\dotnet'
$env:NUGET_PACKAGES='E:\logh7-build\nuget'
$env:TMP='E:\logh7-build\temp'
$env:TEMP='E:\logh7-build\temp'
dotnet test .\apps\server\Logh7.Server.ProtocolTests\Logh7.Server.ProtocolTests.csproj --nologo
```

Result: `21 passed, 0 failed` on .NET 10. The new tests cover compact 0x030B, exact 0x033A/0x033B shapes and caps, authoritative id projection, world-entry binding, selector 0x001E mapping, and the 0x1207 transaction.

## Published artifact

- ZIP: `work/20260902-notify-message-codec/server-scratch/logh7-server-unitship2-win-x64.zip`
- ZIP SHA-256: `0C28053732C483D4A36C7224BB41FE4870A6590177BB745AE7CCE081B9831073`
- EXE SHA-256: `C525060083F0F0CF7860D482B3C16E3E392B29BF9CFB5678DAB7551BFE3F99A4`
- DLL SHA-256: `0A54CAB54139164B877228828F568D177CFE0739CD81A8FA608D7FEBE72ABD3E`
- Expanded ZIP hashes matched the publish directory; 213 files and 15 migrations were present.

## Live run

Run: `20260904T113440Z-natural-l1-relogin-v1`

Evidence root:

`work/20260902-fresh-run-recovered-db/runs/20260904T113440Z-natural-l1-relogin-v1`

Observed path:

1. One credential submission; lobby showed server notice `UNITSHIP2`.
2. Game start -> character 2 -> strategy scene.
3. `unitship2-strategy.png`: ship model visible at the left grid, joined with the served character/HUD.
4. Card -> `部隊解散`: server received 0x1200 selector 0x001E and replied with BEGIN + exact 4,804-byte 0x1207 + END (`responsesBeforePrimaryPayloadLengths [48,4824]`, `unit=2`, one record).
5. The client still showed `実行不可 / 選択可能な項目が存在しません`; therefore the 0x1207 route alone does not establish BASE context.
6. Card -> `ワープ航行` -> visible grid 102 -> confirmation -> decision.
7. Authority wire: application type `0x0B01` (`2817`) succeeded with `move-grid-unit=2;source-cell=101;destination-cell=102;authority-version=25;design=new`.
8. Client message log showed `グリッド選択完了しました。`.
9. Read-only DB inspection: `current_cell_id=102`, unit authority version 25, move-command count 1, domain-event count 25, event type `OriginalGridUnitMoved`, account authority version 25. HBA was restored.
10. Client exited through `ESC -> ゲーム終了 -> 決定`; authority and PostgreSQL then stopped cleanly. Derived server/client/PostgreSQL/data copies were deleted and the final census reported zero leftover processes.

Key receipts:

| Evidence | SHA-256 |
| --- | --- |
| `unitship2-strategy.png` | `904A0D0681B2BCF87C166BDF82663CF45ACD2E913988039462BC7120D13C4633` |
| `unitship2-disband-result.png` | `001ED789C053B90910AF43DE264AA22ED8C260E9D3CDEFB21F2DDC9926646FAB` |
| `unitship2-warp-chooser.png` | `B09D3571FDEFFBB3D001B39410C94F6595A29C7DC0568EFD920BE7C4FDA640DE` |
| `unitship2-warp-confirm-visual.png` | `EF28A8334E712F98D557A134B5F3168DC6B82AA1A2ECB12E25F49284D6811519` |
| `unitship2-after-warp.png` | `F7B6970D264594D6E6BE3BC5145B619B1D836FA6E1BBAF138C2868A41951B341` |
| `server-wire.jsonl` | `1F5541FF2B5BE36F24BF6B9D93400FB6B0DF7FB8144281A2C0B2E2F99B15BF18` |
| `db-after-warp.json` | `543AE44C2E712E337B8C2B1662E5589D1FA88B939369CA5781C9413B30E9599C` |
| `clean-stop.json` | `F2F454D890D2F20D21AA33581D57C17C036E8FF64C8BB5B960F4DF400D2E8A13` |
| `cleanup.json` | `017B81372C569E6BD3D057BC0FC1F60D31D519125C1E752C209C10D8DC987E35` |
| `verify-cleanup.json` | `C728B1AC03C5C7083F3F9BC0D71CB40F90A98A03862566233235E06C0B43F816` |

## Exact open boundaries

- `0x033A` native request emission remains `UNSEEN`; the accepted player-visible result currently depends on the proactive 0x033B world-entry push.
- The static UnitShip template uses a parser-safe zero kind/model default (`NEW DESIGN`). The displayed ship proves loader/model fallback use, not recovered original subtype semantics.
- Clicking the rendered ship did not open a unit-selection panel in this run. Direct hit-test/selectability is `UNSEEN/NOT_WORKING`.
- `部隊解散` still fails locally after an accepted 0x1207 unit list. The remaining blocker is the command's BASE/current-context state, not the selector or list codec.
- DB write was proven in-run. This run did not restart the authority on the same DB, so restart persistence is not newly claimed here (it was proven by an earlier WARP vertical, but remains a separate claim).
- Full strategy commands, docking, combat, economy, multiplayer, and Gate-A/Gate-B parity are not claimed.

## Next start

1. Trace the rendered ship's hit-test/selectability gate from the scene object's copied InformationUnit fields and static UnitShip kind/model fields.
2. Reach the normal UNIT panel and recover/serve the `碇泊` command path; do not force BASE commands from the card panel.
3. Once docking sets the character's current-base context, repeat one BASE command and capture the first downstream request.
4. Only then add the corresponding authority command/persistence handler and a restart/relogin proof.

## Forbidden retries

- Do not repeat `部隊解散` with only the same 0x1207 response; that hypothesis is refuted.
- Do not infer original star/planet/ship subtype semantics from color, filenames, or the visible fallback model.
- Do not call the visible ship alone selectable, BASE-ready, multiplayer, parity, Gate-A, or Gate-B.
- Do not rerun clicks against stale PID/HWND or reuse these run receipts as a new launch permit.
# Status correction (2026-09-04)

The title of this handoff is narrower than it sounds. The unit-ship bootstrap and one strategic grid move were made visible, but tactical combat and the complete command surface were not playable. The controlling continuation is the final section of `docs/handoffs/2026-09-03-celestial-type-placeholder-finding.md` and `work/20260904-warp-state-reverse/evidence/E-002-warp-tactical-static.md`. Do not promote this document to a full-playability receipt.
