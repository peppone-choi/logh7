# LOGH7 greenfield design

## Decision

Use one monorepo. The client, authoritative server, wire contracts, PostgreSQL schema, legacy resource importers, Korean localization, and multi-VM QA harness must evolve as one playable vertical slice. Separate repositories would introduce contract and evidence drift before independent release cadences or teams exist.

## Runtime architecture

The player-facing engine is C++23. SDL3 owns windowing, input, and platform services; a modern Direct3D backend renders imported legacy models and textures through narrow engine interfaces. Legacy files are never read ad hoc by gameplay code. `LegacyDataProvider` validates an external data root, records hashes, decodes known formats, and publishes stable resource IDs to the object registry.

The archived 1024x768 layout is an oracle, not a new-client limit. The new client defaults to 1920x1080 and supports 1280x720 through 3840x2160 with Windows per-monitor-v2 DPI awareness at 100%, 150%, and 200%. World rendering and UI layout use separate logical coordinate systems. HUD panels use anchors, safe areas, nine-slice backgrounds, and scalable Unicode fonts; tactical radar, command trays, chat, status, and modal dialogs may be rearranged when the original fixed layout clips or overlaps at 16:9. Every intentional layout change preserves the original information and is recorded as authored.

The dedicated authoritative server is C# on the supported .NET LTS release. It publishes a self-contained Windows x64 executable for local development and small private servers, plus a Linux x64 container for production on a dedicated PC, home server, or VPS. Linux Docker is the production default; PostgreSQL remains a separate Linux container. The server owns account registration, verification, authentication, recovery and suspension as well as sessions, accelerated game time, characters, factions, authority cards, strategy commands, tactical battles, economy, AI, mail, messenger, chat, persistence, replay, and administration. Account identity and game-character identity are separate. Passwords use Argon2id with per-account salts; refresh tokens are hashed at rest and can be individually revoked. Clients submit commands and render ordered results; they do not mutate authoritative state.

`Logh7Admin.exe` is a separate self-contained Windows x64 WPF application that may run on any operator workstation. It never connects directly to PostgreSQL. It reaches the versioned HTTPS administrator API through a private VPN or SSH tunnel by default; the administrator API is not exposed directly to the public Internet. The API enforces role-based permissions and step-up authentication for destructive operations. The tool manages accounts and sanctions, live players, sessions, tactical battles, notices, communication moderation, editable content and balance data, backups, health, drain, and shutdown. Every mutation records administrator ID, reason, before/after values, correlation ID, and timestamp in an append-only audit log. Editable content follows draft, validated, published, and retired revisions so an incomplete edit cannot mutate a running session silently.

PostgreSQL runs only through Docker. The image is pinned by digest, the database directory is bind-mounted on `E:`, host exposure is limited to loopback, restart is disabled for development, and CPU/memory/PID limits prevent background load from blocking the workstation. Schema changes are migrations. Every lore or balance row is editable and carries provenance: `observed`, `inferred`, `authored`, or `authored-placeholder`.

## Tactical entry recovery

Treat tactical entry as an observable state machine, not one scene call:

1. server detects opposing forces in one strategy grid;
2. server emits the mode-change and tactical notification sequence;
3. client receives the complete tactical bootstrap snapshot;
4. client imports the tactical field and obstacles;
5. client creates bases, corps, troops, ships, camera, radar, and UI;
6. both clients acknowledge the same battle ID, authority version, and state hash;
7. commands are enabled only after the ready barrier.

Each boundary has structured logs, timeouts, failure codes, and replay fixtures. Static analysis follows the fresh CD binary around `NotifyChangeMode`, `NotifyTactics`, `WorldIn_TacticsFieldImport`, `MakeTacticsUnit`, and `WSeq00_Battle`; dynamic analysis breaks on their callers and on file/network APIs inside an isolated oracle VM.

## Isolation and QA

VMware stores all VM files on `E:`. The original oracle VM is Windows XP SP3 32-bit with 2 vCPU, 2 GB RAM, 30 GB disk, 1024x768 display, DirectX 9.0c, 3D acceleration, a read-only CD/data mount, and no general Internet access. New-client VMs use Windows 10/11 64-bit, 4 vCPU, 6 GB RAM, 40 GB sparse differencing disks, 1920x1080 display, and 3D acceleration. Maintain at least `client-a` and `client-b`; clone `client-c` for tactical and reconnect scenarios. A host-only lab network reaches only the controlled server and capture gateway.

Acceptance requires visible, same-run evidence from at least two separate client VMs plus server and PostgreSQL receipts. Unit tests, fixtures, decompiler output, or one client alone do not establish multiplayer or gameplay completion.
