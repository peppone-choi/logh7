# LOGH7 original-game exhaustive trace design

Status: approved in chat
Date: 2026-08-27
Repository: `E:\logh7-greenfield`

## 1. Goal

Before claiming complete reimplementation, enumerate and trace every gameplay function, entity, record, command, notification, UI action, resource, state transition, authority decision, and persistence path exposed or evidenced by the original game.

“Every” includes three separately labeled populations:

1. `SHIPPED_REACHABLE` — reachable in the shipped client under a valid role, faction, mode, or state.
2. `SHIPPED_DORMANT` — code, data, strings, protocol types, or UI descriptors exist but no shipped reachability path is proven.
3. `MANUAL_ONLY` — documented by the official manual but absent or unproven in the shipped executable.

Dormant and manual-only items remain part of the replacement backlog, but their completed behavior is `NEW_DESIGN` unless original runtime rules are recovered.

## 2. Non-goals and evidence boundary

- Do not trace every CPU instruction. Trace every semantic entry point and every state-bearing path.
- Do not treat a string, asset, opcode name, fixture, server stub, or menu row as a working feature.
- Do not promote legacy revival-server behavior into original truth without independent original evidence.
- Do not modify the original executable or debuggee data to manufacture reachability.
- Do not confuse a client-side apply path with server authority, or a server response with player-visible behavior.
- Do not collapse original observations, manual facts, inference, authored decisions, and placeholders.

## 3. Closed-world inventories

Completeness is defined against six mechanically generated inventories. Manual lists may annotate them but cannot replace enumeration.

### 3.1 Protocol inventory

Enumerate every message16/message32 code, request, response, command, notification, parser, serializer, dispatcher branch, body size, array cap, and direction. Each entry records request-response/notify siblings and whether the current server emits, parses, validates, mutates, and persists it.

### 3.2 UI and input inventory

Enumerate every root mode, manager, widget category/index, menu row, event type, enable/visibility writer, modal transition, input predicate, and attached child manager. Bind visible labels only after tracing their consuming branch.

### 3.3 Entity and record inventory

Enumerate every account, session, faction, character, role, rank, office, authority card, order, proposal, mail, chat context, fleet, unit, ship, troop, fighter, weapon, base, system, planet, fortress, grid cell, special celestial body, institution, spot, warehouse item, package, resource, tactical field, battle, objective, ranking, victory state, and administrative record.

Each entity records:

- ID namespace and uniqueness rule;
- parent, ownership, faction, location, and visibility edges;
- creation, selection, update, transfer, destruction, and terminal states;
- static and dynamic wire projections;
- client cache and renderer consumers;
- server authority and persistence owner;
- reconnect/replay behavior.

### 3.4 Resource inventory

Enumerate original models, textures, portraits, backgrounds, fonts, messages, sounds, maps, cursors, and configuration data. A resource becomes integrated only when source hash, loader, runtime key, owning feature/entity, draw/play submission, and player-visible or audible receipt are connected.

### 3.5 Client function inventory

Enumerate functions from imports, exports, RTTI, vtables, strings, protocol class names, dispatch tables, UI descriptor tables, asset path formatters, and state registries. Library and plumbing functions may be grouped, but every gameplay/state-bearing function receives address, proposed name, inputs, outputs, callers, callees, global/structure fields, side effects, evidence, and confidence.

### 3.6 Authority and persistence inventory

Enumerate every server command handler, validation predicate, accepted/rejected result, state mutation, emitted event, notification fanout, database write, checkpoint, replay reducer, reconnect projection, and administrator mutation. Client-only behavior is recorded explicitly when no authority counterpart exists.

## 4. Unified trace graph

All inventories join into one evidence graph. Nodes are functions, messages, fields, entities, UI controls, assets, authority handlers, events, tables, and visible surfaces. Edges are typed rather than inferred from proximity:

- `SERIALIZES`, `PARSES`, `DISPATCHES`, `COPIES_TO`, `READS`, `WRITES`;
- `IDENTIFIES`, `PARENT_OF`, `OWNED_BY`, `LOCATED_IN`, `VISIBLE_TO`;
- `ENABLES`, `TRIGGERS`, `VALIDATES`, `ACCEPTS`, `REJECTS`, `MUTATES`;
- `EMITS`, `APPLIES`, `LOADS`, `SUBMITS`, `PRESENTS`;
- `PERSISTS`, `REPLAYS`, `RESTORES`.

Name equality, array adjacency, source-file proximity, and project-generated IDs are not identity edges without a proven consumer.

## 5. Required vertical traces

### 5.1 Feature trace

Every feature attempts to close this path:

```text
player-visible control
-> enable and authority preconditions
-> input event
-> command construction
-> serialization and send
-> server parse and validation
-> accepted or rejected authority result
-> state mutation and event
-> response or notification
-> client parser and cache/state writer
-> HUD/scene/render or audio consumer
-> persistence
-> reconnect/replay restoration
```

Both a valid path and representative invalid paths are required where the command has authority checks.

### 5.2 Entity trace

Every state-bearing entity attempts to close this path:

```text
definition and provenance
-> stable identity
-> parent/owner/location
-> creation or spawn
-> query and visibility
-> all mutation commands
-> transfer/destruction/terminal state
-> wire snapshots and notifications
-> client representation
-> persistence and restoration
```

### 5.3 Content trace

Every content row connects source name/value, provenance, loader/parser, stable ID, referencing entity/feature, runtime use, localization, and player-visible receipt. Empty original data is preserved as empty; replacement data is separately labeled.

## 6. Domain decomposition

The master graph is executed as bounded domain tracks:

1. launcher, patcher, configuration, data-root validation;
2. account, authentication, lobby, session, character creation/selection;
3. faction, calendar, rank, office, role, authority cards;
4. strategic world topology: systems, planets, fortresses, grids, special bodies;
5. fleets, units, ships, troops, fighters, arms, commanders, flagship selection;
6. strategic navigation, warp, search, encounters, fog and visibility;
7. bases, institutions, spots, rooms, facilities, occupants and location movement;
8. command, order, suggestion/proposal, reply, mail and messenger workflows;
9. grid, spot, unicast and tactical communication;
10. economy, production, construction, repair, supply, cargo and warehouse;
11. tactical entry, field creation, deployment, movement, combat and retreat;
12. politics, personnel, diplomacy, arrest, coup, occupation and governance;
13. growth, rewards, ranking, victory, defeat and session termination;
14. offline state, AI, timeout, disconnect, reconnect and replay;
15. sound, cursor, localization, HUD, information panels and accessibility;
16. administration, moderation, content publication, backup and operations.

The existing world-topology plan is Track 4, not the master plan.

## 7. Coverage states

Every inventory row carries independent evidence states:

- `ENUMERATED`
- `STATIC_MAPPED`
- `CODEC_PROVEN`
- `RUNTIME_OBSERVED`
- `PLAYER_VISIBLE`
- `AUTHORITY_PROVEN`
- `PERSISTENCE_PROVEN`
- `BOTH_FACTIONS`
- `INDEPENDENTLY_REVIEWED`

Reachability is separately `SHIPPED_REACHABLE`, `SHIPPED_DORMANT`, `MANUAL_ONLY`, or `UNKNOWN`. Execution verdicts remain `PASS`, `PARTIAL`, `UNSEEN`, `BLOCKED`, and `UNKNOWN`.

A later state never implies an earlier one. For example, player-visible pixels do not prove authority or persistence.

## 8. Enumeration and orphan gates

Automated checks fail when any of the following occurs:

- an opcode lacks direction, parser/serializer ownership, or a disposition;
- a UI control lacks a handler, explicit dormant classification, or a falsifiable unknown;
- an entity ID has no namespace or an entity parent is unresolved without status;
- a state writer has no known input source;
- a server mutation has no event/persistence disposition;
- an asset has no loader/runtime owner or is incorrectly called integrated;
- a manual feature is absent from the reachability ledger;
- a shipped function/message/resource disappears between inventories without adjudication;
- a compatibility implementation has no original evidence or `NEW_DESIGN` label.

Orphans are work items, not silently discarded noise.

## 9. Static, replay, and live strategy

### 9.1 Static closure

Use the hash-bound Ghidra project to export dispatchers, protocol class references, UI descriptors, state registries, loaders, render consumers, serializers, and structure access patterns. Ghidra is the primary tool; Frida/x32dbg provide dynamic corroboration. Optional tools may be installed when they add an independent result, but absence of radare2 is not a blocker.

### 9.2 Offline byte replay

Build independent decoders around retained bodies and read-only compatibility builders. Compare byte sizes, caps, field consumption, identity joins, and missing fields without launching or changing the server.

### 9.3 Live oracle slices

Do not attempt a monolithic trace or reuse unrelated breakpoint suites. Each semantic action receives a frozen breakpoint/capture manifest spanning the smallest complete vertical path. Each live run uses fresh identity, read-only captures, one explicitly bounded physical action at most, owned-HWND evidence, no automatic click/retry, and independent review where promotion matters.

Multiple runs are necessary because bootstrap, strategic selection, spot transitions, tactical entry, authority commands, and persistence occur in different lifecycles. A consumed permit is never reused.

## 10. Faction, role, and outcome matrix

Every reachable feature is evaluated across the minimum state combinations that can change behavior:

- Galactic Empire and Free Planets Alliance;
- personal, ship, fleet, institutional and national authority contexts;
- owner, ally, enemy and hidden/unknown target;
- sufficient and insufficient permission/resource/location/cooldown;
- accepted, rejected, cancelled, interrupted and duplicate command;
- connected, disconnected and reconnected state.

Equivalent branches may share evidence only when static comparisons and runtime state prove equivalence.

## 11. Outputs

The campaign produces:

- machine-readable inventories and unified trace graph;
- feature, entity, protocol, UI, resource, authority and persistence matrices;
- Ghidra export manifests and reproducible verifiers;
- bounded live-slice contracts and adjudicated receipts;
- original behavior contracts and explicit Unknowns;
- `NEW_DESIGN` decisions for dormant/manual-only/server-lost behavior;
- implementation issues whose acceptance criteria name the exact missing trace boundary;
- per-unit reports and handoffs.

Raw captures remain hash-bound evidence. Reports and implementation contracts must be reproducible without trusting analyst prose.

## 12. Completion criteria

The exhaustive reverse-engineering milestone is complete only when:

1. all six inventories regenerate successfully from frozen inputs;
2. every row has a disposition and provenance;
3. every feature/entity has a first-missing-boundary or a closed vertical trace;
4. no unadjudicated orphan, unexplained stub, silent placeholder, or unclassified manual feature remains;
5. every `SHIPPED_REACHABLE` feature is at least statically mapped and assigned a live-validation requirement;
6. every implemented feature is linked to authority, persistence, player-visible, faction/role and independent-review evidence as applicable;
7. dormant and manual-only features have an approved `NEW_DESIGN` contract before implementation;
8. the final replacement feature matrix contains zero unimplemented, stubbed, skipped, or provenance-free rows.

This milestone does not by itself claim the game is fully implemented or playable. Complete product status additionally requires the two-client authoritative gameplay and deployment criteria in the project goal.
