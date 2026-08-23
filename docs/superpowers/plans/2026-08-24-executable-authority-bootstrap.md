# Executable Authority Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce runnable `Logh7Client.exe`, `Logh7Server.exe`, Linux server container, `Logh7Launcher.exe`, and `Logh7Admin.exe` artifacts that connect two client instances to one resource-limited Docker PostgreSQL-backed authoritative server.

**Architecture:** The C++23 Windows client owns the native window and legacy-data boundary. The .NET 10 server owns authoritative sessions and persists them to PostgreSQL. A .NET launcher performs preflight checks, starts or verifies the bounded development services, and launches independent client profiles without embedding original game data.

**Tech Stack:** Visual Studio 2026 MSVC 19.51, CMake 4.4.2, Ninja 1.13.2, C++23/Win32, .NET SDK 10.0.301, ASP.NET Core, Npgsql, xUnit, Docker Desktop, PostgreSQL 17.11 pinned by digest, PowerShell 7.

**Spec:** `docs/architecture/2026-08-24-greenfield-design.md`

## Global Constraints

- The repository root is `E:\logh7-greenfield`; no prior LOGH7 implementation is read or copied.
- Original assets, VM disks, packet captures, database volumes, secrets, and generated builds remain outside Git.
- PostgreSQL uses `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73` and stores data on `E:`.
- Development services bind to loopback, use `restart: no`, and have CPU, memory, and PID limits.
- Every executable supports `--version`; failure exits non-zero with one actionable error line.
- No multiplayer claim is allowed until two independent clients are observed connected to the same server run.

---

### Task 0: Reproducible toolchain preflight

**Files:**
- Create: `tools/preflight.ps1`

**Interfaces:**
- Consumes: Windows PATH plus the installed Visual Studio 2026 Community instance.
- Produces: one JSON object containing exact versions and exit `0`, or one actionable missing-tool error and exit `1`.

- [ ] **Step 1: Write the failing preflight invocation**

Run: `pwsh -File tools/preflight.ps1`.

Expected before the file exists: FAIL because `tools/preflight.ps1` is absent.

- [ ] **Step 2: Implement exact executable discovery**

Resolve CMake from `C:\Program Files\CMake\bin\cmake.exe`, Ninja from the winget package or current PATH, .NET from `dotnet`, Docker from `C:\Program Files\Docker\Docker\resources\bin\docker.exe`, PowerShell from the current process, Git from the configured bundled/runtime path, and MSVC by invoking Visual Studio's `VsDevCmd.bat` followed by `cl`. Require CMake `4.4.2`, Ninja `1.13.2`, .NET SDK `10.0.301`, Docker Server `28.3.0` or newer in major 28, PowerShell `7` or newer, and MSVC `19.51`.

- [ ] **Step 3: Run the preflight twice**

Expected: both runs exit `0` and emit the same tool paths and versions. PATH aliases are optional because the script records absolute fallbacks.

- [ ] **Step 4: Commit the preflight boundary**

```text
build: verify executable bootstrap toolchain

Constraint: Fail before implementation when a pinned tool is unavailable
Confidence: high
Scope-risk: narrow
```

### Task 1: Native client executable and deterministic build

**Files:**
- Create: `CMakeLists.txt`
- Create: `CMakePresets.json`
- Create: `apps/client/CMakeLists.txt`
- Create: `apps/client/src/main.cpp`
- Create: `apps/client/tests/client_smoke.ps1`
- Create: `qa/capture_owned_window.ps1`

**Interfaces:**
- Consumes: command-line options `--version`, `--profile <name>`, `--server <http-url>`, `--session <uuid>`, `--legacy-root <path>`, `--resolution <width>x<height>`, `--smoke-exit-ms <n>`.
- Produces: `build/windows-release/bin/Logh7Client.exe`; process exit `0` after a visible smoke window, `2` for invalid arguments, `3` for invalid legacy root.

- [ ] **Step 1: Write the failing smoke script**

```powershell
$exe = Join-Path $PSScriptRoot '..\..\..\build\windows-release\bin\Logh7Client.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw "missing $exe" }
& $exe --version
if ($LASTEXITCODE -ne 0) { throw 'version failed' }
& $exe --profile client-a --server http://127.0.0.1:47910 --resolution 1280x720 --smoke-exit-ms 1000
if ($LASTEXITCODE -ne 0) { throw 'window smoke failed' }
```

- [ ] **Step 2: Run the test and confirm the missing-executable failure**

Run: `pwsh -File apps/client/tests/client_smoke.ps1`

Expected: FAIL with `missing ...Logh7Client.exe`.

- [ ] **Step 3: Add the minimal CMake and Win32 implementation**

`main.cpp` must parse the exact options above, declare Windows per-monitor-v2 DPI awareness, register `LOGH7_GREENFIELD_CLIENT`, create a 1920x1080 default overlapped window titled `LOGH7 Greenfield - <profile>`, paint a dark anchored strategy-grid background, show the server endpoint without clipping, and use a timer only when `--smoke-exit-ms` is present. Production mode has no auto-exit. `--resolution` accepts 1280x720, 1920x1080, 2560x1440, and 3840x2160 in this slice.

```cpp
struct ClientOptions {
    std::wstring profile = L"default";
    std::wstring server = L"http://127.0.0.1:47910";
    std::optional<std::wstring> sessionId;
    std::filesystem::path legacyRoot;
    std::optional<unsigned> smokeExitMs;
};

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR commandLine, int showCommand);
```

- [ ] **Step 4: Configure, build, and run the smoke script**

Run: `cmake --preset windows-release`, `cmake --build --preset windows-release`, then `pwsh -File apps/client/tests/client_smoke.ps1`.

Expected: all build and timed-smoke commands exit `0`. For capture, do not pass `--smoke-exit-ms`: `$client = Start-Process $exe -ArgumentList '--profile','client-a','--server','http://127.0.0.1:47910','--resolution','1280x720' -PassThru`; then run `qa/capture_owned_window.ps1 -ProcessId $client.Id -WaitSeconds 10 -Output qa/evidence/<runId>/client-a-1280x720.png` inside `try`, and in `finally` call `Stop-Process -Id $client.Id` only if the same owned process is still running. The capture script uses `PrintWindow` against only the supplied process's HWND and writes a sibling JSON receipt with process ID, executable path, process start time, HWND, client dimensions, DPI, capture timestamp, and SHA-256. The PNG must show the complete grid, profile, and server endpoint with no clipping. Repeat with a newly owned process at 1920x1080; expected output is `client-a-1920x1080.png` and matching receipt.

- [ ] **Step 5: Commit the native executable slice**

```text
feat(client): bootstrap native Windows executable

Constraint: Greenfield implementation with no legacy binary dependency
Confidence: high
Scope-risk: narrow
```

### Task 2: PostgreSQL Compose service and migration boundary

**Files:**
- Create: `infra/compose/compose.yaml`
- Create: `infra/compose/.env.example`
- Create: `infra/compose/prepare_env.ps1`
- Create: `db/migrations/0001_bootstrap.sql`
- Create: `db/seeds/0001_development.sql`
- Create: `infra/compose/postgres_smoke.ps1`

**Interfaces:**
- Consumes: `LOGH7_DB_DATA`, `LOGH7_DB_PASSWORD`, loopback port `55432`; `prepare_env.ps1` supplies safe development values when no ignored `.env` exists.
- Produces: healthy PostgreSQL database `logh7`, role `logh7`, tables `schema_migration`, `world_session`, and `content_record`.

- [ ] **Step 1: Write the failing container smoke script**

Run `pwsh -File infra/compose/prepare_env.ps1` first. It creates ignored `infra/compose/.env` only when absent, setting `LOGH7_DB_DATA=E:/logh7-greenfield/data/postgres-dev` and `LOGH7_DB_PASSWORD=logh7_dev_only`; it refuses a non-E: data path. The smoke script passes `--env-file infra/compose/.env`, runs `docker compose config`, starts only `postgres`, waits for `healthy`, and executes:

```sql
select current_database(), current_user;
select to_regclass('public.world_session');
```

It fails unless the results are `logh7|logh7` and `world_session`.

- [ ] **Step 2: Confirm the service definition is missing**

Run: `pwsh -File infra/compose/prepare_env.ps1`, then `pwsh -File infra/compose/postgres_smoke.ps1`.

Expected: FAIL because `compose.yaml` or the bootstrap table is absent.

- [ ] **Step 3: Add the bounded Compose service**

`compose.yaml` must set project name `logh7-dev` and create network `logh7-dev-net`. Its `postgres` service uses the pinned digest, `127.0.0.1:55432:5432`, `restart: no`, `cpus: 1.0`, `mem_limit: 1g`, `pids_limit: 256`, a `pg_isready` healthcheck, and `${LOGH7_DB_DATA}:/var/lib/postgresql/data`. It also defines a `server` service built from `apps/server/Logh7.Server/Dockerfile`, dependent on healthy `postgres`, with `127.0.0.1:47910:47910`, `restart: no`, `cpus: 2.0`, `mem_limit: 2g`, `pids_limit: 512`, `ASPNETCORE_URLS=http://0.0.0.0:47910`, `LOGH7_DB_CONNECTION=Host=postgres;Port=5432;Database=logh7;Username=logh7;Password=${LOGH7_DB_PASSWORD}`, and `${LOGH7_SERVER_LOGS}:/app/logs`. The server binary implements `healthcheck --url http://127.0.0.1:47910/health`, exiting `0` only when both status and database are `ok`; Compose uses that command with interval `5s`, timeout `3s`, start period `10s`, and `12` retries. The Dockerfile repeats the same `HEALTHCHECK`. `prepare_env.ps1` sets `LOGH7_SERVER_LOGS=E:/logh7-greenfield/data/server-dev-logs` and rejects non-E: DB or log paths. The checked-in `.env.example` contains the same development-only defaults and an explicit warning that production must override the password. The SQL migration creates UUID-keyed sessions, `authority_version bigint`, `authority_state_hash char(64)`, JSONB configuration, and provenance-constrained editable content rows.

- [ ] **Step 4: Run the smoke script twice**

Expected: both runs exit `0`, proving migration idempotency and a healthy persisted database.

- [ ] **Step 5: Commit the PostgreSQL boundary**

```text
feat(infra): add bounded PostgreSQL development service

Constraint: Store database state on E drive and expose loopback only
Confidence: high
Scope-risk: narrow
```

### Task 3: Authoritative self-contained server executable

**Files:**
- Create: `global.json`
- Create: `Directory.Build.props`
- Create: `apps/server/Logh7.Server/Logh7.Server.csproj`
- Create: `apps/server/Logh7.Server/Program.cs`
- Create: `apps/server/Logh7.Server/Accounts/AccountStore.cs`
- Create: `apps/server/Logh7.Server/Accounts/PasswordHasher.cs`
- Create: `apps/server/Logh7.Server/Admin/BootstrapOwnerCommand.cs`
- Create: `apps/server/Logh7.Server/Storage/SessionStore.cs`
- Create: `apps/server/Logh7.Server/Dockerfile`
- Create: `apps/server/Logh7.Server.Tests/Logh7.Server.Tests.csproj`
- Create: `apps/server/Logh7.Server.Tests/AccountApiTests.cs`
- Create: `apps/server/Logh7.Server.Tests/SessionApiTests.cs`

**Interfaces:**
- Consumes: `LOGH7_DB_CONNECTION`, HTTP loopback endpoint `127.0.0.1:47910`.
- Produces: `GET /health`, account endpoints, `POST /v1/sessions`, `GET /v1/sessions/{id}`, `POST /v1/sessions/{id}/connections`, `GET /v1/sessions/{id}/connections`, self-contained Windows x64 `Logh7Server.exe`, and a Linux x64 OCI image.

- [ ] **Step 1: Write failing API tests**

```csharp
[Fact]
public async Task RegisterRejectsDuplicateNormalizedUserName()
{
    var request = new { userName = "AdmiralYang", email = "yang@example.invalid", password = "correct horse battery staple" };
    (await client.PostAsJsonAsync("/v1/accounts/register", request)).EnsureSuccessStatusCode();
    var duplicate = await client.PostAsJsonAsync("/v1/accounts/register", request with { userName = "ADMIRALYANG" });
    Assert.Equal(HttpStatusCode.Conflict, duplicate.StatusCode);
}

[Fact]
public async Task CreateThenReadSessionPreservesAuthorityIdentity()
{
    var created = await client.PostAsJsonAsync("/v1/sessions", new { name = "dev-session" });
    created.EnsureSuccessStatusCode();
    var body = await created.Content.ReadFromJsonAsync<SessionResponse>();
    Assert.Equal(0, body!.AuthorityVersion);
    Assert.Matches("^[0-9a-f]{64}$", body.AuthorityStateHash);
}
```

- [ ] **Step 2: Run the focused test and confirm the endpoint failure**

Run: `dotnet test apps/server/Logh7.Server.Tests/Logh7.Server.Tests.csproj --filter CreateThenReadSessionPreservesAuthorityIdentity`.

Expected: FAIL because the server project and account/session endpoints are absent.

- [ ] **Step 3: Implement the minimal endpoint and Npgsql store**

Use an explicit `AccountStore` and `SessionStore`. Normalize user names and email addresses before unique-index checks. Hash passwords with Argon2id using a per-account random salt and stored work parameters; never log passwords or tokens. Store only SHA-256 hashes of random 256-bit refresh and reset tokens, rotate refresh tokens after use, support individual logout/revocation, and rate-limit registration/login/reset boundaries. Development mode may return a verification code to the local launcher; production mode must send it through the configured mail provider and never include it in API responses. `SessionStore` exposes `CreateAsync(string name, CancellationToken)` and `GetAsync(Guid id, CancellationToken)`, uses parameterized SQL only, and computes the initial SHA-256 from canonical UTF-8 bytes `sessionId|0|name`; it never trusts a client-supplied version or hash.

- [ ] **Step 4: Run tests and publish the server**

Run: `dotnet test`; publish Windows with `dotnet publish apps/server/Logh7.Server/Logh7.Server.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true`; build and start Linux with `docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml up -d --build server`.

Expected: tests pass and the Windows publish directory contains `Logh7Server.exe`. `Invoke-RestMethod http://127.0.0.1:47910/health` returns `{ "status": "ok", "database": "ok" }`; `docker compose ps server` reports healthy; inspecting the Compose server container reports network `logh7-dev-net`, restart `no`, memory `2147483648`, and the E:-backed log bind. Stop only the owned Compose project with `docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml down` after capturing its logs.

- [ ] **Step 5: Commit the authoritative server slice**

```text
feat(server): persist authoritative world sessions

Constraint: PostgreSQL is the only durable state authority
Confidence: high
Scope-risk: moderate
```

### Task 4: Legacy data root validator

**Files:**
- Create: `apps/client/src/legacy/LegacyDataValidator.h`
- Create: `apps/client/src/legacy/LegacyDataValidator.cpp`
- Create: `apps/client/tests/legacy_data_validator_tests.cpp`
- Create: `local-data/README.md`

**Interfaces:**
- Consumes: a user-selected external directory and an expected manifest of relative path, byte length, and SHA-256.
- Produces: `LegacyDataValidation { bool accepted; vector<FileReceipt> receipts; vector<ValidationError> errors; }`.

- [ ] **Step 1: Write tests for missing, mismatched, and valid files**

Use generated temporary bytes, never archived game files. Assert fail-closed behavior for a missing file, wrong size, wrong digest, path escape, and reparse point; assert success only when every receipt matches.

- [ ] **Step 2: Run the focused CTest target and confirm missing symbols**

Run: `ctest --preset windows-release -R legacy_data_validator --output-on-failure`.

- [ ] **Step 3: Implement the validator with Windows CNG SHA-256**

Gameplay code receives logical resource IDs only. The validator normalizes the root, rejects paths outside it, opens files read-only, hashes with BCrypt, and never executes or loads `G7MTClient.exe`.

- [ ] **Step 4: Run CTest and the client smoke script**

Expected: tests pass; client exits `3` for an invalid `--legacy-root` and still runs without that option in developer smoke mode.

- [ ] **Step 5: Commit the data boundary**

```text
feat(client): validate external legacy data root

Constraint: Original game files remain external and read-only
Confidence: high
Scope-risk: moderate
```

### Task 5: Launcher executable and two-client host smoke

**Files:**
- Create: `apps/launcher/Logh7.Launcher/Logh7.Launcher.csproj`
- Create: `apps/launcher/Logh7.Launcher/Program.cs`
- Create: `apps/launcher/Logh7.Launcher/ProcessSupervisor.cs`
- Create: `apps/client/src/net/ServerClient.h`
- Create: `apps/client/src/net/ServerClient.cpp`
- Create: `apps/server/Logh7.Server/Sessions/ConnectionEndpoints.cs`
- Create: `qa/scenarios/two_client_bootstrap.ps1`

**Interfaces:**
- Consumes: `--repo-root`, `--legacy-root`, `--clients <n>`, `--no-start-db`.
- Produces: `Logh7Launcher.exe`, bounded child processes, two server-recorded connections in one session, profile-specific logs, and `qa/evidence/<runId>/receipt.json`.

- [ ] **Step 1: Write a failing two-client scenario**

The scenario creates one session through `Logh7Server.exe`, launches profiles `client-a` and `client-b` with the same `--session` UUID, requires two distinct PIDs and window titles, and polls `GET /v1/sessions/{id}/connections` until it returns distinct `client-a` and `client-b` connection rows for the current run. It records the session ID, connection IDs, server process ID, client process IDs, and all exit codes. It must never kill processes it did not start.

- [ ] **Step 2: Confirm the missing launcher failure**

Run: `pwsh -File qa/scenarios/two_client_bootstrap.ps1`.

- [ ] **Step 3: Implement lifecycle supervision**

`ProcessSupervisor` records every owned PID, starts PostgreSQL only when unhealthy, starts the server with a run-specific log directory, and exposes launcher flows for registration, verification, login, password reset, logout, and profile selection before launching clients with distinct profiles. It stores refresh tokens with Windows Credential Manager, never in plain-text configuration. `ServerClient` uses WinHTTP to POST `{ profile, runId }` to `/v1/sessions/{id}/connections`, requires a successful response before showing `CONNECTED`, and sends a disconnect request during graceful close. The server rejects duplicate `(sessionId, profile, runId)` rows but permits a new run after the previous connection closes. Shutdown order is: stop new joins, request server drain, wait for server exit, then optionally stop the Compose project it started.

- [ ] **Step 4: Publish and manually observe the slice**

Run the scenario, confirm two 1920x1080 client windows are simultaneously visible with the same session UUID and `CONNECTED`, verify the server endpoint returns exactly both profiles for the current run, and verify `docker stats --no-stream` stays within the configured bounds. Capture the owned windows and receipt without claiming VM multiplayer.

- [ ] **Step 5: Commit the executable bootstrap**

```text
feat(launcher): supervise bounded two-client development run

Constraint: Never terminate unowned processes or persistent user services
Confidence: medium
Scope-risk: moderate
```

### Task 6: Authenticated server administration executable

**Files:**
- Create: `apps/admin/Logh7.Admin/Logh7.Admin.csproj`
- Create: `apps/admin/Logh7.Admin/App.xaml`
- Create: `apps/admin/Logh7.Admin/MainWindow.xaml`
- Create: `apps/admin/Logh7.Admin/AdminApiClient.cs`
- Create: `apps/server/Logh7.Server/Admin/AdminEndpoints.cs`
- Create: `apps/server/Logh7.Server/Admin/AdminAuditStore.cs`
- Create: `apps/server/Logh7.Server.Tests/AdminApiTests.cs`

**Interfaces:**
- Consumes: private HTTPS administrator endpoint, administrator access token, reason text, expected row version, versioned `/v1/admin/*` API.
- Produces: self-contained `Logh7Admin.exe`, role-checked mutations, immutable audit rows, and draft/validated/published content revisions.

- [ ] **Step 1: Write failing authorization and audit tests**

```csharp
[Fact]
public async Task SuspendAccountRequiresOperatorRoleReasonAndMatchingVersion()
{
    using var request = new HttpRequestMessage(HttpMethod.Post, $"/v1/admin/accounts/{accountId}/suspend")
    {
        Content = JsonContent.Create(new { reason = "chat abuse receipt 42", expectedVersion = 3 })
    };
    request.Headers.Authorization = new("Bearer", playerToken);
    Assert.Equal(HttpStatusCode.Forbidden, (await client.SendAsync(request)).StatusCode);
    Assert.Empty(await auditStore.ReadAsync(accountId));
}
```

- [ ] **Step 2: Run focused tests and confirm missing endpoints**

Run: `dotnet test apps/server/Logh7.Server.Tests/Logh7.Server.Tests.csproj --filter AdminApiTests`.

Expected: FAIL because the administrator API and audit store are absent.

- [ ] **Step 3: Implement the minimal secure administrator boundary**

Roles are `viewer`, `moderator`, `operator`, and `owner`. Read-only health/player/session views require `viewer`; mute/kick/mail-chat moderation requires `moderator`; account suspension, session pause/end, content publication, backup, drain, and shutdown require `operator`; role assignment requires `owner` plus step-up authentication. Every mutation requires a non-empty reason and optimistic row version. `AdminAuditStore` writes actor, action, target, reason, correlation ID, before JSON, after JSON, and UTC timestamp in the same transaction as the mutation.

- [ ] **Step 4: Build the WPF administrator application**

The first window provides endpoint selection, login, server health, live-player/session lists, account search and suspension, notice publication, content revision list, audit search, and drain/shutdown controls. It rejects plain HTTP except loopback development mode. Remote profiles assume VPN or SSH-tunnel reachability and validate the server certificate. Dangerous controls show the exact target, require typed reason, and display the returned audit ID. Tokens are stored only in Windows Credential Manager.

`Logh7Server.exe admin bootstrap-owner --user <name> --password-stdin` is the only first-owner bootstrap path. It uses `AccountStore` and `AdminAuditStore`, succeeds only when no owner exists, reads the password from stdin, never echoes or logs it, and writes a `bootstrap-owner` audit row. A second invocation exits non-zero. After bootstrap, `POST /v1/admin/auth/login` returns an owner token used by the positive API and UI smoke; no administrator tool or test writes PostgreSQL directly.

- [ ] **Step 5: Publish and smoke-test `Logh7Admin.exe`**

Run the first-owner command through redirected stdin, assert a second bootstrap is rejected, then call `/v1/admin/auth/login`. Publish with `dotnet publish apps/admin/Logh7.Admin/Logh7.Admin.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true`; exercise one read-only query and one reversible development-account suspension using the owner token. Verify the API returns an audit ID, the audit search shows matching before/after values and reason, and the UI refresh shows the suspended state.

- [ ] **Step 6: Commit the administration boundary**

```text
feat(admin): add audited server administration tool

Constraint: Administrative writes use the API and never direct database access
Confidence: medium
Scope-risk: moderate
```

### Task 7: VM-ready packaging contract

**Files:**
- Create: `qa/vmware/client-vm-spec.json`
- Create: `qa/vmware/oracle-vm-spec.json`
- Create: `qa/vmware/verify_vm_spec.ps1`
- Create: `docs/operations/running-the-game.md`

**Interfaces:**
- Consumes: VMware `.vmx` paths supplied outside Git.
- Produces: validated resource/network requirements and exact player/server launch instructions.

- [ ] **Step 1: Write validation fixtures**

The validator rejects VM specs below the approved CPU/RAM/disk/display values, VM paths on C:, NAT/bridged networking for the oracle VM, duplicate MAC addresses, and fewer than two new-client VM entries.

- [ ] **Step 2: Confirm fixture failures**

Run: `pwsh -File qa/vmware/verify_vm_spec.ps1 -Fixture qa/vmware/fixtures/invalid.json`.

- [ ] **Step 3: Add the exact approved specifications**

Oracle: Windows XP SP3 x86, 2 vCPU, 2 GB RAM, 30 GB disk, 1024x768, DirectX 9.0c, 3D acceleration, host-only network. New client: Windows 10/11 x64, 4 vCPU, 6 GB RAM, 40 GB sparse disk, 1920x1080 display, 3D acceleration, host-only network. Require `client-a` and `client-b`; allow `client-c`.

- [ ] **Step 4: Verify documentation and packaged commands**

Document and execute these exact smoke paths: (1) `Logh7Launcher.exe run --clients 2 --session new --legacy-root <path>` returns one session UUID and two connected profiles; (2) direct Windows flow runs `pwsh -File infra/compose/prepare_env.ps1`, `docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml up -d postgres`, waits for PostgreSQL healthy, sets `$env:LOGH7_DB_CONNECTION='Host=127.0.0.1;Port=55432;Database=logh7;Username=logh7;Password=logh7_dev_only'`, starts `Logh7Server.exe --urls http://127.0.0.1:47910`, waits until `Logh7Server.exe healthcheck --url http://127.0.0.1:47910/health` exits `0`, then starts two `Logh7Client.exe --server http://127.0.0.1:47910 --session <uuid> --profile client-a|client-b --resolution 1920x1080` processes; (3) Docker flow runs `docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml up -d --build postgres server` and `/health` returns status/database `ok`; (4) `Logh7Admin.exe --endpoint https://<private-endpoint>` rejects an untrusted certificate and the loopback development profile connects successfully. For each path, record command, exit code, owned PIDs/containers, server session/connection rows, log paths, and screenshot hashes in `qa/evidence/<runId>/receipt.json`. Document graceful shutdown, log locations, and interrupted-run recovery.

- [ ] **Step 5: Commit the VM packaging contract**

```text
docs(ops): define isolated multi-client runtime contract

Constraint: VM and database state live on E drive
Confidence: high
Scope-risk: narrow
```
