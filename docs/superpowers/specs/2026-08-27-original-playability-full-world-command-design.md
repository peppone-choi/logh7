# LOGH7 original-playability and complete-world design

Status: approved in-chat architecture, written design pending user review
Date: 2026-08-27
Repository: `E:\logh7-greenfield`

## 1. Outcome

Deliver the project in two explicit product milestones without confusing their evidence:

1. **Original compatibility milestone** — the unmodified shipped `G7MTClient.exe` can log in, enter the world, play both the Galactic Empire and Free Planets Alliance, use every command and content path actually exposed by that client, exchange proposals and orders, traverse the full strategic map, enter spots, and complete server-backed strategy and tactical actions.
2. **Complete reimplementation milestone** — the independent C++23 client and C# authoritative server reproduce the shipped surface and add the manual-documented but originally unfinished systems as clearly labeled `NEW DESIGN` gameplay.

The original client is an oracle and compatibility target, not a distributed dependency of the final product. Original CD assets remain external, hash-verified runtime data.

## 2. Evidence vocabulary

Every catalog field, rule, command, and screen carries one provenance value:

- `ORIGINAL_OBSERVED`: confirmed by original static or live evidence.
- `ORIGINAL_MANUAL`: stated by the official manual but not yet observed live.
- `INFERRED`: consistent with evidence but not confirmed.
- `NEW_DESIGN`: deliberate replacement behavior where the original service rule is unavailable or a dormant feature must be completed.
- `AUTHORED_PLACEHOLDER`: editable temporary data used to cross a known boundary; never parity evidence.
- `UNKNOWN`: insufficient evidence; cannot silently become a gameplay rule.

Fixture success, static decompilation, a rendered menu, or a server receipt alone never proves player-visible playability.

## 3. Known original constraints

- The current compatibility server sends many `Empty`, `Minimum`, or `Placeholder` payloads. This is the direct implementation gap behind missing world content and disabled commands.
- The recovered celestial source contains exactly 85 systems, 300 planets, and 6 fortresses. No system contains more than four planets.
- Original `0x031D` static Base data accepts at most 350 rows. Flattening all 391 systems, planets, and fortresses into that response is invalid.
- The original selected-system view creates eight ordinal planet nodes but enables a fixed maximum of four planet/orbit slots. This cardinality covers the recovered source because the per-system maximum is four.
- Original spot assets include 44 backgrounds: `bg001` through `bg043`, plus `bg050`. Asset count is not proof of semantic spot count.
- Original proposal/order families include `CommandSuggestion`, `ResponseSuggestion`, `CommandOrderSuggestMail`, `CommandReplyOrderSuggestMail`, and `NotifySimpleInformationOrderSuggestCharacter`.
- Faction-specific authority and order-card assets exist for Empire and Alliance.

Unknown wire layouts, field meanings, and state transitions remain reverse-engineering work; they are not filled with guessed constants.

## 4. System architecture

### 4.1 Authoritative world core

`WorldAuthority` owns the running session state. It exposes versioned command, event, and snapshot contracts and never trusts client-side state mutations. Its bounded modules are:

- `FactionDomain`
- `WorldCatalog`
- `LocationDomain`
- `AuthorityDomain`
- `ProposalDomain`
- `StrategyDomain`
- `TacticsDomain`
- `EconomyAndLogisticsDomain`
- `CommunicationDomain`
- `SessionLifecycleDomain`

All accepted mutations append ordered events and update one authority version and state hash. Rejected commands append no gameplay event. PostgreSQL stores event history, replay checkpoints, published content revisions, and player/session state.

### 4.2 Original-client compatibility edge

`LegacyGateway` owns cipher, framing, login/lobby/session sequencing, original message codecs, and compatibility projections. It translates original messages into the same domain commands used by the new client, then translates results and snapshots back to original responses and notifications.

The current monolithic `OriginalClientCompatibilitySession` is split into protocol state, codecs, domain-command translation, and response projection. `Empty`, `Minimum`, and `Placeholder` encoders remain only in explicit test fixtures; production world entry rejects startup if a required published catalog is absent.

### 4.3 Independent client edge

The new C++23 client consumes typed versioned snapshots and command results. It can display the complete catalog without original wire-size limitations. It uses `LegacyDataProvider` only for user-owned original resources and provenance-bound transformations.

## 5. Two playable factions

`Faction` is a first-class aggregate, not a cosmetic character flag. Initial published content contains exactly two playable major factions:

- Galactic Empire
- Free Planets Alliance

Each has a capital, calendar presentation, authority-card catalog, ranks and offices, institutions, characters, fleets, owned cells, bases, spots, resources, strategic plans, proposal queues, and victory state. Character creation or assignment binds one faction. Cross-faction visibility is filtered by server knowledge and mode; ownership and command checks always use authoritative faction membership.

Acceptance requires two different user accounts in the same session, one per faction. Both must enter the same world snapshot, issue valid commands, receive enemy/friendly visibility appropriate to the rules, and converge on the same public authority state while retaining distinct actor-specific views.

## 6. Authority cards, commands, and proposals

### 6.1 Unified command registry

Every original menu command and every authored command receives one registry row with:

- stable command key and original message/opcode references;
- provenance and evidence links;
- eligible modes and factions;
- required card, office, rank, ownership, location, resources, cooldown, and target type;
- execution handler, events, notifications, persistence effects, and UI proof scenarios;
- status: `UNKNOWN`, `DISCOVERED`, `CODEC_PASS`, `DOMAIN_PASS`, `ORIGINAL_VISIBLE_PASS`, `NEW_CLIENT_VISIBLE_PASS`, or `COMPLETE`.

No command is called playable until an actual client invokes it, the authoritative server accepts or rejects it for the correct reason, resulting state persists, and the result is visible in the same run.

### 6.2 Permission decision

For a given actor and command, `AuthorityPolicy` returns exactly one decision:

- `DIRECT`: actor may execute now.
- `PROPOSAL_REQUIRED`: actor may request an authorized office holder to execute.
- `DENIED`: actor may neither execute nor propose, with a stable reason code.

The result is derived from published authority-card rules and current state. The client never chooses the permission class.

### 6.3 Proposal state machine

A proposal has immutable intent plus mutable workflow state:

`Draft -> Submitted -> Pending -> Accepted | Rejected | Withdrawn | Expired -> Executed | ExecutionFailed`

The server selects eligible recipients from faction offices and authority cards. Acceptance revalidates the underlying command against current state and executes the same handler used by a direct command. It does not apply a parallel shortcut effect. Rejection, withdrawal, expiry, and execution failure retain an auditable reason. Original suggestion/order-mail messages are compatibility projections of this state machine.

Tests cover self-approval denial where prohibited, stale authority, office vacancy, recipient replacement, duplicate replies, expiration, accept-after-state-change, and reconnect visibility.

## 7. Complete world catalog

### 7.1 Canonical model

`WorldCatalog` publishes immutable revisioned definitions for:

- `StarSystem`: stable ID, grid/cell, name, faction/occupation defaults, visual class, adjacency and strategic metadata.
- `Planet`: stable ID, parent system ID, ordinal, name, orbit radius/cycle/direction/initial angle, diameter, model class, economy and facility eligibility.
- `Fortress`: stable ID, parent system ID, name, orbit/position data, ownership and combat role.
- `Institution`: stable ID, parent celestial/location ID, type, owning faction, condition, construction and operating state.
- `Spot`: stable ID, institution or celestial parent, original background resource key, category, access rules, movement edges, communication scope and spawn positions.

IDs are nonzero, stable, unique, and deterministic across every joining message. Project-authored IDs are labeled `NEW_DESIGN`; they are never described as recovered historical IDs.

### 7.2 Original compatibility projection

The original client receives data through proven bounded projections rather than a blind 391-row flatten:

- `0x031D` remains within its 350-row limit and carries the proven static Base projection.
- Selected-system content maps the chosen system's zero-to-four planets into the native ordinal slots.
- Planet orbit values use recovered fields only where a client consumer is proven.
- Fortress, institution, dynamic ownership, and spot data use their own proven request/response or notification families.
- If no native delivery path exists after exhaustive static and bounded live analysis, the fact remains `UNKNOWN`; the full new client consumes the canonical catalog directly.

A compatibility patch is a last resort, built only from a copied original executable after the native route is disproven, with source/target hashes, reversible patch manifest, isolated VM execution, and separate user authorization. It is not part of the distributed reimplementation.

### 7.3 Spot completeness

The 44 background files form an asset inventory, not the spot catalog. Reverse engineering must recover or adjudicate:

- spot ID and parent institution/celestial object;
- background selection and any faction variants;
- entry, exit, local movement, forced movement, and card-loss movement;
- same-spot occupant list and portrait placement;
- spot chat and unicast chat scope;
- access restrictions, capacity, spawn positions, and unavailable states.

Every published spot must be reachable by at least one valid scenario and must render the provenance-bound background through the real loader.

## 8. Reverse-engineering program

Discovery is organized by user-visible boundary, not by arbitrary function count:

1. **World catalog lane** — reproduce 85/300/6 source inventory; recover system, planet, fortress and institution joins; map all client consumers and wire caps.
2. **Spot lane** — inventory 44 assets; recover background loader, semantic spot table, movement commands, occupant list, and chat codecs.
3. **Faction lane** — recover faction IDs, calendars, initial selection/assignment, capitals, card catalogs, visibility and victory messages.
4. **Proposal lane** — recover exact message IDs, payload fields, list capacities, recipient rules, reply lifecycle and UI owners.
5. **Command lane** — enumerate every visible authority-card action and message family, then bind UI entry -> serializer -> server handler -> response/notification -> pixels.

Each lane produces hash-bound evidence, an Unknown list, a command/content matrix delta, and a handoff. Static evidence may advance `DISCOVERED` or `CODEC_PASS`; only same-run original-client evidence advances `ORIGINAL_VISIBLE_PASS`.

## 9. Original-playability milestone

Original compatibility is complete only when the unmodified client demonstrates all of the following against the authoritative server:

- account, lobby, character and both-faction world entry;
- full strategic grid navigation across all 85 systems;
- selected-system presentation for every system, covering all 300 planets and 6 fortresses through their proven views;
- institution and spot entry, exit, movement, same-spot members, chat and reconnect location;
- direct and proposal-required commands, including accept and reject paths;
- movement, logistics, personnel, political, communication and session commands exposed by the shipped client;
- opposing-faction encounter, tactical entry, basic battle, retreat or resolution, and resulting strategic state;
- persistence across reconnect and server restart.

Every scenario binds original-client PID/start/SHA/HWND, server run ID, command IDs, before/after authority hashes, PostgreSQL effects, and owned-HWND captures. Automatic clicking and retrospective evidence editing remain prohibited for sealed oracle runs.

## 10. Complete-reimplementation milestone

After original compatibility establishes the shipped surface, the new client adds manual-documented unfinished areas such as economy, fighters and offline tactical AI as `NEW_DESIGN`. These features use the same authoritative command, proposal, event and catalog systems. They do not need to be forced through an original UI that never exposed a usable path.

The full product is complete only when the feature matrix has no `UNKNOWN`, stub, placeholder production encoder, skipped acceptance test, or unowned command/content row, and two clean client VMs complete the end-to-end campaign loop.

## 11. Failure handling

- Missing required published content prevents session start with a catalog-specific error.
- Unknown original fields are preserved as opaque bytes or omitted; guessed semantic conversions are forbidden.
- Invalid client commands return stable rejection codes and no gameplay events.
- A proposal accepted against changed state becomes `ExecutionFailed` with the underlying rejection reason.
- Reconnect restores actor view from an authority snapshot plus ordered events.
- Projection overflow is detected before encoding; the server never truncates silently.
- A client that cannot consume a required original projection fails that compatibility scenario rather than being declared playable from server-side success.

## 12. Verification strategy

Automated verification is necessary but not sufficient:

- codec round trips and malformed-payload rejection;
- catalog cardinality, referential integrity, ID stability and projection-cap tests;
- property tests for command authorization, idempotency and proposal transitions;
- event replay and PostgreSQL restart/reconnect convergence;
- two-faction integration tests with distinct actor views;
- asset hash, loader and draw-receipt tests for every spot background and celestial resource;
- original-client same-run protocol and pixel receipts;
- new-client two-VM gameplay captures;
- independent evidence review before promoting each visible gate.

The controlling matrix records evidence separately for server behavior, persistence, original-client visibility, new-client visibility and multiplayer convergence.

## 13. Implementation order

Work proceeds as vertical slices that remain part of one architecture:

1. command/content evidence inventory and production-placeholder audit;
2. canonical two-faction `WorldCatalog` and referential-integrity tooling;
3. original-compatible world projection sufficient for complete strategic browsing;
4. spot catalog and one real movement/chat slice;
5. authority cards plus proposal accept/reject slice;
6. warp movement with persistence and two-faction visibility;
7. remaining shipped strategy commands by family;
8. tactical encounter and battle loop;
9. communication, session lifecycle and victory;
10. `NEW_DESIGN` economy, fighters, offline AI and other unfinished content;
11. full matrix closure, packaging, administration and multilingual QA.

Each unit ends with a report and handoff. A family is not complete while any member lacks its required server, persistence, original-client/new-client and multiplayer evidence columns.

This document is the umbrella architecture. After user review, the first implementation plan covers item 1 only. Each later vertical slice receives its own bounded spec or plan once the preceding handoff fixes its inputs; no single plan attempts the entire program at once.

## 14. Controlling evidence baseline

- `docs/handoffs/2026-08-27-original-client-first-play-stage-gate.md`
- `docs/handoffs/2026-08-27-original-client-strategy-celestial-data-delivery-diagnosis.md`
- `docs/handoffs/2026-08-27-original-client-static-base-id-join-contract.md`
- `docs/handoffs/2026-08-27-original-client-selected-system-planet-data-owner.md`
- `docs/handoffs/2026-08-27-original-client-planet-scene-mode-coordinate-writers.md`
- `.worktrees/executable-bootstrap/docs/reverse-engineering/strategy-authority-member-exact-mapping.md`
- `.worktrees/executable-bootstrap/docs/reverse-engineering/client-feature-matrix.csv`

These sources establish the current facts and Unknowns; they do not themselves prove the target milestones.
