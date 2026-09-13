using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Threading.Channels;
using Logh7.Server.Authority;
using Logh7.Server.Compatibility;
using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public enum NaturalAuthoritySessionState
{
    AwaitPhase1,
    AwaitPhase3,
    AwaitFirstApplication,
    LoginAcceptedSent,
    AwaitLobbyLogin,
    LobbyReady,
    LobbyRedirectSent,
    SessionServerReady,
    Rejected
}

public enum NaturalAuthoritySessionStatus
{
    Success,
    Invalid
}

public readonly record struct NaturalAuthoritySessionResult(
    NaturalAuthoritySessionStatus Status,
    ushort? ResponseOuterControl,
    byte[]? ResponseTransportPrefix,
    byte[]? ResponsePayload,
    ushort? ObservedApplicationType,
    string? ErrorCode,
    OriginalLoginInputShape? OriginalLoginInputShape = null,
    string? RejectedApplicationPayloadHex = null,
    IReadOnlyList<NaturalAuthorityPush>? AdditionalResponses = null,
    IReadOnlyList<NaturalAuthorityPush>? ResponsesBeforePrimary = null,
    string? ResponseMetadata = null);

public readonly record struct NaturalAuthorityPush(
    ushort OuterControl,
    byte[] TransportPrefix,
    byte[] Payload);

public sealed partial class NaturalAuthoritySession
{
    // Raw authority notifications are encoded only by the connection actor.
    // The registry fails lagging observers instead of silently dropping events.
    // Damage/completion publication is connected; movement's clock owner is not.
    internal Channel<OriginalTacticalNotificationBatch> PendingNotifications { get; } = Channel.CreateBounded<OriginalTacticalNotificationBatch>(
        new BoundedChannelOptions(64) { SingleReader = true, FullMode = BoundedChannelFullMode.Wait });
    private static readonly Lazy<OriginalBattlefieldCatalog> BattlefieldCatalog =
        new(()=>OriginalBattlefieldCatalog.LoadConfigured(
            Environment.GetEnvironmentVariable("LOGH7_BATTLEFIELD_CATALOG")));
    // The authored, user-approved point economy. Costs charged against it come
    // from the original client where the client states them; the balances,
    // regeneration and cap do not.
    private static readonly Lazy<OriginalCommandPointPolicy> CommandPointPolicy =
        new(()=>OriginalCommandPointPolicy.LoadConfigured(
            Environment.GetEnvironmentVariable("LOGH7_COMMAND_POINT_POLICY")));

    private readonly byte[] _serverOutboundKey;
    private readonly uint _lobbyServerIpv4;
    private readonly uint _sessionServerIpv4;
    private readonly ushort _sessionServerPort;
    private readonly OriginalLoginAuthority _loginAuthority;
    private readonly HandoffRegistry _handoffs;
    private readonly IAccountStore _store;
    private readonly MetadataOnlyGatewayReceipt _receipt;
    private readonly OriginalGameClock _gameClock;
    private readonly OriginalBattlefieldCatalog? _battlefieldCatalog;
    private readonly OriginalStaticArmsTable? _staticArms;
    private OriginalBattlefieldCatalog ActiveBattlefieldCatalog =>
        _battlefieldCatalog ?? BattlefieldCatalog.Value;
    private readonly string? _serverNotice;
    private readonly Guid _returnBaseRequestScope = Guid.NewGuid();
    private readonly Guid _moveGridRequestScope = Guid.NewGuid();

    private byte[]? _clientOutboundKey;
    private byte[]? _lobbyCode;
    private uint _clientSequenceBaseline;
    private uint _nextServerApplicationSequence = 1;
    private uint _pendingHandoffToken;
    private Guid _accountId;
    private string? _normalizedLogin;
    private OriginalCreateCharacterCommand? _createdCharacter;
    private uint _worldPcp;
    private uint _worldMcp;
    // 2026-09-03: the character's CURRENT post card, loaded from original_character_card by
    // RestorePersistedCharacterAsync (falling back to the authored AuthorityCardId when no appointment row exists).
    // 辞任 (0x0709) writes card 0 = 個人 here, which the client renders as 「皇宮 ： 個人」 with no commands.
    private ushort _worldCardId = OriginalAuthoredPlayableCatalog.AuthorityCardId;
    private uint _worldCharacterId;
    private uint _worldGridUnitId;
    private uint _worldGridCellId = OriginalAuthoredPlayableCatalog.CurrentGridCell;
    // Compatibility seed only; an authority-store row replaces this on restore.
    private uint _worldGridBaseId = OriginalAuthoredPlayableCatalog.BaseId;
    private OriginalTacticalUnitShipRecord? _tacticalUnitShip;
    private readonly OriginalTacticalBattleRegistry _battles;
    private IDisposable? _battleSubscription;
    private uint? _subscribedBattleGrid;
    private Guid _battleSubscriptionId;
    private bool _participantPublished;
    private uint? _npcSceneImportedGrid;
    private uint? _npcSceneBootstrapGrid;
    private OriginalTacticalEncounter _tacticalEncounter
    {
        get
        {
            var number = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number;
            if (_worldEntered && _subscribedBattleGrid != _worldGridCellId)
            {
                _battleSubscription?.Dispose();
                _battleSubscriptionId = Guid.NewGuid();
                _battleSubscription = _battles.Subscribe(_worldGridCellId, number, PendingNotifications.Writer,
                    _worldGridUnitId, _battleSubscriptionId, primaryNpcKnown: HasPrimaryTacticalNpc,
                    tacticalActive: IsCurrentTacticalFieldActive, participatesInCombat: !IsRecoveringFromInjury);
                _subscribedBattleGrid = _worldGridCellId;
            }
            return _battles.GetEncounter(_worldGridCellId, number);
        }
    }

    internal void CloseNotifications()
    {
        _participantPublished = false;
        _npcSceneImportedGrid = null;
        _npcSceneBootstrapGrid = null;
        _battles.RemoveParticipant(PendingNotifications.Writer);
        _battleSubscription?.Dispose();
        _battleSubscription = null;
        _battleSubscriptionId = Guid.Empty;
        PendingNotifications.Writer.TryComplete();
    }

    private OriginalOutfitMembership? _playerOutfitMembership;

    private async Task<IReadOnlyList<OriginalTacticalParticipantSnapshot>> ResetBattleObservationForSceneImportAsync(
        CancellationToken cancellationToken)
    {
        var membership = await _store.FindOriginalOutfitMembershipAsync(
            _accountId, _worldCharacterId, cancellationToken);
        if (membership is not null &&
            (membership.CharacterId != _worldCharacterId || membership.Outfit.Power != _createdCharacter?.Power ||
             membership.Outfit.Camp != 0))
            throw new InvalidDataException("PLAYER_OUTFIT_SIDE_UNSUPPORTED");
        _playerOutfitMembership = membership;
        await RestoreFleetRosterAsync(cancellationToken);
        // E065: native 0F02 refresh rebuilds self/NPC entities even when the
        // grid is unchanged. Its former imported actors and queued events no
        // longer belong to the new scene. Leave shared encounter damage intact.
        _battleSubscription?.Dispose();
        _battleSubscription = null;
        _subscribedBattleGrid = null;
        _battleSubscriptionId = Guid.Empty;
        _npcSceneImportedGrid = null;
        _npcSceneBootstrapGrid = _worldGridCellId;
        _participantPublished = true;
        PublishOwnParticipantSnapshot();
        RegisterAuthoredNpc();
        _battles.NotifyCombatStarted(_worldGridCellId, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number,
            PendingNotifications.Writer);
        return OtherBattleParticipants();
    }

    private void RegisterAuthoredNpc()
    {
        if (IsRecoveringFromInjury) return;
        foreach (var fleet in _store is IOriginalFleetUnitStoreProvider ? [] : CurrentBattlefieldTemplate().Fleets ?? [])
            foreach (var participant in fleet.Project(_worldGridCellId))
                _battles.RegisterNpc(WithCommanderCharacter(participant, fleet),
                    OriginalSubordinateShipCatalog.CapabilitiesFor(participant.Unit.Kind),
                    _staticArms ?? OriginalAuthoredPlayableCatalog.TacticalArms,
                    ActiveBattlefieldCatalog.ProjectBaseObjectives(_worldGridCellId), fleet.Defensive);
        if (!CurrentBattlefieldTemplate().SpawnEnemy) return;
        if (_createdCharacter is not OriginalCreateCharacterCommand character ||
            _worldGridUnitId == 0 || _worldCharacterId == 0) return;
        var ship = OriginalSystemSceneCodec.CreateTacticalBattlefield(
            _worldGridUnitId, _worldCharacterId, CurrentBattlefieldTemplate()).Records[1];
        var npcCharacter = CreateTacticalEnemyCharacter(character);
        _battles.RegisterNpc(new(
            new(ship.Id, _worldGridCellId, 0, 100, 0, 0, 100, 100, 10, CurrentEnemyRegularShipKind()),
            ship, OriginalSystemSceneCodec.CreatePlayableTacticalCorps(ship.Character),
            EncodeLocatedCharacter(ship.Character, ship.Id, 0, npcCharacter), npcCharacter.Power,
            commanderMerit: new(ship.Character, npcCharacter.Rank, npcCharacter.Achievement)),
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities, _staticArms ?? OriginalAuthoredPlayableCatalog.TacticalArms,
            ActiveBattlefieldCatalog.ProjectBaseObjectives(_worldGridCellId));
    }

    private void PublishOwnParticipantSnapshot()
    {
        if (_npcSceneImportedGrid != _worldGridCellId) _npcSceneImportedGrid = null;
        if (_npcSceneBootstrapGrid != _worldGridCellId) _npcSceneBootstrapGrid = null;
        if (IsRecoveringFromInjury)
        {
            _battles.RemoveParticipant(PendingNotifications.Writer);
            return;
        }
        if (!_participantPublished || _createdCharacter is not OriginalCreateCharacterCommand character ||
            _worldGridUnitId == 0 || _worldCharacterId == 0) return;
        _tacticalUnitShip ??= OriginalSystemSceneCodec.CreateTacticalBattlefield(
            _worldGridUnitId, _worldCharacterId, CurrentBattlefieldTemplate()).Records[0];
        _battles.UpdateParticipant(PendingNotifications.Writer, new(CurrentPlayerInformationUnit(),
            _tacticalUnitShip.Value,
            CurrentPlayerCorps,
            EncodeLocatedCharacter(_worldCharacterId, _worldGridUnitId, EffectiveWorldCardId, character), character.Power,
            CurrentShipGeneration, outfit: _playerOutfitMembership?.Outfit,
            commanderMerit: new(_worldCharacterId, character.Rank, character.Achievement)),
            npcTargetReady: _npcSceneImportedGrid == _worldGridCellId,
            persistDamage: BindOwnDamagePersistence());
    }

    // NEW DESIGN NPC boundary, not an invented original Ready message:
    // 004B68F0 calls 004B6E00 only after tactical import/make; 004B6E00
    // polls 0348 with active entity IDs. A time or base poll is insufficient.
    // This proves entity enumeration, not presentation/fade completion.
    // Caller owns the grid lease. An accepted attack also removes protection.
    private void MarkOwnNpcSceneImported(bool acceptedAttack = false)
    {
        if (!_worldEntered || !_participantPublished || IsRecoveringFromInjury ||
            _worldGridUnitId == 0 || !_tacticalEncounter.HasSurvivors(_worldGridUnitId)) return;
        // A queued old-scene poll may arrive after a strategic MoveGrid. Only
        // a snapshot sent for this destination can be acknowledged by its poll.
        if (!acceptedAttack && _npcSceneBootstrapGrid != _worldGridCellId) return;
        _npcSceneImportedGrid = _worldGridCellId;
        PublishOwnParticipantSnapshot();
    }

    private IReadOnlyList<OriginalTacticalParticipantSnapshot> OtherBattleParticipants() =>
        IsRecoveringFromInjury ? [] : _battles.OtherParticipants(_worldGridCellId, _worldGridUnitId);

    private void MarkSceneParticipants(IReadOnlyList<OriginalTacticalParticipantSnapshot> participants) =>
        _battles.MarkProjectedParticipants(_worldGridCellId, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number,
            PendingNotifications.Writer, _battleSubscriptionId, participants.Select(p => p.Unit.Id));

    internal IReadOnlyList<NaturalAuthorityPush> EncodeNotificationBatch(OriginalTacticalNotificationBatch batch)
    {
        // A queued old-grid event may outlive an unsubscribe/re-entry. Drop it
        // before consuming cipher sequences or touching the client's scene.
        if (batch.Grid != _worldGridCellId || batch.SubscriptionId != _battleSubscriptionId ||
            _battleSubscriptionId == Guid.Empty || !HasCurrentShipIncarnation)
            return Array.Empty<NaturalAuthorityPush>();
        return batch.Frames.Select(frame => EncodeApplicationPush(frame.Span)).ToArray();
    }
    private OriginalTacticalCorpsRecord? _tacticalPlayerCorps;
    private OriginalTacticalCorpsRecord CurrentPlayerCorps =>
        _battles.PlayerControl(_worldGridUnitId, CurrentShipGeneration) ??
        _tacticalPlayerCorps ?? OriginalSystemSceneCodec.CreatePlayableTacticalCorps(_worldCharacterId);
    private readonly OriginalTacticalTransitionGate _tacticalTransitionGate = new();
    private bool _worldEntered;
    private ushort? _lobbySelectionValue;
    private uint _messengerSourceCharacterId;
    private uint _messengerTargetCharacterId;

    public NaturalAuthoritySession(
        ReadOnlySpan<byte> serverOutboundKey,
        uint lobbyServerIpv4,
        uint sessionServerIpv4,
        ushort sessionServerPort,
        OriginalLoginAuthority loginAuthority,
        HandoffRegistry handoffs,
        IAccountStore store,
        MetadataOnlyGatewayReceipt receipt,
        OriginalGameClock gameClock,
        string? serverNotice = null,
        OriginalBattlefieldCatalog? battlefieldCatalog = null,
        OriginalTacticalBattleRegistry? battles = null,
        OriginalStaticArmsTable? staticArms = null,
        TimeSpan? baseTravelDelay = null)
    {
        if (serverOutboundKey.Length != OriginalClientCipherHandshake.SessionKeyLength)
        {
            throw new ArgumentException("ORIGINAL_SESSION_KEY_LENGTH", nameof(serverOutboundKey));
        }

        _serverOutboundKey = serverOutboundKey.ToArray();
        _lobbyServerIpv4 = lobbyServerIpv4;
        _sessionServerIpv4 = sessionServerIpv4;
        _sessionServerPort = sessionServerPort;
        _loginAuthority = loginAuthority ?? throw new ArgumentNullException(nameof(loginAuthority));
        _handoffs = handoffs ?? throw new ArgumentNullException(nameof(handoffs));
        _store = store ?? throw new ArgumentNullException(nameof(store));
        _receipt = receipt ?? throw new ArgumentNullException(nameof(receipt));
        _gameClock = gameClock ?? throw new ArgumentNullException(nameof(gameClock));
        _battles = battles ?? new OriginalTacticalBattleRegistry();
        _battlefieldCatalog = battlefieldCatalog;
        _staticArms = staticArms;
        if (baseTravelDelay is { } delay && (delay <= TimeSpan.Zero || delay > TimeSpan.FromDays(1)))
            throw new ArgumentOutOfRangeException(nameof(baseTravelDelay));
        _baseTravelDelay = baseTravelDelay;
        _serverNotice = string.IsNullOrEmpty(serverNotice) ? null : serverNotice;
    }

    public NaturalAuthoritySessionState State { get; private set; }

    public async Task<NaturalAuthoritySessionResult> ProcessAsync(
        ushort outerControl,
        ReadOnlyMemory<byte> payload,
        CancellationToken cancellationToken)
    {
        // Original 00615550 commits an unencrypted, payload-free control0002
        // from the socket timer, independently of application/cipher state.
        // Consume it without an echo, sequence allocation or world mutation.
        if (outerControl == 0x0002 && payload.IsEmpty && State is
            NaturalAuthoritySessionState.AwaitPhase1 or
            NaturalAuthoritySessionState.AwaitPhase3 or
            NaturalAuthoritySessionState.AwaitFirstApplication or
            NaturalAuthoritySessionState.AwaitLobbyLogin or
            NaturalAuthoritySessionState.LobbyReady or
            NaturalAuthoritySessionState.SessionServerReady)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return new(NaturalAuthoritySessionStatus.Success, null, null, null, null, null,
                ResponseMetadata: "transport-heartbeat-received");
        }

        var result = await (State switch
        {
            NaturalAuthoritySessionState.AwaitPhase1 when outerControl == 0x0034 =>
                Task.FromResult(ProcessPhase1(payload.Span)),
            NaturalAuthoritySessionState.AwaitPhase3 when outerControl == 0x0036 =>
                Task.FromResult(ProcessPhase3(payload.Span)),
            NaturalAuthoritySessionState.AwaitFirstApplication when outerControl == 0x0030 =>
                ProcessFirstApplicationAsync(payload, cancellationToken),
            NaturalAuthoritySessionState.AwaitLobbyLogin when outerControl == 0x0030 =>
                ProcessLobbyLoginAsync(payload, cancellationToken),
            NaturalAuthoritySessionState.LobbyReady when outerControl == 0x0030 =>
                ProcessLobbyFollowupAsync(payload, cancellationToken),
            NaturalAuthoritySessionState.SessionServerReady when outerControl == 0x0030 =>
                ProcessSessionServerFollowupAsync(payload, cancellationToken),
            _ => Task.FromResult(Invalid("original.session.unexpected-control"))
        });
        PublishOwnParticipantSnapshot();
        return result;
    }

    private NaturalAuthoritySessionResult ProcessPhase1(ReadOnlySpan<byte> payload)
    {
        var result = OriginalClientCipherHandshake.ProcessPhase1(
            payload, _serverOutboundKey, serverSequence: 1);
        if (result.Status != OriginalClientHandshakeStatus.Success)
        {
            return Invalid(result.ErrorCode!);
        }

        _clientOutboundKey = result.PeerOutboundKey!;
        _clientSequenceBaseline = result.PeerSequenceBaseline;
        State = NaturalAuthoritySessionState.AwaitPhase3;
        return Success(0x0035, result.ResponsePayload, null, null);
    }

    private NaturalAuthoritySessionResult ProcessPhase3(ReadOnlySpan<byte> payload)
    {
        var result = OriginalClientCipherHandshake.ValidatePhase3(payload, _serverOutboundKey);
        if (result.Status != OriginalClientHandshakeStatus.Success)
        {
            return Invalid(result.ErrorCode!);
        }

        State = NaturalAuthoritySessionState.AwaitFirstApplication;
        return Success(null, null, null, null);
    }

    private async Task<NaturalAuthoritySessionResult> ProcessFirstApplicationAsync(
        ReadOnlyMemory<byte> payload,
        CancellationToken cancellationToken)
    {
        var decoded = DecodeApplication(payload.Span);
        if (decoded.Status != OriginalClientInnerFrameStatus.Success)
        {
            return Invalid(decoded.ErrorCode!);
        }

        var type = ReadType(decoded.Payload!);
        if (type == OriginalSessionServerCodec.LoginRequestType)
        {
            return ProcessSessionServerLogin(decoded, type);
        }

        if (type == OriginalClientSessionHandoffMessages.HandoffType)
        {
            var handoff = OriginalClientSessionHandoffMessages.Decode(decoded.Payload!);
            if (handoff.Status != OriginalClientSessionHandoffParseStatus.Success)
            {
                return Invalid(handoff.ErrorCode!);
            }

            _pendingHandoffToken = handoff.Message!.Value.HandoffToken;
            _clientSequenceBaseline = decoded.Sequence;
            State = NaturalAuthoritySessionState.AwaitLobbyLogin;
            return Success(null, null, type, null);
        }

        if (type != OriginalLoginCodec.RequestType)
        {
            return Invalid("original.session.unexpected-application-type");
        }

        var login = await _loginAuthority.ProcessAsync(decoded.Payload!, cancellationToken);
        _clientSequenceBaseline = decoded.Sequence;
        if (login.Code == OriginalLoginResultCode.Malformed)
        {
            return Invalid(login.ErrorCode!);
        }

        var response = login.Code == OriginalLoginResultCode.Accepted
            ? OriginalLoginCodec.EncodeAccepted(
                login.MessageCode!, _lobbyServerIpv4, _sessionServerPort, login.HandoffToken)
            : OriginalLoginCodec.EncodeGenericRejection();
        State = login.Code == OriginalLoginResultCode.Accepted
            ? NaturalAuthoritySessionState.LoginAcceptedSent
            : NaturalAuthoritySessionState.Rejected;
        return EncodeApplicationResponse(
            response,
            type,
            includeLobbyPrefix: false,
            login.InputShape);
    }

    private Task<NaturalAuthoritySessionResult> ProcessLobbyLoginAsync(
        ReadOnlyMemory<byte> payload,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var decoded = DecodeApplication(payload.Span);
        if (decoded.Status != OriginalClientInnerFrameStatus.Success)
        {
            return Task.FromResult(Invalid(decoded.ErrorCode!));
        }

        var type = ReadType(decoded.Payload!);
        if (type == OriginalSessionServerCodec.LoginRequestType)
        {
            return Task.FromResult(ProcessSessionServerLogin(decoded, type));
        }

        var lobby = OriginalLobbyCodec.DecodeLogin(decoded.Payload!);
        if (!lobby.Success || !TryNormalizeLogin(lobby.Message!.Value.AccountElements, out var normalized))
        {
            return Task.FromResult(Invalid(lobby.ErrorCode ?? "original.lobby.login.account"));
        }

        _clientSequenceBaseline = decoded.Sequence;
        if (!_handoffs.TryConsume(_pendingHandoffToken, normalized, out _accountId))
        {
            _receipt.Record("handoff", "rejected");
            State = NaturalAuthoritySessionState.Rejected;
            return Task.FromResult(Invalid("original.handoff.rejected"));
        }

        _pendingHandoffToken = 0;
        _lobbyCode = lobby.Message.Value.Code.ToArray();
        _normalizedLogin = normalized;
        _receipt.Record("handoff", "accepted", _accountId);
        State = NaturalAuthoritySessionState.LobbyReady;
        return Task.FromResult(EncodeApplicationResponse(
            OriginalLobbyCodec.EncodeLoginOk(_lobbyCode), type, includeLobbyPrefix: true));
    }

    private async Task<NaturalAuthoritySessionResult> ProcessLobbyFollowupAsync(
        ReadOnlyMemory<byte> payload,
        CancellationToken cancellationToken)
    {
        var decoded = DecodeApplication(payload.Span);
        if (decoded.Status != OriginalClientInnerFrameStatus.Success)
        {
            return Invalid(decoded.ErrorCode!);
        }

        _clientSequenceBaseline = decoded.Sequence;
        var type = ReadType(decoded.Payload!);
        if (type == OriginalLobbyCodec.CharacterListRequestType)
        {
            var characters = await _store.ListCharactersAsync(_accountId, cancellationToken);
            _receipt.Record("character-list", $"count-{characters.Count}", _accountId);
            return EncodeApplicationResponse(
                OriginalLobbyCodec.EncodeCharacters(
                    _lobbyCode!,
                    characters.Select(character => new OriginalLobbyCharacterRecord(
                        character.CharacterId,
                        character.Faction,
                        character.Blood,
                        character.Sex,
                        character.LastName,
                        character.FirstName,
                        character.FlagshipName,
                        character.Face,
                        character.AbilityValues)).ToArray()),
                type,
                includeLobbyPrefix: true);
        }

        if (type == OriginalLobbyCodec.SessionListRequestType)
        {
            var characterCount = await _store.CountCharactersAsync(
                _accountId,
                cancellationToken);
            var pendingLottery = await _store.FindPendingOriginalCharacterLotteryAsync(
                _accountId,
                cancellationToken);
            var lotteryUnavailable = characterCount >= OriginalLobbyCodec.MaximumCharacters ||
                pendingLottery is not null;
            _receipt.Record(
                "session-list",
                lotteryUnavailable
                    ? "count-2-authored-aliases-lottery-unavailable"
                    : "count-2-authored-aliases-lottery-available",
                _accountId);
            var response = EncodeApplicationResponse(
                OriginalLobbyCodec.EncodeSessions(_lobbyCode!, [
                    // NEW DESIGN: both selectors currently route to the same
                    // authoritative world. They exist to exercise the original
                    // client's session-change availability and return path.
                    new OriginalLobbySessionRecord(1, 1, "LOGH7-1", "0", 0),
                    new OriginalLobbySessionRecord(2, 1, "LOGH7-2", "0", 0)
                ], lotteryAvailable: !lotteryUnavailable, serverNotice: _serverNotice),
                type,
                includeLobbyPrefix: true);
            return response;
        }

        if (type == OriginalLobbyCodec.SessionSelectionRequestType)
        {
            var selection = OriginalLobbyCodec.DecodeSessionSelection(decoded.Payload!);
            if (!selection.Success)
            {
                return Invalid(selection.ErrorCode!);
            }

            var characters = await _store.ListCharactersAsync(
                _accountId,
                cancellationToken);
            var selectsOwnedCharacter = characters.Any(
                character => character.CharacterId == selection.SessionId);
            if (selection.SessionId is not (1 or 2) && !selectsOwnedCharacter)
            {
                return Invalid("original.lobby.session-selection.unavailable");
            }

            var handoffToken = _handoffs.Issue(
                _accountId, _normalizedLogin!, selection.SessionId);
            _receipt.Record(
                "session-selection",
                $"accepted-authored-alias-{selection.SessionId}",
                _accountId);
            State = NaturalAuthoritySessionState.LobbyRedirectSent;
            return EncodeApplicationResponse(
                OriginalLobbyCodec.EncodeSessionLoginOk(
                    _sessionServerIpv4, _sessionServerPort, handoffToken),
                type,
                includeLobbyPrefix: true);
        }

        return Invalid("original.lobby.unexpected-application-type");
    }

    private async Task<NaturalAuthoritySessionResult> ProcessSessionServerFollowupAsync(
        ReadOnlyMemory<byte> payload,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var decoded = DecodeApplication(payload.Span);
        if (decoded.Status != OriginalClientInnerFrameStatus.Success)
        {
            return Invalid(decoded.ErrorCode!);
        }

        _clientSequenceBaseline = decoded.Sequence;
        var type = ReadType(decoded.Payload!);
        if (type == OriginalMoveBaseCodec.RequestType)
            return await ProcessBaseTravelAsync(decoded.Payload!,decoded.Sequence,cancellationToken);
        if (type == OriginalSwitchModeCodec.RequestType)
            return await ProcessOwnDepartureAsync(decoded.Payload!,decoded.Sequence,cancellationToken);
        if (type == OriginalMoveGridCodec.RequestType)
        {
            return await ProcessMoveGridAsync(
                decoded.Payload!, type.Value, decoded.Sequence, cancellationToken);
        }

        if (type == OriginalCompletenessRepairCodec.CommandType)
        {
            return await ProcessCompletenessRepairAsync(
                decoded.Payload!, type.Value, decoded.Sequence, cancellationToken);
        }

        if (_tacticalEncounter.IsCompleted && type is
            OriginalTacticalCommandCodec.MoveShipCommandType or
            OriginalTacticalCommandCodec.ParallelMoveShipCommandType or
            OriginalTacticalCommandCodec.FileFleetCommandType or
            OriginalTacticalCommandCodec.TurnShipCommandType or
            OriginalTacticalCommandCodec.ReverseShipCommandType or
            OriginalTacticalCommandCodec.WarpCommandType or
            OriginalTacticalCommandCodec.AttackShipCommandType or
            OriginalTacticalCommandCodec.ShootShipCommandType or
            OriginalTacticalCommandCodec.StopCommandType or
            // 鼓舞 0x0409 is deliberately NOT in this list. Morale is a lasting
            // property of the unit, not a manoeuvre, so encouraging a fleet whose
            // last battle in this grid is over is a normal act - the same reason
            // 完全修復 is not gated on a live encounter either.
            OriginalTacticalControlCodec.CommandType)
            return RejectCommandVisibly("TACTICAL_ENCOUNTER_FINISHED", type.Value, "戦術戦闘は終了しています");

        if (type is OriginalTacticalCommandCodec.MoveShipCommandType or
            OriginalTacticalCommandCodec.ParallelMoveShipCommandType)
        {
            return await ProcessTacticalMoveShipAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type is OriginalTacticalCommandCodec.ChangeModeCommandType or
            OriginalTacticalCommandCodec.SortieCommandType)
        {
            return await ProcessTacticalModeChangeAsync(
                decoded.Payload!, type.Value, decoded.Sequence, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.AirBattleCommandType)
        {
            return await ProcessAirBattleAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type is OriginalTacticalCommandCodec.AdmissionCommandType or
            OriginalTacticalCommandCodec.AdmissionBaseCommandType)
        {
            return await ProcessAdmissionAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.MoveFortressCommandType)
        {
            return await ProcessMoveFortressAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.FightCommandType)
        {
            return await ProcessFightAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.ShootFortressCommandType)
        {
            return await ProcessShootFortressAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.StopBaseCommandType)
        {
            return await ProcessStopBaseAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.MoveTroopCommandType)
        {
            return await ProcessMoveTroopAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type is OriginalTacticalCommandCodec.RepairBaseCommandType or
            OriginalTacticalCommandCodec.SupplyBaseCommandType)
        {
            return await ProcessBaseSupportAsync(
                decoded.Payload!, type.Value, decoded.Sequence, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.StopTroopCommandType)
        {
            return await ProcessStopTroopAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.AttackTroopCommandType)
        {
            return await ProcessTroopAssaultAsync(
                decoded.Payload!, type.Value, decoded.Sequence, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.EncourageBaseCommandType)
        {
            return await ProcessEncourageBaseAsync(
                decoded.Payload!, type.Value, decoded.Sequence, cancellationToken);
        }

        if (type is OriginalTacticalCommandCodec.SortieTroopsCommandType or
            OriginalTacticalCommandCodec.EvacuateTroopsCommandType)
        {
            return await ProcessTroopLandingAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.EmergencySupplyCommandType)
        {
            return await ProcessEmergencySupplyAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.ChangeAuthorityCommandType)
        {
            return await ProcessChangeAuthorityAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type is OriginalTacticalCommandCodec.RepairFleetCommandType or
            OriginalTacticalCommandCodec.SupplyFleetCommandType)
        {
            return await ProcessTacticalSupportAsync(
                decoded.Payload!, type.Value, decoded.Sequence, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.FileFleetCommandType)
        {
            return await ProcessTacticalFileFleetAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.StopFleetCommandType)
        {
            return await ProcessTacticalStopFleetAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.ReverseShipCommandType)
        {
            return await ProcessTacticalReverseShipAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.TurnShipCommandType)
        {
            return await ProcessTacticalTurnShipAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.WarpCommandType)
        {
            return await ProcessTacticalWarpAsync(
                decoded.Payload!, type.Value, decoded.Sequence, cancellationToken);
        }

        if (type is OriginalTacticalCommandCodec.AttackShipCommandType or
            OriginalTacticalCommandCodec.ShootShipCommandType or
            OriginalTacticalCommandCodec.StopCommandType)
        {
            return await ProcessTacticalRelayAsync(
                decoded.Payload!, type.Value, cancellationToken);
        }

        if (type == OriginalSuggestionCodec.CommandType)
        {
            return await ProcessSuggestionAsync(decoded.Payload!, type.Value, cancellationToken);
        }

        if (type == OriginalTacticalCommandCodec.MissionCommandType)
        {
            return await ProcessMissionAsync(decoded.Payload!, type.Value, cancellationToken);
        }

        if (type == OriginalEncourageFlagshipCodec.CommandType)
        {
            return await ProcessEncourageFlagshipAsync(
                decoded.Payload!, type.Value, decoded.Sequence, cancellationToken);
        }

        if (type == OriginalTacticalControlCodec.CommandType)
        {
            return await ProcessTacticalControlAsync(decoded.Payload!, cancellationToken);
        }

        if (type == OriginalCharacterDeleteCodec.RequestType)
        {
            var parsed = OriginalCharacterDeleteCodec.DecodeRequest(decoded.Payload!);
            if (!parsed.Success)
            {
                return Invalid(parsed.ErrorCode!, type, Convert.ToHexString(decoded.Payload!));
            }

            if (_lobbySelectionValue is null ||
                parsed.SessionId != _lobbySelectionValue.Value)
            {
                return Invalid(
                    "original.character-delete.selection-mismatch",
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            var characters = await _store.ListCharactersAsync(_accountId, cancellationToken);
            var selected = ResolveSelectedCharacter(characters, _lobbySelectionValue.Value);
            if (selected is null)
            {
                return Invalid("original.character-delete.selection-unavailable", type);
            }

            var requestFingerprint = Convert.ToHexStringLower(SHA256.HashData(
                System.Text.Encoding.UTF8.GetBytes(FormattableString.Invariant(
                    $"original-character-delete/v1\n{_accountId:D}\n{selected.CharacterId}\n{parsed.SessionId}"))));
            CharacterDeleteStoreResult stored;
            try
            {
                stored = await _store.DeleteCharacterAsync(
                    _accountId,
                    new CharacterDeleteWrite(
                        requestFingerprint,
                        selected.CharacterId,
                        parsed.SessionId),
                    cancellationToken);
            }
            catch (InvalidOperationException exception)
                when (exception.Message is "CHARACTER_NOT_FOUND" or
                    "CHARACTER_DELETE_REPLAY_MISMATCH")
            {
                return Invalid("original.character-delete.authority-conflict", type);
            }

            _createdCharacter = null;
            _worldPcp = 0;
            _worldMcp = 0;
            _worldCharacterId = 0;
            _worldGridUnitId = 0;
            _receipt.Record(
                "character-delete",
                stored.Deleted ? "deleted" : "idempotent-replay",
                _accountId);
            return Success(null, null, type, null);
        }

        if (type == OriginalSessionServerCodec.CharacterContextRequestType &&
            decoded.Payload!.Length == sizeof(ushort))
        {
            var restoreError = await RestorePersistedCharacterAsync(cancellationToken);
            if (restoreError is not null)
            {
                return Invalid(restoreError, type);
            }

            if (_createdCharacter is not null)
            {
                return EncodeApplicationResponse(
                    OriginalSessionServerCodec.EncodeCharacterContext(_worldCharacterId),
                    type,
                    includeLobbyPrefix: true);
            }

            _receipt.Record("character-context", "empty-account", _accountId);
            return EncodeApplicationResponse(
                OriginalSessionServerCodec.EncodeCharacterContext(0),
                type,
                includeLobbyPrefix: true);
        }

        if (type == OriginalSessionServerCodec.GameLoginRequestType &&
            decoded.Payload!.Length == sizeof(ushort))
        {
            var restoreError = await RestorePersistedCharacterAsync(cancellationToken);
            if (restoreError is not null)
            {
                return Invalid(restoreError, type);
            }

            var gridRestoreError = await RestorePersistedGridUnitAsync(cancellationToken);
            if (gridRestoreError is not null)
            {
                return Invalid(gridRestoreError, type);
            }

            _worldEntered = true;
            _receipt.Record("world-entry", "game-login-ok", _accountId);
            var accepted = EncodeApplicationResponse(
                OriginalSessionServerCodec.EncodeGameLoginOk(),
                type,
                includeLobbyPrefix: true);
            if (_createdCharacter is not OriginalCreateCharacterCommand character)
            {
                return accepted;
            }

            return accepted with
            {
                AdditionalResponses =
                [
                    EncodeApplicationPush(
                        OriginalWorldEntryCodec.EncodeCharacterContext(_worldCharacterId)),
                    EncodeApplicationPush(
                        EncodeTacticalBattlefieldUnits()),
                    EncodeApplicationPush(
                        OriginalSystemSceneCodec.EncodeTacticalUnitShips(
                            CurrentTacticalBattlefield())),
                    .. EncodeBattlefieldCharacters(character).Select(frame => EncodeApplicationPush(frame)),
                    .. (OriginalSimpleRankCodec.InfoProbeEnabled
                        ? OriginalSimpleRankCodec.EncodeInfoProbeFrames().Select(frame => EncodeApplicationPush(frame)).ToArray()
                        : Array.Empty<NaturalAuthorityPush>())
                ],
                ResponseMetadata = OriginalSimpleRankCodec.InfoProbeEnabled ? "info-probe:0x0218(340)+0x021B(8900) pushed after game-login" : null
            };
        }

        if (type == 0x0f02 &&
            decoded.Payload!.Length == sizeof(ushort) &&
            _createdCharacter is OriginalCreateCharacterCommand gridCharacter)
        {
            var recoveryError = await PrepareInjuryReturnAsync(cancellationToken);
            if (recoveryError is not null) return Invalid(recoveryError, type);
            recoveryError = await PrepareFlagshipRecoveryAsync(cancellationToken);
            if (recoveryError is not null) return Invalid(recoveryError,type);
            gridCharacter = _createdCharacter!.Value;
            var unitTemplateError = ResolveSceneUnitComplements(out _);
            if (unitTemplateError is not null) return Invalid(unitTemplateError,type);
            var transitionStep = _tacticalTransitionGate.OnWorldInitializeRequest();
            if (transitionStep == OriginalTacticalTransitionStep.WorldRefresh)
            {
                using var refreshLease = await _battles.LockAsync(_worldGridCellId,
                    OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
                if (!HasCurrentShipIncarnation) return Invalid("original.flagship.scene-refresh-required",type);
                var sceneParticipants = await ResetBattleObservationForSceneImportAsync(cancellationToken);
                // LIVE ORIGINAL CLIENT (2026-09-04T15:07:30Z): a later 0x0F02
                // starts a fresh scene build. Returning 0x0F03 alone clears the
                // previous scene but leaves no InformationUnit from which to
                // select PLAYER_INFO, then the client faults in FUN_0058EE70.
                // Re-project the joined unit/character/tactics records before
                // the status terminator. NotifyTactics is served once by the
                // initial bootstrap; repeating it creates another modal.
                var refreshFrames = new List<byte[]>
                {
                    EncodeCurrentGridState(checked((ushort)_worldGridCellId)),
                    EncodeTacticalBattlefieldUnits(participants: sceneParticipants),
                };
                AddFleetInformation(refreshFrames, sceneParticipants);
                refreshFrames.AddRange(EncodeBattlefieldCharacters(gridCharacter, sceneParticipants));
                refreshFrames.Add(OriginalSystemSceneCodec.EncodeTacticalUnitShips(CurrentTacticalBattlefield(participants: sceneParticipants)));
                refreshFrames.AddRange(EncodeTacticalSceneBootstrapFrames(sceneParticipants));
                refreshFrames.Add(OriginalWorldBootstrapCodec.EncodeStatus(0x0f03));
                var worldRefresh = EncodeApplicationResponse(
                    refreshFrames[0],
                    type,
                    includeLobbyPrefix: true);
                MarkSceneParticipants(sceneParticipants);
                return worldRefresh with
                {
                    AdditionalResponses = refreshFrames.Skip(1)
                        .Select(frame => EncodeApplicationPush(frame))
                        .ToArray(),
                    ResponseMetadata = $"world-bootstrap:player-context-refresh;encounter-completed={_tacticalEncounter.IsCompleted};enemy-present={_tacticalEncounter.EnemyHasSurvivors}"
                };
            }

            var ownedCharacters = await _store.ListCharactersAsync(_accountId, cancellationToken);
            if (ownedCharacters.Count == 0 ||
                ownedCharacters.Any(character => character.CharacterId is <= 0 or > uint.MaxValue))
            {
                return Invalid("original.world-bootstrap.owned-character-roster", type);
            }

            var ownedRosterFrames = OriginalSimpleCharacterRosterCodec.EncodeTransaction(
                ownedCharacters.Select(character => new OriginalSimpleCharacterRosterEntry(
                    checked((uint)character.CharacterId),
                    character.LastName,
                    2)).ToArray());
            using var bootstrapLease = await _battles.LockAsync(_worldGridCellId,
                OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
            if (!HasCurrentShipIncarnation) return Invalid("original.flagship.scene-refresh-required",type);
            var bootstrapParticipants = await ResetBattleObservationForSceneImportAsync(cancellationToken);
            var gridFrames = new List<byte[]>
            {
                OriginalWorldEntryCodec.EncodeCharacterContext(_worldCharacterId),
                OriginalWorldEntryCodec.EncodeGridEnterBoundary(0x0b09),
                EncodeCurrentGridState(checked((ushort)_worldGridCellId)),
                EncodeTacticalBattlefieldUnits(participants: bootstrapParticipants),
            };
            AddFleetInformation(gridFrames, bootstrapParticipants);
            gridFrames.AddRange(EncodeBattlefieldCharacters(gridCharacter, bootstrapParticipants));
            gridFrames.Add(OriginalSystemSceneCodec.EncodeTacticalUnitShips(CurrentTacticalBattlefield(participants: bootstrapParticipants)));
            gridFrames.AddRange(EncodeTacticalSceneBootstrapFrames(bootstrapParticipants));
            if (IsCurrentTacticalFieldActive)
                gridFrames.Add(OriginalTacticalCommandCodec.EncodeNotifyTactics(1, _worldGridCellId));
            gridFrames.Add(OriginalWorldEntryCodec.EncodeGridEnterBoundary(0x0b0a));
            gridFrames.Add(OriginalWorldBootstrapCodec.EncodeStaticGridTypes());
            gridFrames.Add(OriginalWorldBootstrapCodec.EncodeStaticGrid());
            gridFrames.Add(OriginalWorldBootstrapCodec.EncodeStatus(0x0f03));
            gridFrames.AddRange(ownedRosterFrames);
            _receipt.Record("world-bootstrap", "grid-initialize-spawn", _accountId);
            var gridInitialize = EncodeApplicationResponse(
                gridFrames[0],
                type,
                includeLobbyPrefix: true);
            MarkSceneParticipants(bootstrapParticipants);
            return gridInitialize with
            {
                AdditionalResponses = gridFrames.Skip(1)
                    .Select(frame => EncodeApplicationPush(frame))
                    .ToArray()
            };
        }

        if (type == OriginalSimpleCharacterRosterCodec.RequestType)
        {
            if (decoded.Payload!.Length != OriginalSimpleCharacterRosterCodec.RequestMessageSize)
            {
                return Invalid(
                    "original.simple-character-roster.request-length",
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            var simpleInformationSelector = BinaryPrimitives.ReadUInt16BigEndian(
                decoded.Payload.AsSpan(
                    sizeof(ushort) + OriginalLoginCodec.MessageCodeSize));
            if (_worldEntered &&
                (simpleInformationSelector == OriginalSimpleRankCodec.PromotionRankSelector ||
                 OriginalSimpleRankCodec.ListKindProbe(simpleInformationSelector) == 0x1209))
            {
                var restoreRankError = await RestorePersistedCharacterAsync(cancellationToken);
                if (restoreRankError is not null)
                {
                    return Invalid(restoreRankError, type);
                }

                if (_createdCharacter is null)
                {
                    return Invalid("original.simple-rank.empty-account", type);
                }

                var rankFrames = OriginalSimpleRankCodec.EncodePromotionTransaction(
                    _createdCharacter.Value.Rank,
                    decoded.Payload.AsSpan(sizeof(ushort)));
                _receipt.Record(
                    "simple-rank",
                    "promotion-rank-ladder-served",
                    _accountId);
                var rankResponsesBeforePrimary = rankFrames.Take(rankFrames.Count - 1)
                    .Select(frame => EncodeApplicationPush(frame))
                    .ToArray();
                var rankEnd = EncodeApplicationResponse(
                    rankFrames[^1],
                    type,
                    includeLobbyPrefix: true);
                return rankEnd with
                {
                    ResponsesBeforePrimary = rankResponsesBeforePrimary,
                    ResponseMetadata = $"promotion-rank-ladder-served;selector=0x{simpleInformationSelector:X4}"
                };
            }

            if (_worldEntered &&
                (simpleInformationSelector == OriginalSimpleRankCodec.NinmeiCharacterSelector ||
                 OriginalSimpleRankCodec.ListKindProbe(simpleInformationSelector) == 0x1202))
            {
                // default: every account-owned character is an appointable person; LOGH7_NINMEI_CHARS narrows (probe).
                var wanted = OriginalSimpleRankCodec.NinmeiCharacterProbeSelectsIds ? OriginalSimpleRankCodec.NinmeiCharacterIds() : Array.Empty<uint>();
                var ownedForNinmei = await _store.ListCharactersAsync(_accountId, cancellationToken);
                var ninmeiCharacters = ownedForNinmei
                    .Where(character => character.CharacterId > 0 && (wanted.Length == 0 || wanted.Contains(checked((uint)character.CharacterId))))
                    .Select(character => (checked((uint)character.CharacterId), character.LastName, $"{character.FirstName}・{character.LastName}", checked((ushort)character.Rank)))
                    .ToArray();
                var charFrames = OriginalSimpleRankCodec.EncodeNinmeiCharacterTransaction(
                    decoded.Payload.AsSpan(sizeof(ushort)),
                    ninmeiCharacters);
                _receipt.Record("simple-information", "ninmei-character-list-served;selector=0x0004", _accountId);
                var charBefore = charFrames.Take(charFrames.Count - 1)
                    .Select(frame => EncodeApplicationPush(frame))
                    .ToArray();
                var charEnd = EncodeApplicationResponse(
                    charFrames[^1],
                    type,
                    includeLobbyPrefix: true);
                return charEnd with
                {
                    ResponsesBeforePrimary = charBefore,
                    ResponseMetadata = $"ninmei-character-list-served;selector=0x{simpleInformationSelector:X4};notify=0x1202;characters={string.Join('+', ninmeiCharacters.Select(c => c.Item1))}"
                };
            }

            if (_worldEntered &&
                (simpleInformationSelector == OriginalSimpleRankCodec.NinmeiSelector ||
                 OriginalSimpleRankCodec.ListKindProbe(simpleInformationSelector) == 0x1208) &&
                !OriginalSimpleRankCodec.NinmeiProbeEnabled)
            {
                // default 任命 post list: subordinate posts of the player's card (static appointer hierarchy); holder ids
                // come from original_character_card when present (NEW_DESIGN persistence), 0 = vacant.
                var hierarchy = OriginalWorldBootstrapCodec.StaticCardAppointerOverrides();
                var currentCards = await _store.ListCharacterCardsAsync(_accountId, cancellationToken);
                var actorCard = currentCards.FirstOrDefault(card=>card.CharacterId==_worldCharacterId)?.CardId
                    ?? OriginalAuthoredPlayableCatalog.AuthorityCardId;
                var posts = hierarchy
                    .Where(pair => actorCard != 0 && pair.Value == actorCard)
                    .OrderBy(pair => pair.Key)
                    .Select(pair => (pair.Key, currentCards.Where(card => card.CardId == pair.Key).Select(card => checked((uint)card.CharacterId)).FirstOrDefault()))
                    .ToArray();
                var postFrames = OriginalSimpleRankCodec.EncodeNinmeiPostTransaction(
                    decoded.Payload.AsSpan(sizeof(ushort)),
                    posts);
                _receipt.Record("simple-information", $"ninmei-post-list-served;selector=0x0012;posts={posts.Length}", _accountId);
                var postBefore = postFrames.Take(postFrames.Count - 1)
                    .Select(frame => EncodeApplicationPush(frame))
                    .ToArray();
                var postEnd = EncodeApplicationResponse(
                    postFrames[^1],
                    type,
                    includeLobbyPrefix: true);
                return postEnd with
                {
                    ResponsesBeforePrimary = postBefore,
                    ResponseMetadata = $"ninmei-post-list-served;selector=0x{simpleInformationSelector:X4};notify=0x1208;posts={string.Join('+', posts.Select(p => $"{p.Item1}:{p.Item2}"))}"
                };
            }

            // ORIGINAL_STATIC + LIVE: selector 0x001E is the unit-command
            // prefetch and consumes 0x1207. Serve the authoritative world unit
            // by default; environment mappings remain discovery-only fallbacks
            // for selectors whose list kind is still unknown.
            var knownListKind = _worldEntered
                ? OriginalSimpleRankCodec.KnownListKind(simpleInformationSelector)
                : (ushort)0;

            // PROBE (2026-09-03, LOGH7_LIST_KIND_PROBE=<sel>:1204|1205|1206): serve one authored record of the mapped kind so a
            // picker whose kind is unknown (作戦計画 selector 0x0021, 発令 0x000B) can be identified live. Grid = the current
            // grid unit cell, Base = base 1 of the served system scene, Strategy = ids 1..2 (作戦 catalog unknown). Read-only.
            var probeKind = knownListKind != 0
                ? knownListKind
                : (_worldEntered ? OriginalSimpleRankCodec.ListKindProbe(simpleInformationSelector) : (ushort)0);
            if (probeKind is 0x1204 or 0x1205 or 0x1206 or 0x1207)
            {
                var probeFrames = probeKind switch
                {
                    0x1207 => OriginalSimpleRankCodec.EncodeUnitListTransaction(decoded.Payload.AsSpan(sizeof(ushort)), new (uint, byte, ushort)[] { (_worldGridUnitId, 0, 0) }),
                    0x1205 => OriginalSimpleRankCodec.EncodeGridListTransaction(decoded.Payload.AsSpan(sizeof(ushort)), new uint[] { 101, 102 }),
                    0x1204 => OriginalSimpleRankCodec.EncodeBaseListTransaction(decoded.Payload.AsSpan(sizeof(ushort)), new (uint, ushort, ushort)[] { (1, 0, 0), (2, 0, 0) }),
                    _ => OriginalSimpleRankCodec.EncodeStrategyListTransaction(decoded.Payload.AsSpan(sizeof(ushort)), new (uint, ushort, byte, byte)[] { (1, 0, 0, 0), (2, 0, 0, 0) }),
                };
                var listDetail = knownListKind == 0x1207
                    ? $"unit-command-list-served;selector=0x{simpleInformationSelector:X4};kind=0x1207;unit={_worldGridUnitId}"
                    : $"list-kind-probe-served;selector=0x{simpleInformationSelector:X4};kind=0x{probeKind:X4}";
                _receipt.Record("simple-information", listDetail, _accountId);
                var probeBefore = probeFrames.Take(probeFrames.Count - 1).Select(frame => EncodeApplicationPush(frame)).ToArray();
                var probeEnd = EncodeApplicationResponse(probeFrames[^1], type, includeLobbyPrefix: true);
                return probeEnd with
                {
                    ResponsesBeforePrimary = probeBefore,
                    ResponseMetadata = knownListKind == 0x1207
                        ? $"unit-command-list-served;selector=0x{simpleInformationSelector:X4};notify=0x1207;unit={_worldGridUnitId};records=1"
                        : $"list-kind-probe-served;selector=0x{simpleInformationSelector:X4};kind=0x{probeKind:X4};records=2"
                };
            }

            if (_worldEntered &&
                simpleInformationSelector == OriginalSimpleRankCodec.NinmeiSelector &&
                OriginalSimpleRankCodec.NinmeiProbeEnabled)
            {
                var ninmeiFrames = OriginalSimpleRankCodec.EncodeNinmeiProbeTransaction(
                    decoded.Payload.AsSpan(sizeof(ushort)));
                _receipt.Record("simple-information", "ninmei-probe-list-served;selector=0x0012", _accountId);
                var ninmeiBefore = ninmeiFrames.Take(ninmeiFrames.Count - 1)
                    .Select(frame => EncodeApplicationPush(frame))
                    .ToArray();
                var ninmeiEnd = EncodeApplicationResponse(
                    ninmeiFrames[^1],
                    type,
                    includeLobbyPrefix: true);
                return ninmeiEnd with
                {
                    ResponsesBeforePrimary = ninmeiBefore,
                    ResponseMetadata = OriginalSimpleRankCodec.NinmeiProbeCardIds ? $"ninmei-probe-card-list-served;selector=0x0012;notify=0x1208;cards={string.Join('+', OriginalSimpleRankCodec.NinmeiCardIds())}" : OriginalSimpleRankCodec.NinmeiProbeTokenCardAlone ? "ninmei-probe-card-list-served;selector=0x0012;notify=0x1208;records=1(token pattern, mode-2 frame alone)" : OriginalSimpleRankCodec.NinmeiProbeCardList ? (OriginalSimpleRankCodec.NinmeiProbePrefixed ? "ninmei-probe-card-list-served;selector=0x0012;notify=0x1205,0x1206,0x1207,0x1208;records=3(cardId 1..3)" : (OriginalSimpleRankCodec.NinmeiProbeNonZero ? "ninmei-probe-card-list-served;selector=0x0012;notify=0x1208;records=3(cardId 1..3, other fields 0x01)" : "ninmei-probe-card-list-served;selector=0x0012;notify=0x1208;records=3(cardId 1..3)")) : OriginalSimpleRankCodec.NinmeiProbeAllTypes ? (OriginalSimpleRankCodec.NinmeiProbeReverse ? "ninmei-probe-all-types-served-reverse;selector=0x0012;notify=0x120F..0x1202;records=1-each" : "ninmei-probe-all-types-served;selector=0x0012;notify=0x1202..0x120F;records=1-each") : "ninmei-probe-list-served;selector=0x0012;notify=0x120A;records=1"
                };
            }

            var restoreError = await RestorePersistedCharacterAsync(cancellationToken);
            if (restoreError is not null)
            {
                return Invalid(restoreError, type);
            }

            if (_createdCharacter is null)
            {
                return Invalid("original.simple-character-roster.empty-account", type);
            }

            IReadOnlyList<OriginalSimpleCharacterRosterEntry> rosterEntries;
            string receiptDetail;
            if (!_worldEntered)
            {
                rosterEntries = OriginalLotteryCandidateCatalog.Entries;
                receiptDetail = $"request-served-with-authored-lottery-catalog;selector=0x{simpleInformationSelector:X4}";
            }
            else
            {
                var characters = await _store.ListCharactersAsync(_accountId, cancellationToken);
                if (characters.Count == 0)
                {
                    return Invalid("original.simple-character-roster.empty-account", type);
                }

                if (characters.Any(character => character.CharacterId is <= 0 or > uint.MaxValue))
                {
                    return Invalid("original.simple-character-roster.character-id-range", type);
                }

                rosterEntries = characters
                    .Select(character => new OriginalSimpleCharacterRosterEntry(
                        checked((uint)character.CharacterId),
                        character.LastName,
                        2))
                    .ToArray();
                receiptDetail = $"request-served-with-account-owned-characters;selector=0x{simpleInformationSelector:X4}";
            }

            var rosterFrames = OriginalSimpleCharacterRosterCodec.EncodeTransaction(
                rosterEntries,
                decoded.Payload!.AsSpan(sizeof(ushort)));
            _receipt.Record(
                "simple-character-roster",
                receiptDetail,
                _accountId);
            var beforePrimary = rosterFrames.Take(rosterFrames.Count - 1)
                .Select(frame => EncodeApplicationPush(frame))
                .ToArray();
            var end = EncodeApplicationResponse(
                rosterFrames[^1],
                type,
                includeLobbyPrefix: true);
            return end with
            {
                ResponsesBeforePrimary = beforePrimary,
                ResponseMetadata = receiptDetail
            };
        }

        if (type == OriginalCharacterEntryStateCodec.SelectionRequestType)
        {
            if (!OriginalCharacterEntryStateCodec.TryDecodeSelection(
                    decoded.Payload!,
                    out var selectedCharacterId))
            {
                return Invalid(
                    "original.character-entry-state.request-shape",
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            var restoreError = await RestorePersistedCharacterAsync(cancellationToken);
            if (restoreError is not null)
            {
                return Invalid(restoreError, type);
            }

            var isPlayableCharacter = selectedCharacterId == _worldCharacterId;
            var isAuthoredLotteryCandidate = OriginalLotteryCandidateCatalog.Entries.Any(
                candidate => candidate.CharacterId == selectedCharacterId);
            if (_createdCharacter is null ||
                (!isPlayableCharacter && !isAuthoredLotteryCandidate))
            {
                return Invalid(
                    "original.character-entry-state.character-mismatch",
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            _receipt.Record(
                "original-character-entry-state",
                "authored-placeholder-candidates",
                _accountId);
            return EncodeApplicationResponse(
                OriginalCharacterEntryStateCodec.EncodeState(
                    selectedCharacterId,
                    OriginalLotteryCandidateCatalog.Entries
                        .Select(candidate => candidate.CharacterId)
                        .ToArray()),
                type,
                includeLobbyPrefix: true);
        }

        if (type == 0x0f1a)
        {
            if (!_worldEntered) return Invalid("original.return-base.world-not-entered", type);
            var request = decoded.Payload!;
            // E083: 00480CF0 / 00480DD0 and 004BD51F: time, id, base, all u32.
            if (request.Length != 14) return Invalid("original.return-base.payload", type);
            var restoreError = await RestorePersistedCharacterAsync(cancellationToken);
            if (restoreError is not null) return Invalid(restoreError, type);
            var characterId = BinaryPrimitives.ReadUInt32BigEndian(request.AsSpan(6));
            var baseId = BinaryPrimitives.ReadUInt32BigEndian(request.AsSpan(10));
            if (_createdCharacter is not OriginalCreateCharacterCommand character ||
                characterId == 0 || characterId != _worldCharacterId)
                return Invalid("original.return-base.character", type);
            if (baseId != 0)
            {
                var grid = ActiveBattlefieldCatalog.StaticBases?
                    .Where(value => value.Id == baseId).Select(value => (uint?)value.Grid).FirstOrDefault();
                if (ActiveBattlefieldCatalog.StaticBases is null &&
                    baseId == OriginalAuthoredPlayableCatalog.BaseId)
                    grid = OriginalAuthoredPlayableCatalog.CurrentGridCell;
                // Authored permission boundary: known, same-faction base only.
                if (grid is null || ActiveBattlefieldCatalog.ProjectBaseObjectives(grid.Value)?
                    .Any(value => value.Id == baseId && value.Power == character.Power && value.Camp == 0) != true)
                    return Invalid("original.return-base.base", type);
            }
            // The original time field is not a proven unique request id.
            // A fresh inner sequence (or connection) can intentionally repeat A after B.
            var fingerprint = Convert.ToHexStringLower(SHA256.HashData(
                System.Text.Encoding.UTF8.GetBytes(FormattableString.Invariant(
                    $"original-return-base/v1|{_returnBaseRequestScope:N}|{decoded.Sequence}|{Convert.ToHexString(request)}"))));
            OriginalReturnBaseStoreResult saved;
            try
            {
                saved = await _store.SetOriginalReturnBaseAsync(_accountId,
                    new(fingerprint, characterId, baseId), cancellationToken);
            }
            catch (InvalidOperationException exception) when (exception.Message == "CHARACTER_NOT_FOUND")
            {
                return Invalid("original.return-base.character", type);
            }
            // Publish only committed state. A replay may follow a newer setting.
            _createdCharacter = character with { ReturnBaseId = saved.CurrentReturnBaseId };
            PublishOwnParticipantSnapshot();
            var response = new byte[4 + request.Length];
            request.CopyTo(response, 4);
            return EncodeApplicationResponse(response, type, includeLobbyPrefix: true) with
            {
                ResponseMetadata = $"return-base={saved.CurrentReturnBaseId};updated={saved.Updated};version={saved.AuthorityVersion}"
            };
        }

        // 0x0322 RequestInformationCharacter (2026-09-03, run 043049Z): the 任命 dialog requests the selected
        // character's details before executing; an unanswered request disconnects the client. Serve the same
        // 0x0323 ResponseInformationCharacter frame the world bootstrap pushes for the account's world character.
        if (type == 0x0322 && _worldEntered)
        {
            var infoPayload = decoded.Payload ?? Array.Empty<byte>();
            // decoded.Payload starts with the 2-byte application type (run 10f: 0322 00000002) => id at +2.
            var requestedId = infoPayload.Length >= 6
                ? System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(infoPayload.AsSpan(sizeof(ushort)))
                : 0u;
            var infoRestoreError = await RestorePersistedCharacterAsync(cancellationToken);
            if (infoRestoreError is not null)
            {
                return Invalid(infoRestoreError, type);
            }

            if (_createdCharacter is OriginalCreateCharacterCommand infoCharacter &&
                (requestedId == _worldCharacterId || requestedId == 0 ||
                 (requestedId == OriginalAuthoredPlayableCatalog.TacticalEnemyCharacterId && HasPrimaryTacticalNpc)))
            {
                var isEnemy = requestedId == OriginalAuthoredPlayableCatalog.TacticalEnemyCharacterId;
                if (!isEnemy)
                {
                    // Regeneration is owed by elapsed time, not by a command, so
                    // it is settled here, on the query whose answer the HUD
                    // draws. Without it a warp's 320 MCP would never come back
                    // and the player would run out of moves for good.
                    await SettleCommandPointRegenerationAsync(cancellationToken);
                    // Character identity may already be cached. Balances may not:
                    // read the committed owned row for each explicit self-info query.
                    var owned = (await _store.ListCharactersAsync(_accountId,cancellationToken))
                        .SingleOrDefault(row => row.CharacterId == _worldCharacterId);
                    if (owned is null) return Invalid("original.information-character.owner-missing",type);
                    _worldPcp = owned.Pcp;
                    _worldMcp = owned.Mcp;
                    // Other authority operations may change rank/merit while this
                    // connection remains open. The public reply and the participant
                    // published after processing must use this same committed row.
                    if (owned.Rank is <= 0 or > byte.MaxValue)
                        return Invalid("original.information-character.rank-out-of-range", type);
                    infoCharacter = infoCharacter with { Rank = (byte)owned.Rank, Achievement = owned.Achievement };
                    _createdCharacter = infoCharacter;
                }
                _receipt.Record("information-character", $"served;requested={requestedId}", _accountId);
                return EncodeApplicationResponse(
                    EncodeLocatedCharacter(
                        isEnemy ? OriginalAuthoredPlayableCatalog.TacticalEnemyCharacterId : _worldCharacterId,
                        isEnemy ? OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId : _worldGridUnitId,
                        isEnemy ? (ushort)0 : EffectiveWorldCardId,
                        isEnemy ? CreateTacticalEnemyCharacter(infoCharacter) : infoCharacter),
                    type,
                    includeLobbyPrefix: true) with
                {
                    ResponseMetadata = $"information-character-served;requested={requestedId};payloadHex={Convert.ToHexString(infoPayload)}"
                };
            }

            // Controlled subordinate units may share this character ID without
            // owning its character record. Continue to the actual record owner.
            var participant = OtherBattleParticipants().FirstOrDefault(p =>
                p.Ship.Character == requestedId && !p.CharacterFrame.IsEmpty);
            if (participant is not null && !participant.CharacterFrame.IsEmpty)
                return EncodeApplicationResponse(participant.CharacterFrame.ToArray(), type, includeLobbyPrefix: true);
            return Invalid("original.information-character.unknown-id", type, Convert.ToHexString(infoPayload));
        }

        if (type == OriginalOutfitPartyCodec.RequestType && _worldEntered)
            return await ProcessFleetPartyQueryAsync(decoded.Payload!, cancellationToken);

        // 0x0707 CommandCardAppointment (2026-09-03, run 044421Z payload
        // 0707 00000000 00000002 00000000 00000000 00000002 0028 0000 00000000 00000000 00000000 0000):
        // {u32 time, u32 characterId, u32 pcp, u32 mcp, u32 targetCharacterId, u16 cardId, ...} after the 2-byte type.
        // The client handler FUN_004BFCD0 ignores the response body (it clears the pending-command cells and
        // refreshes), so the accepted response echoes the command like 0x0704. Persistence: NEW_DESIGN table
        // original_card_appointment / original_character_card + domain event CharacterCardAppointed.
        if (type == 0x0707 && _worldEntered)
        {
            var appointPayload = decoded.Payload ?? Array.Empty<byte>();
            if (appointPayload.Length < 26)
            {
                return Invalid("original.card-appointment.payload", type, Convert.ToHexString(appointPayload));
            }

            // run 10h (045258Z): _worldCharacterId is only materialized by RestorePersistedCharacterAsync (as the
            // rank-up path does first); without it the identity check rejected appointer 2 with world id 0.
            var appointRestoreError = await RestorePersistedCharacterAsync(cancellationToken);
            if (appointRestoreError is not null)
            {
                return Invalid(appointRestoreError, type);
            }

            var appointerId = System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(appointPayload.AsSpan(6));
            var targetId = System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(appointPayload.AsSpan(18));
            // run 10i (045939Z): the card id is a u32 at +22 (00000028), not a u16 (the u16 read gave 0).
            var rawCardId = System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(appointPayload.AsSpan(22));
            if (rawCardId > ushort.MaxValue)
                return Invalid("original.card-appointment.card-range", type);
            var cardId = (ushort)rawCardId;
            if (appointerId != _worldCharacterId || targetId == 0 || cardId == 0)
            {
                return Invalid($"original.card-appointment.identity;appointer={appointerId};world={_worldCharacterId};target={targetId};card={cardId}", type, Convert.ToHexString(appointPayload));
            }

            var fingerprint = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
                System.Text.Encoding.UTF8.GetBytes($"original-card-appointment/v2\n{_accountId:D}\n{_moveGridRequestScope:N}\n{decoded.Sequence}\n{appointerId}\n{cardId}\n{targetId}\n{Convert.ToHexString(appointPayload)}"))).ToLowerInvariant();
            if (!OriginalWorldBootstrapCodec.StaticCardAppointerOverrides().TryGetValue(cardId,out var requiredAppointer)
                || requiredAppointer == 0)
                return RejectCommandVisibly("original.card-appointment.authority-not-configured",type.GetValueOrDefault(),
                    "この職務の任命権限は設定されていません");
            CardAppointmentStoreResult stored;
            try
            {
                stored = await _store.AppointCardAsync(
                    _accountId,
                    new CardAppointmentWrite(fingerprint, appointerId, cardId, targetId,
                        CommandPointPolicy.Value, _gameClock.Now, IsCurrentTacticalFieldActive,
                        requiredAppointer,OriginalAuthoredPlayableCatalog.AuthorityCardId),
                    cancellationToken);
            }
            catch (Exception exception) when (exception is InvalidOperationException or ArgumentException or Npgsql.PostgresException)
            {
                var reason = exception is Npgsql.PostgresException pg ? $"postgres-{pg.SqlState}" : exception.Message.ToLowerInvariant().Replace('_','-');
                return RejectCommandVisibly($"original.card-appointment.{reason}",type.GetValueOrDefault(),
                    "任命できません（権限、対象、コマンドポイントを確認してください）");
            }

            _receipt.Record("card-appointment", $"appointed;card={cardId};target={targetId};version={stored.AuthorityVersion};updated={stored.Updated}", _accountId);
            var appointmentActor = (await _store.ListCharactersAsync(_accountId,cancellationToken))
                .Single(row=>row.CharacterId==_worldCharacterId);
            _worldPcp=appointmentActor.Pcp; _worldMcp=appointmentActor.Mcp;
            var accepted = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + 160];
            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(accepted.AsSpan(4), 0x0707);
            appointPayload.AsSpan(2, Math.Min(appointPayload.Length - 2, 160)).CopyTo(accepted.AsSpan(6));
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(accepted.AsSpan(14),_worldPcp);
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(accepted.AsSpan(18),_worldMcp);
            return EncodeApplicationResponse(accepted, type, includeLobbyPrefix: true) with
            {
                ResponseMetadata = $"card-appointment-accepted;card={cardId};target={targetId};appointer={appointerId};authorityVersion={stored.AuthorityVersion};payloadHex={Convert.ToHexString(appointPayload)}"
            };
        }

        // 罷免 CommandCardDismissal (0x0708): the inverse of 任命 — the acting character removes a target character's
        // current appointment (captured live 2026-09-03 run 20260903T065309Z; same field offsets as 0x0707:
        // appointer u32@+6, target u32@+18, card u32@+22). NEW_DESIGN persistence: set original_character_card to0,
        // record original_card_dismissal_command, event CharacterCardDismissed. A store rejection (no such appointment)
        // becomes a visible 0x0500 NotifyInvalidMessage instead of a dropped connection (condition 7).
        if (type == 0x0708 && _worldEntered)
        {
            var dismissPayload = decoded.Payload ?? Array.Empty<byte>();
            if (dismissPayload.Length < 26)
            {
                return Invalid("original.card-dismissal.payload", type, Convert.ToHexString(dismissPayload));
            }

            var dismissRestoreError = await RestorePersistedCharacterAsync(cancellationToken);
            if (dismissRestoreError is not null)
            {
                return Invalid(dismissRestoreError, type);
            }

            var dismissAppointerId = System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(dismissPayload.AsSpan(6));
            var dismissTargetId = System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(dismissPayload.AsSpan(18));
            var rawDismissCardId = System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(dismissPayload.AsSpan(22));
            if (rawDismissCardId > ushort.MaxValue)
                return Invalid("original.card-dismissal.card-range", type);
            var dismissCardId = (ushort)rawDismissCardId;
            if (dismissAppointerId != _worldCharacterId || dismissTargetId == 0 || dismissCardId == 0)
            {
                return Invalid($"original.card-dismissal.identity;appointer={dismissAppointerId};world={_worldCharacterId};target={dismissTargetId};card={dismissCardId}", type, Convert.ToHexString(dismissPayload));
            }

            var dismissFingerprint = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
                System.Text.Encoding.UTF8.GetBytes($"original-card-dismissal/v2\n{_accountId:D}\n{_moveGridRequestScope:N}\n{decoded.Sequence}\n{dismissAppointerId}\n{dismissCardId}\n{dismissTargetId}\n{Convert.ToHexString(dismissPayload)}"))).ToLowerInvariant();
            CardDismissalStoreResult dismissed;
            try
            {
                dismissed = await _store.DismissCardAsync(
                    _accountId,
                    new CardDismissalWrite(dismissFingerprint, dismissAppointerId, dismissCardId, dismissTargetId,
                        CommandPointPolicy.Value, _gameClock.Now, IsCurrentTacticalFieldActive),
                    cancellationToken);
            }
            catch (Exception exception) when (exception is InvalidOperationException or ArgumentException or Npgsql.PostgresException)
            {
                var dismissReason = exception is Npgsql.PostgresException pg ? $"postgres-{pg.SqlState}" : exception.Message.ToLowerInvariant().Replace('_', '-');
                return RejectCommandVisibly($"original.card-dismissal.{dismissReason}", type.GetValueOrDefault(), "罷免できません（対象が任命されていないか、既に処理済みです）");
            }

            _receipt.Record("card-dismissal", $"dismissed;card={dismissCardId};target={dismissTargetId};version={dismissed.AuthorityVersion};updated={dismissed.Updated}", _accountId);
            var dismissalActor = (await _store.ListCharactersAsync(_accountId,cancellationToken))
                .Single(row=>row.CharacterId==_worldCharacterId);
            _worldPcp=dismissalActor.Pcp; _worldMcp=dismissalActor.Mcp;
            var dismissAccepted = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + 160];
            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(dismissAccepted.AsSpan(4), 0x0708);
            dismissPayload.AsSpan(2, Math.Min(dismissPayload.Length - 2, 160)).CopyTo(dismissAccepted.AsSpan(6));
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(dismissAccepted.AsSpan(14),_worldPcp);
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(dismissAccepted.AsSpan(18),_worldMcp);
            return EncodeApplicationResponse(dismissAccepted, type, includeLobbyPrefix: true) with
            {
                ResponseMetadata = $"card-dismissal-accepted;card={dismissCardId};target={dismissTargetId};appointer={dismissAppointerId};authorityVersion={dismissed.AuthorityVersion};payloadHex={Convert.ToHexString(dismissPayload)}"
            };
        }

        // 辞任 CommandCardResignation (0x0709): the character resigns from the post they hold. Captured live
        // 2026-09-03 (run 20260903T063644Z): [u16 type][u32 time][u32 actor][u32 pcp][u32 mcp][u32 cardId][u32 0][u8 0],
        // 27 bytes, no picker — the client sends the card it currently displays. The resulting state is card 0 = 個人,
        // the ORIGINAL's own "holds no post" value (constmsg group 3 row 0), proven to render as 「皇宮 ： 個人」 with an
        // empty command grid (run 20260903T085429Z). Persistence: original_character_card.card_id = 0 +
        // original_card_resignation_command + event CharacterCardResigned (migration 0015).
        if (type == 0x0709 && _worldEntered)
        {
            var resignPayload = decoded.Payload ?? Array.Empty<byte>();
            var resignRestoreError = await RestorePersistedCharacterAsync(cancellationToken);
            if (resignRestoreError is not null)
            {
                return Invalid(resignRestoreError, type);
            }

            var decodedResign = OriginalCardResignationCodec.Decode(resignPayload);
            if (!decodedResign.Success || decodedResign.Command is not { } resignCommand)
            {
                return Invalid(decodedResign.ErrorCode ?? "original.card-resignation.decode", type, Convert.ToHexString(resignPayload));
            }

            if (resignCommand.ActorId != _worldCharacterId || resignCommand.CardId == 0)
            {
                return Invalid($"original.card-resignation.identity;actor={resignCommand.ActorId};world={_worldCharacterId};card={resignCommand.CardId}", type, Convert.ToHexString(resignPayload));
            }

            var resignFingerprint = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
                System.Text.Encoding.UTF8.GetBytes($"original-card-resignation/v2\n{_accountId:D}\n{_moveGridRequestScope:N}\n{decoded.Sequence}\n{resignCommand.ActorId}\n{resignCommand.CardId}\n{Convert.ToHexString(resignPayload)}"))).ToLowerInvariant();
            CardResignationStoreResult resigned;
            try
            {
                resigned = await _store.ResignCardAsync(
                    _accountId,
                    new CardResignationWrite(resignFingerprint, resignCommand.ActorId, checked((int)resignCommand.CardId),
                        CommandPointPolicy.Value, _gameClock.Now, IsCurrentTacticalFieldActive),
                    OriginalAuthoredPlayableCatalog.AuthorityCardId,
                    cancellationToken);
            }
            catch (Exception exception) when (exception is InvalidOperationException or ArgumentException or Npgsql.PostgresException)
            {
                var resignReason = exception is Npgsql.PostgresException pg ? $"postgres-{pg.SqlState}" : exception.Message.ToLowerInvariant().Replace('_', '-');
                return RejectCommandVisibly($"original.card-resignation.{resignReason}", type.GetValueOrDefault(), "辞任できません（現在の職務と一致しないか、既に処理済みです）");
            }

            _worldCardId = 0;
            var resignationCharacter = (await _store.ListCharactersAsync(_accountId, cancellationToken))
                .Single(row => row.CharacterId == _worldCharacterId);
            _worldPcp = resignationCharacter.Pcp;
            _worldMcp = resignationCharacter.Mcp;
            _receipt.Record("card-resignation", $"resigned;from={resignCommand.CardId};character={resignCommand.ActorId};version={resigned.AuthorityVersion};updated={resigned.Updated}", _accountId);
            var resignAccepted = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + 160];
            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(resignAccepted.AsSpan(4), 0x0709);
            resignPayload.AsSpan(2, Math.Min(resignPayload.Length - 2, 160)).CopyTo(resignAccepted.AsSpan(6));
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(resignAccepted.AsSpan(14), _worldPcp);
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(resignAccepted.AsSpan(18), _worldMcp);
            return EncodeApplicationResponse(resignAccepted, type, includeLobbyPrefix: true) with
            {
                ResponseMetadata = $"card-resignation-accepted;from={resignCommand.CardId};to=0;character={resignCommand.ActorId};authorityVersion={resigned.AuthorityVersion};payloadHex={Convert.ToHexString(resignPayload)}"
            };
        }

        // PROBE (2026-09-03, LOGH7_COMMAND_ECHO=1): strategy command families the authority does not implement yet
        // (0x0700-0x070F card/personnel, 0x0900-0x090F, 0x0C00-0x0C0F unit maintenance, 0x0B00-0x0B0F strategic movement)
        // are recorded (payload hex) and echoed back as a 160-byte response of the same type so the client's command
        // panel closes instead of the connection dropping. No state is mutated; each captured payload feeds the
        // command-by-command implementation (docs/reverse-engineering/strategy-command-ledger.json).
        if (_worldEntered &&
            Environment.GetEnvironmentVariable("LOGH7_COMMAND_ECHO") == "1" &&
            type is (>= 0x0700 and <= 0x070F) or (>= 0x0900 and <= 0x090F) or (>= 0x0C00 and <= 0x0C0F) or (>= 0x0B00 and <= 0x0B0F) &&
            type != 0x0704 && type != 0x0705 && type != 0x0706 && type != 0x0707 && type != 0x0708 && type != 0x0709)
        {
            var echoPayload = decoded.Payload ?? Array.Empty<byte>();
            _receipt.Record("command-echo", $"type=0x{type:X4};payload={Convert.ToHexString(echoPayload)}", _accountId);
            var echoFrame = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + 160];
            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(echoFrame.AsSpan(4), (ushort)type);
            echoPayload.AsSpan(Math.Min(2, echoPayload.Length), Math.Min(Math.Max(echoPayload.Length - 2, 0), 160)).CopyTo(echoFrame.AsSpan(6));
            return EncodeApplicationResponse(echoFrame, type, includeLobbyPrefix: true) with
            {
                ResponseMetadata = $"command-echo-probe;type=0x{type:X4};payloadHex={Convert.ToHexString(echoPayload)}"
            };
        }

        // 降等 CommandRankDown (0x0706): the acting character demotes a target character one rank down the ladder
        // (NEW_DESIGN persistence = character.rank+1, character_rank_command replay row, event CharacterDemoted with the
        // actor). Captured live 2026-09-03 (run 20260903T063644Z); the client ignores the response body.
        if (type == OriginalRankDownCodec.CommandType)
        {
            var parsedDown = OriginalRankDownCodec.Decode(decoded.Payload!);
            if (!parsedDown.Success)
            {
                return Invalid(parsedDown.ErrorCode!, type, Convert.ToHexString(decoded.Payload!));
            }

            var restoreDownError = await RestorePersistedCharacterAsync(cancellationToken);
            if (restoreDownError is not null)
            {
                return Invalid(restoreDownError, type);
            }

            var down = parsedDown.Command!.Value;
            if (!_worldEntered ||
                _createdCharacter is not OriginalCreateCharacterCommand downActor ||
                _worldCharacterId == 0 ||
                down.ActorId != _worldCharacterId ||
                down.TargetCharacterId == 0 ||
                down.TargetRank < 1 ||
                down.TargetRank >= byte.MaxValue ||
                down.MoveCharacterIds.Length != 0)
            {
                return Invalid(
                    $"original.rank-down.authority-rejected;actor={down.ActorId};world={_worldCharacterId};target={down.TargetCharacterId};rank={down.TargetRank}",
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            var downFingerprint = Convert.ToHexStringLower(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(
                $"original-demotion/v2|{_moveGridRequestScope:N}|{decoded.Sequence}|{Convert.ToHexString(decoded.Payload!)}")));
            CharacterRankUpStoreResult downStored;
            try
            {
                downStored = await _store.PromoteCharacterAsync(
                    _accountId,
                    new CharacterRankUpWrite(
                        downFingerprint,
                        down.TargetCharacterId,
                        down.TargetRank,
                        checked((short)(down.TargetRank + 1)),
                        "CharacterDemoted",
                        _worldCharacterId,CommandPointPolicy.Value,_gameClock.Now,IsCurrentTacticalFieldActive),
                    cancellationToken);
            }
            catch (Exception exception) when (exception is InvalidOperationException or ArgumentException or Npgsql.PostgresException)
            {
                var downReason = exception is Npgsql.PostgresException pg ? $"postgres-{pg.SqlState}" : exception.Message.ToLowerInvariant().Replace('_', '-');
                return RejectCommandVisibly($"original.rank-down.{downReason}", type.GetValueOrDefault(), "降等できません（階級が一致しないか、既に処理済みです）");
            }

            if (downStored.CharacterId == _worldCharacterId && downStored.Rank is > 0 and <= byte.MaxValue)
            {
                _createdCharacter = downActor with { Rank = checked((byte)downStored.Rank), Achievement = downStored.Achievement };
            }

            _receipt.Record(
                "character-rank-down",
                (downStored.Updated ? "rank-demoted" : "idempotent-replay") + $";target={down.TargetCharacterId};rank={down.TargetRank}->{downStored.Rank}",
                _accountId);
            var downBalances = (await _store.ListCharactersAsync(_accountId,cancellationToken))
                .Single(row=>row.CharacterId==_worldCharacterId);
            _worldPcp=downBalances.Pcp; _worldMcp=downBalances.Mcp;
            var downAccepted = EncodeApplicationResponse(
                OriginalRankDownCodec.EncodeAccepted(down with { Achievement = downStored.Achievement,
                    Pcp = _worldPcp, Mcp = _worldMcp }),
                type,
                includeLobbyPrefix: true) with
            {
                ResponseMetadata = $"rank-down-accepted;target={down.TargetCharacterId};rank={down.TargetRank}->{downStored.Rank};updated={downStored.Updated};authorityVersion={downStored.AuthorityVersion}"
            };
            if (downStored.CharacterId != _worldCharacterId || _createdCharacter is null)
            {
                return downAccepted;
            }

            return downAccepted with
            {
                AdditionalResponses =
                [
                    EncodeApplicationPush(
                        EncodeLocatedCharacter(
                            _worldCharacterId,
                            _worldGridUnitId,
                            EffectiveWorldCardId,
                            _createdCharacter.Value))
                ]
            };
        }

        // 抜擢 CommandSpeciallyRankUp (0x0705): the acting character promotes a target character one rank up the
        // ladder (NEW_DESIGN persistence = the same character_rank_command/character rows as 0x0704, event
        // CharacterSpeciallyPromoted with the actor). Captured live 2026-09-03; the client ignores the response body.
        if (type == OriginalSpecialRankUpCodec.CommandType)
        {
            var parsedSpecial = OriginalSpecialRankUpCodec.Decode(decoded.Payload!);
            if (!parsedSpecial.Success)
            {
                return Invalid(parsedSpecial.ErrorCode!, type, Convert.ToHexString(decoded.Payload!));
            }

            var restoreSpecialError = await RestorePersistedCharacterAsync(cancellationToken);
            if (restoreSpecialError is not null)
            {
                return Invalid(restoreSpecialError, type);
            }

            var special = parsedSpecial.Command!.Value;
            if (!_worldEntered ||
                _createdCharacter is not OriginalCreateCharacterCommand specialActor ||
                _worldCharacterId == 0 ||
                special.ActorId != _worldCharacterId ||
                special.TargetCharacterId == 0 ||
                special.TargetRank <= 1 ||
                special.MoveCharacterIds.Length != 0)
            {
                return Invalid(
                    $"original.special-rank-up.authority-rejected;actor={special.ActorId};world={_worldCharacterId};target={special.TargetCharacterId};rank={special.TargetRank}",
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            var specialFingerprint = Convert.ToHexStringLower(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(
                $"original-special-promotion/v2|{_moveGridRequestScope:N}|{decoded.Sequence}|{Convert.ToHexString(decoded.Payload!)}")));
            CharacterRankUpStoreResult specialStored;
            try
            {
                specialStored = await _store.PromoteCharacterAsync(
                    _accountId,
                    new CharacterRankUpWrite(
                        specialFingerprint,
                        special.TargetCharacterId,
                        special.TargetRank,
                        checked((short)(special.TargetRank - 1)),
                        "CharacterSpeciallyPromoted",
                        _worldCharacterId,CommandPointPolicy.Value,_gameClock.Now,IsCurrentTacticalFieldActive),
                    cancellationToken);
            }
            catch (Exception exception) when (exception is InvalidOperationException or ArgumentException or Npgsql.PostgresException)
            {
                var specialReason = exception is Npgsql.PostgresException pg ? $"postgres-{pg.SqlState}" : exception.Message.ToLowerInvariant().Replace('_', '-');
                return RejectCommandVisibly($"original.special-rank-up.{specialReason}", type.GetValueOrDefault(), "抜擢できません（階級が一致しないか、既に処理済みです）");
            }

            if (specialStored.CharacterId == _worldCharacterId && specialStored.Rank is > 0 and <= byte.MaxValue)
            {
                _createdCharacter = specialActor with { Rank = checked((byte)specialStored.Rank), Achievement = specialStored.Achievement };
            }

            var specialBalances = (await _store.ListCharactersAsync(_accountId,cancellationToken))
                .Single(row=>row.CharacterId==_worldCharacterId);
            _worldPcp=specialBalances.Pcp; _worldMcp=specialBalances.Mcp;

            _receipt.Record(
                "character-special-rank-up",
                (specialStored.Updated ? "rank-promoted" : "idempotent-replay") + $";target={special.TargetCharacterId};rank={special.TargetRank}->{specialStored.Rank}",
                _accountId);
            var specialAccepted = EncodeApplicationResponse(
                OriginalSpecialRankUpCodec.EncodeAccepted(special with { Achievement = specialStored.Achievement,
                    Pcp = _worldPcp, Mcp = _worldMcp }),
                type,
                includeLobbyPrefix: true) with
            {
                ResponseMetadata = $"special-rank-up-accepted;target={special.TargetCharacterId};rank={special.TargetRank}->{specialStored.Rank};updated={specialStored.Updated};authorityVersion={specialStored.AuthorityVersion}"
            };
            if (specialStored.CharacterId != _worldCharacterId || _createdCharacter is null)
            {
                return specialAccepted;
            }

            return specialAccepted with
            {
                AdditionalResponses =
                [
                    EncodeApplicationPush(
                        EncodeLocatedCharacter(
                            _worldCharacterId,
                            _worldGridUnitId,
                            EffectiveWorldCardId,
                            _createdCharacter.Value))
                ]
            };
        }

        if (type == OriginalRankUpCodec.CommandType)
        {
            var parsed = OriginalRankUpCodec.Decode(decoded.Payload!);
            if (!parsed.Success)
            {
                return Invalid(
                    parsed.ErrorCode!,
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            var restoreError = await RestorePersistedCharacterAsync(cancellationToken);
            if (restoreError is not null)
            {
                return Invalid(restoreError, type);
            }

            var command = parsed.Command!.Value;
            if (!_worldEntered ||
                _createdCharacter is not OriginalCreateCharacterCommand character ||
                _worldCharacterId == 0 ||
                command.TargetRank <= 1 ||
                command.MoveCharacterIds.Length != 0)
            {
                return Invalid(
                    "original.rank-up.authority-rejected",
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            var requestFingerprint = Convert.ToHexStringLower(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(
                $"original-rank-up/v2|{_moveGridRequestScope:N}|{decoded.Sequence}|{Convert.ToHexString(decoded.Payload!)}")));
            CharacterRankUpStoreResult stored;
            try
            {
                stored = await _store.PromoteCharacterAsync(
                    _accountId,
                    new CharacterRankUpWrite(
                        requestFingerprint,
                        _worldCharacterId,
                        command.TargetRank,
                        checked((short)(command.TargetRank - 1))),
                    cancellationToken);
            }
            catch (InvalidOperationException exception)
                when (exception.Message is "CHARACTER_RANK_CONFLICT" or
                    "CHARACTER_RANK_UP_REPLAY_MISMATCH")
            {
                return Invalid(
                    "original.rank-up.authority-conflict",
                    type,
                    requestFingerprint);
            }

            if (stored.Rank is <= 0 or > byte.MaxValue)
            {
                return Invalid("original.rank-up.persisted-rank-range", type);
            }

            _createdCharacter = character with { Rank = checked((byte)stored.Rank), Achievement = stored.Achievement };
            var rankBalances = (await _store.ListCharactersAsync(_accountId, cancellationToken))
                .Single(row => row.CharacterId == _worldCharacterId);
            _worldPcp = rankBalances.Pcp;
            _worldMcp = rankBalances.Mcp;
            _receipt.Record(
                "character-rank-up",
                stored.Updated ? "rank-promoted" : "idempotent-replay",
                _accountId);
            var accepted = EncodeApplicationResponse(
                OriginalRankUpCodec.EncodeAccepted(command with { RankChangedCharacterAchievement = stored.Achievement,
                    Pcp = _worldPcp, Mcp = _worldMcp }),
                type,
                includeLobbyPrefix: true);
            return accepted with
            {
                AdditionalResponses =
                [
                    EncodeApplicationPush(
                        EncodeLocatedCharacter(
                            _worldCharacterId,
                            _worldGridUnitId,
                            EffectiveWorldCardId,
                            _createdCharacter.Value))
                ]
            };
        }

        if (type == OriginalCharacterChargeCodec.CommandType)
        {
            if (!OriginalCharacterChargeCodec.TryDecode(
                    decoded.Payload!,
                    out var candidateCharacterIds))
            {
                return Invalid(
                    "original.character-charge.request-shape",
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            var authoredCandidateIds = OriginalLotteryCandidateCatalog.Entries
                .Select(candidate => candidate.CharacterId)
                .ToHashSet();
            if (candidateCharacterIds.Any(candidateId => !authoredCandidateIds.Contains(candidateId)))
            {
                return Invalid(
                    "original.character-charge.candidate-mismatch",
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            var requestFingerprint = Convert.ToHexStringLower(
                SHA256.HashData(decoded.Payload!));
            OriginalCharacterLotteryEntryStoreResult stored;
            try
            {
                stored = await _store.EnterOriginalCharacterLotteryAsync(
                    _accountId,
                    new OriginalCharacterLotteryEntryWrite(
                        requestFingerprint,
                        candidateCharacterIds.ToArray()),
                    cancellationToken);
            }
            catch (InvalidOperationException exception)
                when (exception.Message == "ORIGINAL_LOTTERY_ENTRY_ALREADY_PENDING")
            {
                return Invalid(
                    "original.character-charge.pending-conflict",
                    type,
                    requestFingerprint);
            }

            _receipt.Record(
                "original-character-charge",
                stored.Created ? "persisted-entry-created" : "persisted-entry-replayed",
                _accountId);
            var resultCandidateId = candidateCharacterIds[
                RandomNumberGenerator.GetInt32(candidateCharacterIds.Count)];
            var template = OriginalLotteryCandidateCatalog.Get(resultCandidateId);
            var award = await _store.AwardOriginalCharacterLotteryAsync(
                _accountId,
                new OriginalCharacterLotteryAwardWrite(
                    stored.EntryId,
                    resultCandidateId,
                    OriginalLotteryCandidateCatalog.Provenance,
                    new CharacterCreateWrite(
                        OriginalCharacterLotteryAwardIdentity.CharacterRequestFingerprint(
                            stored.EntryId,
                            resultCandidateId),
                        OriginalCharacterLotteryAwardIdentity.CharacterPayloadHash(
                            stored.EntryId,
                            resultCandidateId,
                            OriginalLotteryCandidateCatalog.Provenance),
                        template.Faction,
                        template.Blood,
                        template.Sex,
                        template.LastName,
                        template.FirstName,
                        template.FlagshipName,
                        template.Face,
                        template.AbilityValues.ToArray())),
                cancellationToken);
            _receipt.Record(
                "original-character-lottery-award",
                award.Awarded ? "persisted-award-created" : "persisted-award-replayed",
                _accountId);
            return EncodeApplicationResponse(
                OriginalCharacterChargeCodec.EncodeAccepted(candidateCharacterIds),
                type,
                includeLobbyPrefix: true);
        }

        if (type == 0x0f06 && decoded.Payload!.Length == sizeof(ushort))
        {
            var characters = await _store.ListCharactersAsync(_accountId, cancellationToken);
            if (characters.Count > 100 || characters.Any(character =>
                    character.CharacterId is <= 0 or > uint.MaxValue ||
                    character.Rank is < 0))
            {
                return Invalid(
                    "original.messenger.authoritative-character-roster",
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            var records = characters.Select(character =>
                new OriginalMessengerInformationRecord(
                    checked((uint)character.CharacterId),
                    $"{character.FirstName}・{character.LastName}",
                    character.FlagshipName,
                    checked((ushort)character.Rank))).ToList();
            // AUTHORED_PLACEHOLDER: the original live presence directory is
            // gone, while the native messenger intentionally excludes the
            // selected character from its card list. Keep one editable
            // catalog contact so a one-character account has a real card to
            // select instead of an empty, dead surface. This is not claimed
            // as an original-server roster rule or an observed online user.
            var contact = OriginalLotteryCandidateCatalog.Templates[0];
            records.Add(new OriginalMessengerInformationRecord(
                contact.CharacterId,
                contact.DisplayName,
                contact.FlagshipName,
                OriginalAuthoredPlayableCatalog.StartingRank));
            _receipt.Record(
                "messenger-information",
                $"owned-count-{characters.Count}-authored-placeholder-contact-1",
                _accountId);
            var messengerInformation = EncodeApplicationResponse(
                OriginalWorldBootstrapCodec.EncodeMessengerInformation(records),
                type,
                includeLobbyPrefix: true);
            return messengerInformation with
            {
                ResponseMetadata = $"messenger-character-count={records.Count}"
            };
        }

        if (type == OriginalMessengerConnectionCodec.RequestType)
        {
            if (!OriginalMessengerConnectionCodec.TryDecode(
                    decoded.Payload!, out var command))
            {
                return Invalid(
                    "original.messenger.connection.invalid-command",
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            if (!_worldEntered)
            {
                return Invalid("original.messenger.connection.world-not-entered", type);
            }

            var characters = await _store.ListCharactersAsync(_accountId, cancellationToken);
            CharacterReadRecord? selected = characters.Count == 1
                ? characters[0]
                : _lobbySelectionValue is null
                    ? null
                    : ResolveSelectedCharacter(characters, _lobbySelectionValue.Value);
            var contact = OriginalLotteryCandidateCatalog.Templates[0];
            if (selected is null ||
                selected.CharacterId is <= 0 or > uint.MaxValue ||
                command.SourceCharacterId != checked((uint)selected.CharacterId))
            {
                return Invalid("original.messenger.connection.source-not-selected", type);
            }

            if (command.TargetCharacterId != contact.CharacterId)
            {
                return Invalid("original.messenger.connection.target-not-advertised", type);
            }

            IReadOnlyList<OriginalMessengerMessageRecord> history;
            try
            {
                history = await _store.ListOriginalMessengerMessagesAsync(
                    _accountId,
                    command.SourceCharacterId,
                    command.TargetCharacterId,
                    cancellationToken);
            }
            catch (InvalidOperationException exception)
                when (exception.Message == "MESSENGER_VIEWER_NOT_OWNED")
            {
                return Invalid("original.messenger.connection.authority-conflict", type);
            }

            // NEW DESIGN / AUTHORED_PLACEHOLDER: the historical presence
            // service is unavailable. This accepts only the selected owned
            // character talking to the single contact advertised by 0x0f07.
            // Persisted raw 0x0f0f commands are replayed through the already
            // observed native receive path after a fresh connection. This is
            // not a claim about the historical server's storage design or a
            // second human client.
            _receipt.Record(
                "messenger-connection",
                FormattableString.Invariant(
                    $"new-design-persisted-history-source-{command.SourceCharacterId}-target-{command.TargetCharacterId}-count-{history.Count}"),
                _accountId);
            _messengerSourceCharacterId = command.SourceCharacterId;
            _messengerTargetCharacterId = command.TargetCharacterId;
            return EncodeApplicationResponse(
                OriginalMessengerConnectionCodec.EncodeAccepted(decoded.Payload!),
                type,
                includeLobbyPrefix: true) with
            {
                AdditionalResponses = history.Count == 0
                    ? null
                    : history
                        .Select(message => EncodeApplicationPush(
                            OriginalMessengerMessageCodec.EncodeAccepted(
                                message.WirePayload)))
                        .ToArray(),
                ResponseMetadata = FormattableString.Invariant(
                    $"messenger-connection-source={command.SourceCharacterId};target={command.TargetCharacterId};history-count={history.Count};persistence=postgres")
            };
        }

        if (type == OriginalMessengerMessageCodec.RequestType)
        {
            if (!OriginalMessengerMessageCodec.TryDecode(
                    decoded.Payload!, out var command))
            {
                return Invalid(
                    "original.messenger.message.invalid-command",
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            if (!_worldEntered)
            {
                return Invalid("original.messenger.message.world-not-entered", type);
            }

            if (_messengerSourceCharacterId == 0 || _messengerTargetCharacterId == 0)
            {
                return Invalid("original.messenger.message.connection-required", type);
            }

            if (command.SourceCharacterId != _messengerSourceCharacterId)
            {
                return Invalid("original.messenger.message.source-not-connected", type);
            }

            var contact = OriginalLotteryCandidateCatalog.Templates[0];
            if (_messengerTargetCharacterId != contact.CharacterId)
            {
                return Invalid("original.messenger.message.target-not-advertised", type);
            }

            var requestFingerprint = Convert.ToHexStringLower(
                SHA256.HashData(decoded.Payload!));
            OriginalMessengerMessageStoreResult stored;
            try
            {
                stored = await _store.SaveOriginalMessengerMessageAsync(
                    _accountId,
                    new OriginalMessengerMessageWrite(
                        requestFingerprint,
                        command.SourceCharacterId,
                        _messengerTargetCharacterId,
                        command.Message,
                        decoded.Payload!.ToArray()),
                    cancellationToken);
            }
            catch (InvalidOperationException exception)
                when (exception.Message is "MESSENGER_SENDER_NOT_OWNED" or
                    "MESSENGER_MESSAGE_REPLAY_MISMATCH")
            {
                return Invalid(
                    "original.messenger.message.authority-conflict",
                    type,
                    requestFingerprint);
            }

            // NEW DESIGN / AUTHORED_PLACEHOLDER: persist the selected
            // player's semantic message and exact raw 0x0f0f command, then
            // echo it through the original receive path. This makes the
            // native conversation surface recoverable after reconnect while
            // leaving peer delivery and historical storage semantics unproven.
            _receipt.Record(
                "messenger-message",
                FormattableString.Invariant(
                    $"new-design-persisted-echo-id-{stored.MessageId}-source-{command.SourceCharacterId}-target-{_messengerTargetCharacterId}-characters-{command.Message.Length}"),
                _accountId);
            return EncodeApplicationResponse(
                OriginalMessengerMessageCodec.EncodeAccepted(decoded.Payload!),
                type,
                includeLobbyPrefix: true) with
            {
                ResponseMetadata = FormattableString.Invariant(
                    $"messenger-message-id={stored.MessageId};created={stored.Created.ToString().ToLowerInvariant()};source={command.SourceCharacterId};target={_messengerTargetCharacterId};characters={command.Message.Length};persistence=postgres")
            };
        }

        if (type == 0x0f04)
        {
            var characters = await _store.ListCharactersAsync(_accountId, cancellationToken);
            CharacterReadRecord? selected = null;
            if (characters.Count == 1)
            {
                selected = characters[0];
            }
            else if (characters.Count > 1)
            {
                if (_lobbySelectionValue is null)
                {
                    return Invalid(
                        "original.character.selection.multiple-not-instrumented",
                        type,
                        Convert.ToHexString(decoded.Payload!));
                }

                selected = ResolveSelectedCharacter(characters, _lobbySelectionValue.Value);
                if (selected is null)
                {
                    return Invalid(
                        "original.character.selection.unavailable",
                        type,
                        Convert.ToHexString(decoded.Payload!));
                }
            }

            var addresses = selected is not null
                ? new[]
                {
                    new OriginalMailAddressRecord(
                        checked((uint)selected.CharacterId),
                        $"{selected.FirstName}・{selected.LastName}")
                }
                : [];
            _receipt.Record(
                "mail-address",
                $"authoritative-count-{addresses.Length}",
                _accountId);
            return EncodeApplicationResponse(
                OriginalWorldBootstrapCodec.EncodeMailAddresses(addresses),
                type,
                includeLobbyPrefix: true) with
            {
                ResponseMetadata = $"mail-address-count={addresses.Length};character-id={selected?.CharacterId.ToString() ?? "none"}"
            };
        }

        if (type == OriginalMailSendCodec.RequestType)
        {
            var parsed = OriginalMailSendCodec.Decode(decoded.Payload!);
            if (!parsed.Success)
            {
                return Invalid(
                    parsed.ErrorCode!,
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            var command = parsed.Command!;
            if (_lobbySelectionValue is null)
            {
                return Invalid("original.mail.send.selection-missing", type);
            }

            var characters = await _store.ListCharactersAsync(_accountId, cancellationToken);
            var selected = ResolveSelectedCharacter(characters, _lobbySelectionValue.Value);
            if (selected is null || selected.CharacterId != command.SenderCharacterId)
            {
                return Invalid("original.mail.send.sender-not-selected", type);
            }

            if (!characters.Any(character =>
                    character.CharacterId == command.RecipientCharacterId))
            {
                return Invalid("original.mail.send.recipient-not-owned", type);
            }

            OriginalMailSendStoreResult stored;
            try
            {
                stored = await _store.SendOriginalMailAsync(
                    _accountId,
                    new OriginalMailSendWrite(
                        command.RequestFingerprint,
                        command.SenderCharacterId,
                        command.RecipientCharacterId,
                        command.Title,
                        command.Body),
                    cancellationToken);
            }
            catch (InvalidOperationException exception)
                when (exception.Message is "MAIL_CHARACTER_NOT_FOUND" or
                    "MAIL_SEND_REPLAY_MISMATCH")
            {
                return Invalid("original.mail.send.authority-conflict", type);
            }

            _receipt.Record(
                "mail-send",
                stored.Created ? "persisted" : "idempotent-replay",
                _accountId);
            return Success(
                outerControl: null,
                payload: null,
                observedType: type,
                prefix: null) with
            {
                ResponseMetadata = FormattableString.Invariant(
                    $"mail-id={stored.MailId};created={stored.Created.ToString().ToLowerInvariant()};sender-character-id={command.SenderCharacterId};recipient-character-id={command.RecipientCharacterId}")
            };
        }

        if (type == OriginalMailListCodec.RequestType)
        {
            var parsed = OriginalMailListCodec.DecodeRequest(decoded.Payload!);
            if (!parsed.Success)
            {
                return Invalid(
                    parsed.ErrorCode!,
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            if (_lobbySelectionValue is null)
            {
                return Invalid("original.mail.list.selection-missing", type);
            }

            var request = parsed.Request!.Value;
            var characters = await _store.ListCharactersAsync(_accountId, cancellationToken);
            var selected = ResolveSelectedCharacter(characters, _lobbySelectionValue.Value);
            if (selected is null || selected.CharacterId != request.CharacterId)
            {
                return Invalid("original.mail.list.character-not-selected", type);
            }

            var storedMail = await _store.ListOriginalMailAsync(_accountId, cancellationToken);
            var selectedCharacterId = selected.CharacterId;
            var filtered = storedMail.Where(mail =>
                    (request.Box == 0
                        ? mail.SenderCharacterId == selectedCharacterId && !mail.SenderDeleted
                        : mail.RecipientCharacterId == selectedCharacterId && !mail.RecipientDeleted) &&
                    (!request.UnreadOnly || !mail.IsRead))
                .ToArray();
            if (filtered.Any(mail =>
                    mail.MailId is <= 0 or > uint.MaxValue ||
                    mail.SenderCharacterId is <= 0 or > uint.MaxValue ||
                    mail.RecipientCharacterId is <= 0 or > uint.MaxValue))
            {
                return Invalid("original.mail.list.identifier-range", type);
            }

            var characterNames = characters.ToDictionary(
                character => character.CharacterId,
                character => $"{character.FirstName}・{character.LastName}");
            if (filtered.Any(mail =>
                    !characterNames.ContainsKey(mail.SenderCharacterId) ||
                    !characterNames.ContainsKey(mail.RecipientCharacterId)))
            {
                return Invalid("original.mail.list.character-name-unavailable", type);
            }

            var begin = EncodeApplicationResponse(
                OriginalMailListCodec.EncodeBegin(request),
                type,
                includeLobbyPrefix: true);
            var additional = new List<NaturalAuthorityPush>(filtered.Length + 3);
            foreach (var mail in filtered)
            {
                additional.Add(EncodeApplicationPush(
                    OriginalMailListCodec.EncodeRecord(new OriginalMailListWireRecord(
                        checked((uint)mail.MailId),
                        uint.MaxValue,
                        // CONFIRMED_STATIC: the original client maps a non-zero byte here
                        // to the row widget's disabled bit. Read state belongs to the 0x0f08
                        // unread filter and must not make a stored message unselectable.
                        0,
                        new OriginalMailListCharacter(
                            checked((uint)mail.SenderCharacterId),
                            characterNames[mail.SenderCharacterId]),
                        new OriginalMailListCharacter(
                            checked((uint)mail.RecipientCharacterId),
                            characterNames[mail.RecipientCharacterId]),
                        0,
                        mail.Title,
                        mail.Body))));
            }
            if (IsEligibleForAuthoredOrderSuggestCard(selected))
            {
                var selectedWireCharacterId = checked((uint)selected.CharacterId);
                const uint referId = uint.MaxValue;
                const byte cardStatus = 0;
                var displayName = OrderSuggestDisplayName(selected);
                var storedReply = await _store.FindOriginalOrderSuggestReplyAsync(
                    _accountId,
                    selected.CharacterId,
                    OriginalAuthoredPlayableCatalog.AuthorityCardId,
                    cancellationToken);
                if (storedReply is not null && storedReply.ReplyValue > 2)
                {
                    return Invalid(
                        "original.order-suggest-reply.persisted-shape",
                        type);
                }

                if (storedReply is null)
                {
                    additional.Add(EncodeApplicationPush(
                        OriginalOrderSuggestMailCodec.EncodeOrder(
                        new OriginalOrderSuggestMailWireRecord(
                            MailId: selectedWireCharacterId,
                            ReferId: referId,
                            Status: cardStatus,
                            Sender: new OriginalMailListCharacter(
                                selectedWireCharacterId,
                                displayName),
                            Recipient: new OriginalMailListCharacter(
                                selectedWireCharacterId,
                                displayName),
                            Time: checked((uint)DateTimeOffset.UtcNow.ToUnixTimeSeconds()),
                            Command: OriginalCommandSearchCodec.Type,
                            OrderSuggestType: OriginalOrderSuggestType.Suggestion,
                            Influence: 0,
                            UnknownTrailing0: 0,
                            UnknownTrailing1: 0))));
                    additional.Add(EncodeApplicationPush(
                        OriginalCommandSearchCodec.Encode(
                            new OriginalCommandSearchWireRecord(0, 0, 0, 0, 0))));
                }
                else
                {
                    var resolvedBody = storedReply.ReplyValue switch
                    {
                        0 => "この命令については拒否しました。",
                        1 => "この命令については拒絶しました。",
                        2 => "オウム返し",
                        _ => throw new InvalidOperationException("ORDER_SUGGEST_REPLY_VALUE")
                    };
                    additional.Add(EncodeApplicationPush(
                        OriginalMailListCodec.EncodeRecord(new OriginalMailListWireRecord(
                            OriginalAuthoredPlayableCatalog.ResolvedAuthorityCardMailId,
                            referId,
                            cardStatus,
                            new OriginalMailListCharacter(selectedWireCharacterId, displayName),
                            new OriginalMailListCharacter(selectedWireCharacterId, displayName),
                            0,
                            "命令（返答済み）",
                            resolvedBody))));
                }
                _receipt.Record(
                    "order-suggest-card",
                    storedReply is null
                        ? $"authored-authority-card-{OriginalAuthoredPlayableCatalog.AuthorityCardId}-target-{selectedWireCharacterId}"
                        : $"authored-authority-card-{OriginalAuthoredPlayableCatalog.AuthorityCardId}-target-{selectedWireCharacterId}-reply-{storedReply.ReplyValue}",
                    _accountId);
            }
            additional.Add(EncodeApplicationPush(OriginalMailListCodec.EncodeEnd()));
            _receipt.Record(
                "mail-list",
                $"authoritative-count-{filtered.Length}",
                _accountId);
            return begin with
            {
                AdditionalResponses = additional,
                ResponseMetadata = FormattableString.Invariant(
                    $"mail-list-count={filtered.Length};character-id={request.CharacterId};box={request.Box};unread-only={request.UnreadOnly.ToString().ToLowerInvariant()};payloadHex={Convert.ToHexString(decoded.Payload!)}")
            };
        }

        if (type == OriginalOrderSuggestReplyCodec.RequestType)
        {
            var parsed = OriginalOrderSuggestReplyCodec.Decode(decoded.Payload!);
            if (!parsed.Success)
            {
                return Invalid(
                    parsed.ErrorCode!,
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            if (_lobbySelectionValue is null)
            {
                return Invalid("original.order-suggest-reply.selection-missing", type);
            }

            var command = parsed.Command!.Value;
            var characters = await _store.ListCharactersAsync(_accountId, cancellationToken);
            var selected = ResolveSelectedCharacter(characters, _lobbySelectionValue.Value);
            var selectedWireId = selected is null
                ? 0
                : checked((uint)selected.CharacterId);
            var expectedDisplayName = selected is null
                ? string.Empty
                : OrderSuggestDisplayName(selected);
            if (selected is null ||
                !IsEligibleForAuthoredOrderSuggestCard(selected) ||
                command.ActorCharacterId != selectedWireId ||
                command.TargetCharacterId != selectedWireId ||
                !string.Equals(
                    command.ActorDisplayName,
                    expectedDisplayName,
                    StringComparison.Ordinal) ||
                !string.Equals(
                    command.TargetDisplayName,
                    expectedDisplayName,
                    StringComparison.Ordinal))
            {
                return Invalid(
                    "original.order-suggest-reply.authority-rejected",
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            var requestFingerprint = Convert.ToHexStringLower(
                SHA256.HashData(decoded.Payload!));
            OriginalOrderSuggestReplyStoreResult stored;
            try
            {
                stored = await _store.SaveOriginalOrderSuggestReplyAsync(
                    _accountId,
                    new OriginalOrderSuggestReplyWrite(
                        requestFingerprint,
                        selected.CharacterId,
                        OriginalAuthoredPlayableCatalog.AuthorityCardId,
                        command.ReplyValue),
                    cancellationToken);
            }
            catch (InvalidOperationException exception)
                when (exception.Message is "CHARACTER_NOT_FOUND" or
                    "ORDER_SUGGEST_REPLY_ALREADY_DECIDED" or
                    "ORDER_SUGGEST_REPLY_REPLAY_MISMATCH")
            {
                return Invalid(
                    "original.order-suggest-reply.authority-conflict",
                    type,
                    requestFingerprint);
            }

            _receipt.Record(
                "order-suggest-reply",
                stored.Updated
                    ? $"authored-card-{OriginalAuthoredPlayableCatalog.AuthorityCardId}-reply-{command.ReplyValue}-persisted"
                    : $"authored-card-{OriginalAuthoredPlayableCatalog.AuthorityCardId}-reply-{command.ReplyValue}-idempotent",
                _accountId);
            return EncodeApplicationResponse(
                OriginalOrderSuggestReplyCodec.EncodeAccepted(command),
                type,
                includeLobbyPrefix: true) with
            {
                ResponseMetadata = FormattableString.Invariant(
                    $"order-suggest-reply={command.ReplyValue};actor-character-id={command.ActorCharacterId};target-character-id={command.TargetCharacterId};authored-card-id={OriginalAuthoredPlayableCatalog.AuthorityCardId}")
            };
        }

        if (type == OriginalMailReadCodec.RequestType)
        {
            var parsed = OriginalMailReadCodec.Decode(decoded.Payload!);
            if (!parsed.Success)
            {
                return Invalid(
                    parsed.ErrorCode!,
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            if (_lobbySelectionValue is null)
            {
                return Invalid("original.mail.read.selection-missing", type);
            }

            var command = parsed.Command!.Value;
            var characters = await _store.ListCharactersAsync(_accountId, cancellationToken);
            var selected = ResolveSelectedCharacter(characters, _lobbySelectionValue.Value);
            if (selected is null || selected.CharacterId != command.CharacterId)
            {
                return Invalid("original.mail.read.character-not-selected", type);
            }

            if (IsEligibleForAuthoredOrderSuggestCard(selected) &&
                command.MailId == OriginalAuthoredPlayableCatalog.ResolvedAuthorityCardMailId)
            {
                var resolvedReply = await _store.FindOriginalOrderSuggestReplyAsync(
                    _accountId,
                    selected.CharacterId,
                    OriginalAuthoredPlayableCatalog.AuthorityCardId,
                    cancellationToken);
                if (resolvedReply is null || resolvedReply.ReplyValue > 2)
                {
                    return Invalid("original.order-suggest-resolved-card.missing", type);
                }

                return EncodeApplicationResponse(
                    OriginalMailReadCodec.EncodeAccepted(command),
                    type,
                    includeLobbyPrefix: true) with
                {
                    ResponseMetadata = FormattableString.Invariant(
                        $"order-suggest-resolved-card-read;character-id={command.CharacterId};reply={resolvedReply.ReplyValue};authored-card-id={OriginalAuthoredPlayableCatalog.AuthorityCardId}")
                };
            }

            if (IsEligibleForAuthoredOrderSuggestCard(selected) &&
                command.MailId == selected.CharacterId)
            {
                var storedReply = await _store.FindOriginalOrderSuggestReplyAsync(
                    _accountId,
                    selected.CharacterId,
                    OriginalAuthoredPlayableCatalog.AuthorityCardId,
                    cancellationToken);
                if (storedReply is not null && storedReply.ReplyValue > 2)
                {
                    return Invalid("original.order-suggest-reply.persisted-shape", type);
                }

                var response = EncodeApplicationResponse(
                    OriginalMailReadCodec.EncodeAccepted(command),
                    type,
                    includeLobbyPrefix: true);
                if (storedReply is null)
                {
                    return response with
                    {
                        ResponseMetadata = FormattableString.Invariant(
                            $"order-suggest-card-read;character-id={command.CharacterId};reply=pending;authored-card-id={OriginalAuthoredPlayableCatalog.AuthorityCardId}")
                    };
                }

                var wireCharacterId = checked((uint)selected.CharacterId);
                var displayName = OrderSuggestDisplayName(selected);
                return response with
                {
                    AdditionalResponses =
                    [
                        EncodeApplicationPush(OriginalOrderSuggestReplyCodec.Encode(
                            wireCharacterId,
                            wireCharacterId,
                            displayName,
                            displayName,
                            storedReply.ReplyValue))
                    ],
                    ResponseMetadata = FormattableString.Invariant(
                        $"order-suggest-card-read;character-id={command.CharacterId};reply={storedReply.ReplyValue};authored-card-id={OriginalAuthoredPlayableCatalog.AuthorityCardId}")
                };
            }

            OriginalMailReadStoreResult stored;
            try
            {
                stored = await _store.MarkOriginalMailReadAsync(
                    _accountId,
                    selected.CharacterId,
                    command.MailId,
                    cancellationToken);
            }
            catch (InvalidOperationException exception)
                when (exception.Message == "MAIL_NOT_FOUND")
            {
                return Invalid("original.mail.read.mail-not-found", type);
            }

            _receipt.Record(
                "mail-read",
                stored.Updated ? "persisted" : "idempotent-replay",
                _accountId);
            return EncodeApplicationResponse(
                OriginalMailReadCodec.EncodeAccepted(command),
                type,
                includeLobbyPrefix: true) with
            {
                ResponseMetadata = FormattableString.Invariant(
                    $"mail-id={stored.MailId};read-updated={stored.Updated.ToString().ToLowerInvariant()};character-id={command.CharacterId};box={command.Box};authority-version={stored.AuthorityVersion}")
            };
        }

        if (type == OriginalMailDeleteCodec.RequestType)
        {
            var parsed = OriginalMailDeleteCodec.Decode(decoded.Payload!);
            if (!parsed.Success)
            {
                return Invalid(
                    parsed.ErrorCode!,
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            if (_lobbySelectionValue is null)
            {
                return Invalid("original.mail.delete.selection-missing", type);
            }

            var command = parsed.Command!.Value;
            var characters = await _store.ListCharactersAsync(_accountId, cancellationToken);
            var selected = ResolveSelectedCharacter(characters, _lobbySelectionValue.Value);
            if (selected is null || selected.CharacterId != command.CharacterId)
            {
                return Invalid("original.mail.delete.character-not-selected", type);
            }

            OriginalMailDeleteStoreResult stored;
            try
            {
                stored = await _store.DeleteOriginalMailAsync(
                    _accountId,
                    selected.CharacterId,
                    command.MailId,
                    command.Box,
                    cancellationToken);
            }
            catch (InvalidOperationException exception)
                when (exception.Message == "MAIL_NOT_FOUND")
            {
                return Invalid("original.mail.delete.mail-not-found", type);
            }

            _receipt.Record(
                "mail-delete",
                stored.Updated ? "persisted" : "idempotent-replay",
                _accountId);
            return EncodeApplicationResponse(
                OriginalMailDeleteCodec.EncodeAccepted(command),
                type,
                includeLobbyPrefix: true) with
            {
                ResponseMetadata = FormattableString.Invariant(
                    $"mail-id={stored.MailId};delete-updated={stored.Updated.ToString().ToLowerInvariant()};character-id={command.CharacterId};box={command.Box};authority-version={stored.AuthorityVersion}")
            };
        }

        if (type == OriginalWarehouseCodec.RequestType)
            return await ProcessWarehouseQueryAsync(decoded.Payload!, cancellationToken);

        if (type == OriginalSystemSceneCodec.BaseParametersRequestType)
        {
            if (!OriginalInformationBaseCodec.TryEncodeResponse(decoded.Payload!,
                    CurrentBattlefieldTemplate().ProjectBaseInformation(_worldGridCellId), out var response))
                return Invalid("original.information-base.request-shape", type);

            // Common authored world data, never reassigned to the requesting faction.
            return EncodeApplicationResponse(response, type, includeLobbyPrefix: true) with
            {
                ResponseMetadata = $"information-base;grid={_worldGridCellId};design=new"
            };
        }

        if (type == OriginalSystemSceneCodec.TacticalUnitShipsRequestType)
        {
            if (!OriginalSystemSceneCodec.TryDecodeTacticalUnitShipIdRequest(
                    decoded.Payload!,
                    out var request))
            {
                return Invalid(
                    "original.tactics-unit-ship.request-shape",
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            var restoreError = await RestorePersistedCharacterAsync(cancellationToken);
            if (restoreError is not null)
            {
                return Invalid(restoreError, type);
            }

            var gridRestoreError = await RestorePersistedGridUnitAsync(cancellationToken);
            if (gridRestoreError is not null)
            {
                return Invalid(gridRestoreError, type);
            }

            using var snapshotLease=await _battles.LockAsync(_worldGridCellId,
                OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number,cancellationToken);
            if (!HasCurrentShipIncarnation) return Invalid("original.flagship.scene-refresh-required",type);
            var available = _worldGridUnitId == 0 || _worldCharacterId == 0
                ? Array.Empty<OriginalTacticalUnitShipRecord>()
                : CurrentTacticalBattlefield().Records;
            var projected = OriginalSystemSceneCodec.ProjectTacticalUnitShips(
                request,
                available);
            _receipt.Record(
                "tactics-unit-ship",
                FormattableString.Invariant(
                    $"requested={request.Ids.Count};returned={projected.Records.Count};unit={_worldGridUnitId}"),
                _accountId);
            return EncodeApplicationResponse(
                OriginalSystemSceneCodec.EncodeTacticalUnitShips(projected),
                type,
                includeLobbyPrefix: true) with
            {
                ResponseMetadata = FormattableString.Invariant(
                    $"tactics-unitship-request-count={request.Ids.Count};returned-count={projected.Records.Count};unit-id={_worldGridUnitId}")
            };
        }

        if (type is ushort tacticalSceneType &&
            OriginalSystemSceneCodec.IsTacticalSceneRequestType(tacticalSceneType))
        {
            if (!OriginalSystemSceneCodec.TryDecodeTacticalSceneRequest(
                    decoded.Payload!,
                    out var request))
            {
                return Invalid(
                    "original.tactical-scene.request-shape",
                    type,
                    Convert.ToHexString(decoded.Payload!));
            }

            var restoreError = await RestorePersistedCharacterAsync(cancellationToken);
            if (restoreError is not null)
            {
                return Invalid(restoreError, type);
            }
            var gridRestoreError = await RestorePersistedGridUnitAsync(cancellationToken);
            if (gridRestoreError is not null)
            {
                return Invalid(gridRestoreError, type);
            }

            using var snapshotLease=await _battles.LockAsync(_worldGridCellId,
                OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number,cancellationToken);
            if (!HasCurrentShipIncarnation) return Invalid("original.flagship.scene-refresh-required",type);
            var battlefield = _worldGridUnitId == 0 || _worldCharacterId == 0
                ? new OriginalTacticalUnitShipResponse(
                    Array.Empty<OriginalTacticalUnitShipRecord>())
                : CurrentTacticalBattlefield();
            var template = CurrentBattlefieldTemplate();
            byte[] response;
            switch (request.Type)
            {
                case OriginalSystemSceneCodec.TacticalCharactersRequestType:
                    response = OriginalSystemSceneCodec.EncodeTacticalCharacters(
                        _worldCharacterId == 0 || request.Qualifier != _worldGridCellId
                            ? Array.Empty<uint>()
                            : battlefield.Records.Select(ship => ship.Character).Distinct().ToArray());
                    break;
                case OriginalSystemSceneCodec.TacticalCorpsRequestType:
                    response = OriginalSystemSceneCodec.EncodeTacticalCorps(
                        OriginalSystemSceneCodec.ProjectTacticalCorps(
                            request.Ids,
                            CurrentTacticalCorps().Records));
                    break;
                case OriginalSystemSceneCodec.TacticalFillShieldRequestType:
                    response = OriginalTacticalShieldCodec.Encode(OriginalTacticalShieldCodec.Project(
                        request.Ids, battlefield.Records.Select(ship =>
                            OriginalTacticalShieldCodec.CreateAuthoredInitialState(ship.Id)).ToArray()));
                    break;
                case OriginalSystemSceneCodec.TacticalBasesRequestType:
                    response = OriginalSystemSceneCodec.EncodeTacticalBases(
                        new(ActiveBattlefieldCatalog.ProjectTacticalBases(_worldGridCellId, request.Ids)));
                    break;
                case OriginalSystemSceneCodec.TacticalObstaclesRequestType:
                    response = OriginalSystemSceneCodec.EncodeObstacles(
                        ActiveBattlefieldCatalog
                            .Resolve(request.Qualifier)
                            .ProjectObstacles(request.Qualifier));
                    break;
                case OriginalSystemSceneCodec.UnitPositionsRequestType:
                    // The complete snapshot/import-ready operation now owns
                    // snapshotLease; reacquiring this non-reentrant lock hangs.
                    if (request.Ids.Contains(_worldGridUnitId) &&
                        battlefield.Records.Any(ship => ship.Id == _worldGridUnitId))
                        MarkOwnNpcSceneImported();
                    response = OriginalSystemSceneCodec.EncodeUnitPositions(
                        OriginalSystemSceneCodec.ProjectTacticalUnitShips(
                            new OriginalTacticalUnitShipIdRequest(request.Ids),
                            battlefield.Records));
                    break;
                case OriginalSystemSceneCodec.BasePositionsRequestType:
                    response = OriginalSystemSceneCodec.EncodeBasePositions(
                        new(ActiveBattlefieldCatalog.ProjectBasePositions(_worldGridCellId, request.Ids)));
                    break;
                default:
                    return Invalid("original.tactical-scene.unhandled", type);
            }

            _receipt.Record(
                "tactical-scene",
                FormattableString.Invariant(
                    $"request={request.Type:x4};ids={request.Ids.Count};qualifier={request.Qualifier}"),
                _accountId);
            return EncodeApplicationResponse(
                response,
                type,
                includeLobbyPrefix: true) with
            {
                ResponseMetadata = FormattableString.Invariant(
                    $"tactical-scene-request={request.Type:x4};ids={request.Ids.Count};qualifier={request.Qualifier};design=new;npc-scene-imported={_npcSceneImportedGrid == _worldGridCellId}")
            };
        }

        if (type == 0x0300 && decoded.Payload!.Length == sizeof(ushort))
        {
            // The clock request is the only thing the client sends on its own,
            // so it is where elapsed regeneration is settled. Throttled inside;
            // the 情報 query settles again so the numbers it serves are fresh.
            if (_worldEntered) await SettleCommandPointRegenerationAsync(cancellationToken);
            var tick = _gameClock.Tick;
            var metadata = FormattableString.Invariant(
                $"time={tick};frequency={OriginalGameClock.TicksPerSecond};epoch=server-process");
            _receipt.Record("world-time", metadata, _accountId);
            var clockAnswer = EncodeApplicationResponse(
                OriginalWorldBootstrapCodec.EncodeResponseTime(tick),
                type,
                includeLobbyPrefix: true) with { ResponseMetadata = metadata };
            // ORIGINAL_OBSERVED 2026-09-10: the client arms its tactical command
            // UI from 0x0F1F - FUN_004C1B20 writes world+0x357E8C = 2 for state 1
            // and 0 otherwise, and the battle sequence reads it. After a world
            // bootstrap that already carried a 0x0F1F(1) among twenty-odd pushed
            // frames, that word was measured as 0 on the live client, so the one
            // inside the burst did not take. Re-asserting it once on the first
            // clock tick after entering an active tactical field costs one 11-byte
            // frame, is idempotent for the client, and is not sent at all when the
            // field is inactive.
            var reassert = TacticalFieldNotificationToReassert();
            return reassert is null
                ? clockAnswer
                : clockAnswer with
                {
                    AdditionalResponses = [EncodeApplicationPush(reassert)],
                    ResponseMetadata = metadata + ";tactics-notify-reasserted=1",
                };
        }

        if (type == 0x0316 && decoded.Payload!.Length == 4)
        {
            var requestedGrid = BinaryPrimitives.ReadUInt16BigEndian(decoded.Payload.AsSpan(2));
            return EncodeApplicationResponse(EncodeCurrentGridState(requestedGrid),
                type, includeLobbyPrefix: true);
        }

        if (type == 0x030a)
        {
            if (decoded.Payload!.Length != 2)
                return Invalid("original.unit-template.request-shape", type);
            var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
            if (restoreError is not null) return Invalid(restoreError,type);
            var templateError = ResolveSceneUnitComplements(out var numbers);
            if (templateError is not null) return Invalid(templateError,type);
            return EncodeApplicationResponse(OriginalWorldBootstrapCodec.EncodeStaticUnitShipsWithComplements(numbers),
                type,includeLobbyPrefix:true);
        }

        if (OriginalWorldBootstrapCodec.TryEncodeResponse(decoded.Payload!, out var bootstrap,
            type == 0x031c ? ActiveBattlefieldCatalog.StaticBases : null, _staticArms))
        {
            _receipt.Record("world-bootstrap", $"request-{type:x4}", _accountId);
            return EncodeApplicationResponse(
                bootstrap,
                type,
                includeLobbyPrefix: true);
        }

        if (type == OriginalCharacterCodec.CreateType)
        {
            var parsed = OriginalCharacterCodec.DecodeCreate(decoded.Payload!);
            if (!parsed.Success)
            {
                return Invalid(parsed.ErrorCode!, type, Convert.ToHexString(decoded.Payload!));
            }

            var command = parsed.Command!.Value;
            if (command.RequestCategory > 4)
            {
                return Invalid("original.character.create.category", type);
            }

            if (command.RequestCategory < 4)
            {
                _receipt.Record(
                    "character-create-step",
                    $"category-{command.RequestCategory}-echoed",
                    _accountId);
                return EncodeApplicationResponse(
                    OriginalCharacterCodec.EncodeAccepted(command),
                    type,
                    includeLobbyPrefix: true);
            }

            if (command.Face > int.MaxValue)
            {
                return Invalid("original.character.create.face", type);
            }

            var fingerprint = Convert.ToHexStringLower(SHA256.HashData(command.RawPayload));
            var write = new CharacterCreateWrite(
                fingerprint,
                fingerprint,
                command.Power,
                command.Blood,
                command.Sex,
                command.LastName,
                command.FirstName,
                command.FlagshipName,
                checked((int)command.Face),
                command.AbilityValues.Select(value => (short)value).ToArray(),
                command.FlagshipType,
                command.FlagshipKind);
            var stored = await _store.CreateCharacterAsync(_accountId, write, cancellationToken);
            var storedId = checked((uint)stored.CharacterId);
            _worldCharacterId = storedId;
            _worldGridUnitId = storedId;
            _createdCharacter = command;
            _worldPcp = 0;
            _worldMcp = 0;
            _receipt.Record(
                "character-create",
                stored.Created ? "created" : "idempotent-replay",
                _accountId);
            return EncodeApplicationResponse(
                OriginalCharacterCodec.EncodeAccepted(command),
                type,
                includeLobbyPrefix: true);
        }

        // PROBE (2026-09-03, LOGH7_COMMAND_ECHO=1): any other world request type is recorded and echoed back
        // (160-byte body of the same type) instead of dropping the connection, so one live sweep can capture every
        // command payload. Read-only; nothing is mutated. Without the env the original rejection stands.
        if (_worldEntered && Environment.GetEnvironmentVariable("LOGH7_COMMAND_ECHO") == "1")
        {
            var anyEchoPayload = decoded.Payload ?? Array.Empty<byte>();
            _receipt.Record("command-echo", $"type=0x{type:X4};payload={Convert.ToHexString(anyEchoPayload)}", _accountId);
            var anyEchoFrame = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + 160];
            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(anyEchoFrame.AsSpan(4), type.GetValueOrDefault());
            anyEchoPayload.AsSpan(Math.Min(2, anyEchoPayload.Length), Math.Min(Math.Max(anyEchoPayload.Length - 2, 0), 160)).CopyTo(anyEchoFrame.AsSpan(6));
            return EncodeApplicationResponse(anyEchoFrame, type, includeLobbyPrefix: true) with
            {
                ResponseMetadata = $"command-echo-probe;type=0x{type:X4};payloadHex={Convert.ToHexString(anyEchoPayload)}"
            };
        }

        // A tactical command the authority has not implemented yet must not end
        // the player's game. 旋回 0x0401 closed the session this way until it
        // was recovered, and 鼓舞 0x0409 still did on 2026-09-09
        // (ORIGINAL_OBSERVED body 040908D24738000000020000000200000002): the
        // client answered with 切断 / サーバーから切断されました。 Inside the
        // 操艦 command block the session now stays open and says so on screen.
        // The payload is recorded, so the sweep that recovers the command keeps
        // its receipt. Outside that block the original rejection stands.
        if (_worldEntered && type is not null &&
            OriginalTacticalCommandCatalog.NameOf(type.Value) is { } unimplementedName)
        {
            var unimplemented = Convert.ToHexString(decoded.Payload!);
            _receipt.Record("tactical-command-unimplemented",
                $"type=0x{type:X4};name={unimplementedName};payload={unimplemented}", _accountId);
            // The refused body goes into the response metadata, which the wire
            // log keeps: one live sweep of the command panel then recovers every
            // remaining 操艦 command's native shape without ending the session.
            return RejectCommandVisibly(OriginalTacticalCommandCatalog.NotImplementedErrorCode, type.Value,
                OriginalTacticalCommandCatalog.NotImplementedText) with
            {
                ResponseMetadata = FormattableString.Invariant(
                    $"command-reject={OriginalTacticalCommandCatalog.NotImplementedErrorCode};type=0x{type.Value:X4};name={unimplementedName};payloadHex={unimplemented};design=new")
            };
        }

        return Invalid(
            "original.session-server.unexpected-application-type",
            type,
            Convert.ToHexString(decoded.Payload!));
    }

    // The 0x04xx 操艦 block the tactical command panel draws from is now
    // enumerated by OriginalTacticalCommandCatalog, recovered from the client's
    // own per-command loggers, rather than by a hand-written bound. The old bound
    // stopped at 0x040F, so every command above it - 任務 0x0421 among them - was
    // treated as a protocol violation and dropped the player's connection.

    private async Task<NaturalAuthoritySessionResult> ProcessMoveGridAsync(
        byte[] payload,
        ushort type,
        uint sequence,
        CancellationToken cancellationToken)
    {
        if (!OriginalMoveGridCodec.TryDecodeRequest(payload, out var request))
        {
            return RejectCommandVisibly("original.move-grid.request-shape", type,
                "この命令は実行できません");
        }

        if (!_worldEntered ||
            _createdCharacter is null ||
            _worldCharacterId == 0 ||
            _worldGridUnitId == 0)
        {
            return RejectCommandVisibly("original.move-grid.world-not-entered", type,
                "この命令は実行できません");
        }

        // ORIGINAL sender004B48D0 uses004B4A90 (login character identity),
        // not InformationUnit.Id. Resolve the ship from the selected session.
        if (request.Id != _worldCharacterId)
            return Invalid("original.move-grid.actor-not-owned", type);
        var characters = await _store.ListCharactersAsync(_accountId, cancellationToken);
        var selected = characters.SingleOrDefault(
            character => character.CharacterId == _worldCharacterId);
        if (selected is null)
        {
            return Invalid("original.move-grid.character-not-owned", type);
        }

        OriginalGridUnitRecord? persisted;
        try
        {
            persisted = await _store.FindOriginalGridUnitAsync(
                _accountId,
                selected.CharacterId,
                _worldGridUnitId,
                cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.move-grid.persistence-not-supported", type);
        }

        if (persisted is null ||
            persisted.CharacterId != selected.CharacterId ||
            persisted.UnitId != _worldGridUnitId)
        {
            return Invalid("original.move-grid.unit-not-owned", type);
        }

        // NEW_DESIGN semantic adapter: the static codec exposes the named
        // request fields, but no original-server capture establishes their
        // authority semantics. This authored first playable route therefore
        // maps only the live unit/card/grid values and derives source from the
        // persisted state. The WARP action comes from the authored card command,
        // never from a zero-filled structural sample.
        var command = new OriginalMoveGridAuthorityCommand(
            UnitId: persisted.UnitId,
            AuthorityCardId: request.Card,
            SourceCellId: persisted.CurrentCellId,
            DestinationCellId: request.Grid,
            Action: OriginalMoveGridAuthority.MinimalWorldWarpAction)
        {
            DestinationBaseId = ArrivalBaseId(request.Grid),
        };
        var decision = OriginalMoveGridAuthority.Transition(
            new OriginalMoveGridAuthorityState(
                persisted.UnitId,
                persisted.AuthorityCardId,
                persisted.CurrentCellId,
                persisted.BaseId, persisted.Cruising),
            command);
        if (decision.Status != OriginalMoveGridAuthorityStatus.Allowed ||
            decision.Notification is null)
        {
            // NEW_DESIGN soft rejection: keep the session open and tell the player why,
            // using the original client's 0x0500 NotifyInvalidMessage (ORIGINAL_STATIC wire).
            return RejectMoveGridVisibly(decision.ErrorCode ?? "authority-rejected", type);
        }

        // NEW_DESIGN intent identity: a fresh sequence after a round trip may
        // legitimately repeat the same payload. The original wire has no
        // demonstrated cross-connection intent token.
        var requestFingerprint = Convert.ToHexStringLower(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(
            FormattableString.Invariant($"original-move-grid/v2|{_moveGridRequestScope:N}|{sequence}|{Convert.ToHexString(payload)}"))));
        OriginalMoveGridStoreResult stored;
        var number = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number;
        using var firstGridLease = await _battles.LockAsync(
            Math.Min(command.SourceCellId,command.DestinationCellId),number,cancellationToken);
        using var secondGridLease = await _battles.LockAsync(
            Math.Max(command.SourceCellId,command.DestinationCellId),number,cancellationToken);
        var current = await _store.FindOriginalGridUnitAsync(
            _accountId,selected.CharacterId,persisted.UnitId,cancellationToken);
        if (current != persisted)
            return RejectMoveGridVisibly("MOVE_GRID_SOURCE_STALE",type);
        if (!HasCurrentShipIncarnation || persisted.ShipGeneration != CurrentShipGeneration)
            return RejectMoveGridVisibly("SHIP_SCENE_REFRESH_REQUIRED",type);
        if (persisted.InjuryReturnId is not null ||
            !_battles.GetEncounter(command.SourceCellId,number).HasSurvivors(command.UnitId))
            return RejectMoveGridVisibly("MOVE_GRID_UNIT_DESTROYED",type);
        try
        {
            stored = await _store.MoveOriginalGridUnitAsync(
                _accountId,
                new OriginalMoveGridWrite(
                    requestFingerprint,
                    selected.CharacterId,
                    command.UnitId,
                    command.AuthorityCardId,
                    persisted.CurrentCellId,
                    command.SourceCellId,
                    command.DestinationCellId,
                    command.Action)
                {
                    DestinationBaseId = command.DestinationBaseId,
                    // The client states this cost itself before it sends the
                    // request; charging it in the move's own transaction keeps
                    // the two from ever disagreeing.
                    Points = new OriginalMoveGridPointCharge(
                        OriginalCommandPointPool.Military,
                        OriginalMoveGridAuthority.WarpMilitaryPointCost,
                        CommandPointPolicy.Value,
                        _gameClock.Now,
                        IsCurrentTacticalFieldActive),
                },
                cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.move-grid.persistence-not-supported", type);
        }

        if (stored.Status == OriginalMoveGridStoreStatus.Replayed)
        {
            return Invalid("original.move-grid.replay", type);
        }

        if (stored.Status != OriginalMoveGridStoreStatus.Moved)
        {
            return RejectMoveGridVisibly(stored.ErrorCode ?? "authority-conflict", type);
        }

        var movedUnit = stored.Unit;
        if (movedUnit is null ||
            movedUnit.CharacterId != selected.CharacterId ||
            movedUnit.UnitId != decision.State.UnitId ||
            movedUnit.AuthorityCardId != decision.State.AuthorityCardId ||
            movedUnit.CurrentCellId != decision.State.CellId ||
            movedUnit.BaseId != decision.State.BaseId ||
            movedUnit.Cruising != decision.State.Cruising ||
            movedUnit.ShipGeneration != persisted.ShipGeneration)
        {
            return Invalid("original.move-grid.persisted-state-shape", type);
        }

        ApplyPersistedGridUnit(movedUnit);
        // Arrival becomes visible before releasing the destination lease, so
        // recovery authorization cannot race a committed strategic movement.
        PublishOwnParticipantSnapshot();
        // The charge committed with the move; re-read the balances the client's
        // next 情報 query will show, so the HUD and the receipt agree.
        var charged = (await _store.ListCharactersAsync(_accountId, cancellationToken))
            .SingleOrDefault(row => row.CharacterId == _worldCharacterId);
        if (charged is not null)
        {
            _worldPcp = charged.Pcp;
            _worldMcp = charged.Mcp;
        }
        _receipt.Record(
            "move-grid",
            $"new-design-unit-{movedUnit.UnitId}-cell-{movedUnit.CurrentCellId}-persisted",
            _accountId);
        var accepted = EncodeApplicationResponse(
            OriginalMoveGridCodec.EncodeNotification(decision.Notification.Value with
            {
                // ORIGINAL_STATIC 004BA3E9 binds SSCharacterIDResponce to +3584A0;
                // 004BDD50/7C only completes the pending0B07 for that actor.
                // This is character identity, not the cruising array's unit ID.
                Id = _worldCharacterId,
                Time = _gameClock.Tick,
                Mode = movedUnit.Mode,
            }),
            type,
            includeLobbyPrefix: true);
        return accepted with
        {
            AdditionalResponses =
            [
                EncodeApplicationPush(
                    EncodeTacticalBattlefieldUnits())
            ],
            ResponseMetadata = FormattableString.Invariant(
                $"move-grid-unit={movedUnit.UnitId};source-cell={command.SourceCellId};destination-cell={movedUnit.CurrentCellId};authority-version={stored.AuthorityVersion};mcp-cost={OriginalMoveGridAuthority.WarpMilitaryPointCost};pcp={_worldPcp};mcp={_worldMcp};design=new")
        };
    }

    /// <summary>
    /// 撤退. 0x0404 is the battlefield's only exit that does not require
    /// winning: the unit warps out and returns to the grid its own base
    /// occupies. Accepting the command without moving the unit left a player
    /// who entered an unwinnable battle with no way out of the tactical scene,
    /// because the strategy card is not on screen there.
    /// </summary>
    private async Task<NaturalAuthoritySessionResult> ProcessTacticalWarpAsync(
        byte[] payload,
        ushort type,
        uint sequence,
        CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeWarpCommand(
                payload,
                out var command))
        {
            return RejectCommandVisibly("original.tactical-warp.request-shape", type,
                "この命令は実行できません");
        }

        if (!_worldEntered || _worldCharacterId == 0 || _worldGridUnitId == 0)
        {
            return RejectCommandVisibly("original.tactical-warp.world-not-entered", type,
                "この命令は実行できません");
        }

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null)
        {
            return Invalid(restoreError, type);
        }

        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;

        // The withdrawal target is the character's own return base, the same
        // 帰還惑星 an injured flagship is sent to; the request carries only
        // unit ids. A unit already standing in that grid, or a world whose
        // catalog does not hold the base, has nowhere to withdraw to: the
        // command is still accepted and the client still receives 0x0425,
        // exactly as it did before this slice.
        var retreatBase = _createdCharacter?.ReturnBaseId ?? 0;
        uint? retreatCell =
            retreatBase != 0 &&
            ActiveBattlefieldCatalog.TryResolveBaseGrid(retreatBase, out var baseCell) &&
            baseCell != 0 && baseCell != _worldGridCellId &&
            // A withdrawal must not deliver the unit to a base that is not its
            // own side's; a hostile garrison is not a refuge.
            ActiveBattlefieldCatalog.Resolve(baseCell).ProjectBaseInformation(baseCell)
                .Any(b => b.Id == retreatBase && b.Power == _createdCharacter?.Power && b.Camp == 0)
            ? baseCell
            : null;
        // Both grids are leased in id order, exactly as a strategic movement
        // does, so a retreat can never interleave with combat on either side.
        var warpNumber = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number;
        using var warpLease = await _battles.LockAsync(
            retreatCell is { } lower ? Math.Min(_worldGridCellId, lower) : _worldGridCellId,
            warpNumber, cancellationToken);
        using var warpDestinationLease = retreatCell is { } upper
            ? await _battles.LockAsync(Math.Max(_worldGridCellId, upper), warpNumber, cancellationToken)
            : null;
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED",type,"艦の情報を再読み込みしてください");
        if (IsRecoveringFromInjury || !_tacticalEncounter.HasSurvivors(_worldGridUnitId))
            return RejectCommandVisibly("TACTICAL_UNIT_DESTROYED",type,"この艦隊はワープできません");

        var decision = OriginalTacticalCommandAuthority.AuthorizeWarp(
            command,
            _worldGridUnitId,
            _worldGridCellId,
            _worldGridBaseId,
            mode: 0,
            authorityTick: _gameClock.Tick);
        if (!decision.Accepted || decision.Notification is null)
        {
            return RejectCommandVisibly(
                decision.ErrorCode ?? "TACTICAL_WARP_REJECTED",
                type,
                "この艦隊はワープできません");
        }

        // ORIGINAL_STATIC 004C187E, native E027: character PowerWarp >= 50
        // enables the ship's warp field. Check the accepted 040C authority
        // state, not a client flag or the initial allocation. This is only
        // one prerequisite; preparation/radius/destination remain separate.
        var warpPower = CurrentPlayerCorps.PowerWarp;
        if (warpPower < 50)
        {
            return RejectCommandVisibly("TACTICAL_WARP_POWER_INSUFFICIENT", type,
                "ワープ出力を最大にしてください");
        }

        var sourceCell = _worldGridCellId;
        if (retreatCell is not { } destinationCell ||
            _persistedGridUnit is not OriginalGridUnitRecord persistedUnit)
        {
            _receipt.Record(
                "tactical-warp",
                FormattableString.Invariant(
                    $"accepted;unit={_worldGridUnitId};grid={_worldGridCellId};base={_worldGridBaseId}"),
                _accountId);
            var held = EncodeApplicationResponse(
                OriginalTacticalCommandCodec.EncodeWarpedNotification(decision.Notification.Value),
                type,
                includeLobbyPrefix: true);
            return held with
            {
                AdditionalResponses =
                [
                    EncodeApplicationPush(EncodeTacticalBattlefieldUnits()),
                    EncodeApplicationPush(
                        OriginalSystemSceneCodec.EncodeTacticalUnitShips(CurrentTacticalBattlefield())),
                ],
                ResponseMetadata = FormattableString.Invariant(
                    $"tactical-warp-accepted;unit={_worldGridUnitId};grid={_worldGridCellId};base={_worldGridBaseId};design=new")
            };
        }

        // The withdrawal itself is a grid movement, so it takes the same
        // authority transition and the same durable, idempotent write as a
        // strategic warp. Cruising falls by the warp cost either way.
        sourceCell = persistedUnit.CurrentCellId;
        var gridCommand = new OriginalMoveGridAuthorityCommand(
            UnitId: _worldGridUnitId,
            AuthorityCardId: persistedUnit.AuthorityCardId,
            SourceCellId: sourceCell,
            DestinationCellId: destinationCell,
            Action: OriginalMoveGridAuthority.MinimalWorldWarpAction)
        {
            // A withdrawal returns to the character's own base, so the unit
            // belongs to it on arrival; arriving beside it in open space would
            // leave the client refusing 態勢変更 there too.
            DestinationBaseId = retreatBase,
        };
        var gridDecision = OriginalMoveGridAuthority.Transition(
            new OriginalMoveGridAuthorityState(
                persistedUnit.UnitId,
                persistedUnit.AuthorityCardId,
                persistedUnit.CurrentCellId,
                persistedUnit.BaseId,
                persistedUnit.Cruising),
            gridCommand);
        if (gridDecision.Status != OriginalMoveGridAuthorityStatus.Allowed)
        {
            return RejectCommandVisibly(
                gridDecision.ErrorCode ?? "TACTICAL_WARP_REJECTED", type, "この艦隊はワープできません");
        }

        var retreatFingerprint = Convert.ToHexStringLower(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(
            FormattableString.Invariant(
                $"original-tactical-warp/v1|{_moveGridRequestScope:N}|{sequence}|{Convert.ToHexString(payload)}"))));
        OriginalMoveGridStoreResult retreatStored;
        try
        {
            retreatStored = await _store.MoveOriginalGridUnitAsync(
                _accountId,
                new OriginalMoveGridWrite(
                    retreatFingerprint,
                    _worldCharacterId,
                    gridCommand.UnitId,
                    gridCommand.AuthorityCardId,
                    persistedUnit.CurrentCellId,
                    gridCommand.SourceCellId,
                    gridCommand.DestinationCellId,
                    gridCommand.Action)
                {
                    DestinationBaseId = gridCommand.DestinationBaseId,
                },
                cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.tactical-warp.persistence-not-supported", type);
        }

        if (retreatStored.Status == OriginalMoveGridStoreStatus.Replayed)
            return Invalid("original.tactical-warp.replay", type);
        if (retreatStored.Status != OriginalMoveGridStoreStatus.Moved || retreatStored.Unit is null)
        {
            return RejectCommandVisibly(
                retreatStored.ErrorCode ?? "TACTICAL_WARP_CONFLICT", type, "この艦隊はワープできません");
        }

        var retreatedUnit = retreatStored.Unit;
        if (retreatedUnit.CharacterId != _worldCharacterId ||
            retreatedUnit.UnitId != gridDecision.State.UnitId ||
            retreatedUnit.AuthorityCardId != gridDecision.State.AuthorityCardId ||
            retreatedUnit.CurrentCellId != gridDecision.State.CellId ||
            retreatedUnit.BaseId != gridDecision.State.BaseId ||
            retreatedUnit.Cruising != gridDecision.State.Cruising ||
            retreatedUnit.ShipGeneration != persistedUnit.ShipGeneration)
        {
            return Invalid("original.tactical-warp.persisted-state-shape", type);
        }

        ApplyPersistedGridUnit(retreatedUnit);
        // Arrival becomes visible before either lease is released, so nothing
        // can observe the unit on both grids at once.
        PublishOwnParticipantSnapshot();
        _receipt.Record(
            "tactical-warp",
            FormattableString.Invariant(
                $"retreat;unit={_worldGridUnitId};source-cell={sourceCell};destination-cell={retreatedUnit.CurrentCellId};base={_worldGridBaseId}"),
            _accountId);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeWarpedNotification(
                decision.Notification.Value),
            type,
            includeLobbyPrefix: true);
        var retreatFrames = new List<byte[]>
        {
            EncodeTacticalBattlefieldUnits(),
            OriginalSystemSceneCodec.EncodeTacticalUnitShips(CurrentTacticalBattlefield()),
            OriginalTacticalCommandCodec.EncodeNotifyTactics(
                IsCurrentTacticalFieldActive ? (byte)1 : (byte)0, _worldGridCellId),
        };
        RecordTacticalCommand(type, _gameClock.Tick);
        return response with
        {
            AdditionalResponses = retreatFrames.Select(frame => EncodeApplicationPush(frame)).ToArray(),
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-warp-retreated;unit={_worldGridUnitId};source-cell={sourceCell};destination-cell={retreatedUnit.CurrentCellId};base={_worldGridBaseId};authority-version={retreatStored.AuthorityVersion};design=new")
        };
    }

    /// <summary>
    /// 旋回. The native widget sends 0x0401 with the unit's current and
    /// requested headings. Rejecting the type used to close the session, so an
    /// unimplemented tactical command ended the player's game.
    /// </summary>
    private async Task<NaturalAuthoritySessionResult> ProcessTacticalTurnShipAsync(
        byte[] payload, ushort type, CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeTurnShipCommand(payload, out var command))
        {
            return RejectCommandVisibly("original.tactical-turn-ship.request-shape", type,
                "この命令は実行できません");
        }
        if (!_worldEntered || _worldCharacterId == 0 || _worldGridUnitId == 0)
        {
            return RejectCommandVisibly("original.tactical-turn-ship.world-not-entered", type,
                "この命令は実行できません");
        }
        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);

        using var turnLease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED", type, "艦の情報を再読み込みしてください");
        if (!_tacticalEncounter.HasSurvivors(_worldGridUnitId))
            return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;
        // Only the player's own single unit, exactly as the target/attack path.
        // Commanding another controller's unit is not authorized here.
        if (command.Units.Count != 1 || command.Units[0].UnitId != _worldGridUnitId)
            return RejectCommandVisibly("TACTICAL_UNIT_NOT_CONTROLLED", type, "この艦隊は指揮できません");

        var current = _tacticalUnitShip ?? OriginalSystemSceneCodec.CreateTacticalBattlefield(
            _worldGridUnitId, _worldCharacterId, CurrentBattlefieldTemplate()).Records[0];
        // NEW_DESIGN: the heading is applied immediately. The original turn
        // rate and its arrival timing are not recovered, so this is an
        // instantaneous authority change, not original turn kinematics.
        _tacticalUnitShip = current with { Direction = command.Units[0].To };
        PublishOwnParticipantSnapshot();
        _receipt.Record("tactical-turn-ship", FormattableString.Invariant(
            $"accepted;unit={_worldGridUnitId};from={current.Direction};to={command.Units[0].To}"), _accountId);
        RecordTacticalCommand(type, _gameClock.Tick);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload), type, includeLobbyPrefix: true);
        return response with
        {
            AdditionalResponses =
            [
                EncodeApplicationPush(OriginalSystemSceneCodec.EncodeTacticalUnitShips(
                    CurrentTacticalBattlefield())),
                EncodeApplicationPush(OriginalTacticalCommandCodec.EncodeTurnedNotification(
                    new(_gameClock.Tick, _worldGridUnitId, command.Units[0].To))),
            ],
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-turn-ship-accepted;unit={_worldGridUnitId};direction={command.Units[0].To};design=new")
        };
    }

    private async Task<NaturalAuthoritySessionResult> ProcessTacticalMoveShipAsync(
        byte[] payload,
        ushort type,
        CancellationToken cancellationToken)
    {
        if (!OriginalTacticalCommandCodec.TryDecodeMoveShipCommand(
                payload,
                type,
                out var command))
        {
            return RejectCommandVisibly("original.tactical-move-ship.request-shape", type,
                "この命令は実行できません");
        }
        // 平行移動 is 移動 without the turn: constmsg group 0 row 12 says
        // 「選択ユニットを平行移動する」 against row 4's 「移動させる」, and the two commands
        // share one wire body. So the destination is applied and the heading is
        // left exactly as it was.
        var keepHeading = type == OriginalTacticalCommandCodec.ParallelMoveShipCommandType;

        if (!_worldEntered || _worldCharacterId == 0 || _worldGridUnitId == 0)
        {
            return RejectCommandVisibly("original.tactical-move-ship.world-not-entered", type,
                "この命令は実行できません");
        }

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null)
        {
            return Invalid(restoreError, type);
        }

        using var moveLease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED",type,"艦の情報を再読み込みしてください");
        if (!_tacticalEncounter.HasSurvivors(_worldGridUnitId))
            return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");
        if (TacticalScheduleRefusal(type, _gameClock.Tick) is { } scheduleRefusal) return scheduleRefusal;

        // Resolve the same map spawn as normal scene projection, but do not
        // publish/cache new state before this command passes authorization.
        var current = _tacticalUnitShip ??
            OriginalSystemSceneCodec.CreateTacticalBattlefield(
                _worldGridUnitId,
                _worldCharacterId,
                CurrentBattlefieldTemplate()).Records[0];
        var decision = OriginalTacticalCommandAuthority.AuthorizeMoveShip(
            command,
            _worldGridUnitId,
            current);
        if (!decision.Accepted || decision.State is null)
        {
            return RejectCommandVisibly(
                decision.ErrorCode ?? "TACTICAL_MOVE_SHIP_REJECTED",
                type,
                "この艦隊は移動できません");
        }

        _tacticalUnitShip = keepHeading
            ? decision.State.Value with { Direction = current.Direction }
            : decision.State.Value;
        PublishOwnParticipantSnapshot();
        // E074/E076: 033B leaves the native one-waypoint path authorized only
        // through its translation. A nonnegative route equal to waypoint count
        // authorizes its final rotation (004BF870 -> 004CA620), without the
        // negative-route branch rebuilding the path. This grants the accepted
        // route; it does NOT report that the ship has arrived. The current
        // authority still lacks a shared, time-evolving movement state.
        var routeAuthorization = OriginalTacticalCommandCodec.EncodeMovedShipNotification(
            new(_gameClock.Tick, _worldGridUnitId, _tacticalUnitShip.Value.Direction,
                _tacticalUnitShip.Value.X, _tacticalUnitShip.Value.Y, _tacticalUnitShip.Value.Z,
                checked((sbyte)command.Destinations.Count)));
        _receipt.Record(
            "tactical-move-ship",
            FormattableString.Invariant(
                $"accepted;unit={_worldGridUnitId};x={_tacticalUnitShip.Value.X};y={_tacticalUnitShip.Value.Y};z={_tacticalUnitShip.Value.Z};direction={_tacticalUnitShip.Value.Direction};velocity={command.Velocity}"),
            _accountId);
        RecordTacticalCommand(type, _gameClock.Tick);
        var response = EncodeApplicationResponse(
            OriginalTacticalCommandCodec.EncodeCommandEcho(payload),
            type,
            includeLobbyPrefix: true);
        return response with
        {
            AdditionalResponses =
            [
                EncodeApplicationPush(
                    OriginalSystemSceneCodec.EncodeTacticalUnitShips(
                        CurrentTacticalBattlefield())),
                // Must follow 033B: its route-zero import must not replace the
                // final-segment authorization we are delivering here.
                EncodeApplicationPush(routeAuthorization),
            ],
            ResponseMetadata = FormattableString.Invariant(
                $"tactical-{(keepHeading ? "parallel-move" : "move")}-ship-accepted;unit={_worldGridUnitId};x={_tacticalUnitShip.Value.X};y={_tacticalUnitShip.Value.Y};z={_tacticalUnitShip.Value.Z};direction={_tacticalUnitShip.Value.Direction};design=new")
        };
    }

    private async Task<NaturalAuthoritySessionResult> ProcessTacticalControlAsync(
        byte[] payload, CancellationToken cancellationToken)
    {
        const ushort type = OriginalTacticalControlCodec.CommandType;
        if (!_worldEntered || !OriginalTacticalControlCodec.TryDecode(payload, out var command))
            return RejectCommandVisibly("TACTICAL_CONTROL_INVALID", type, "出力配分を変更できません");

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null)
            return RejectCommandVisibly(restoreError, type, "部隊を確認できません");

        using var controlLease=await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number,cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED",type,"艦の情報を再読み込みしてください");
        if (!_tacticalEncounter.HasSurvivors(_worldGridUnitId))
            return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");
        var beforeControl=CurrentPlayerCorps;
        var decision = OriginalTacticalControlCodec.Apply(command, _worldGridUnitId,
            OriginalAuthoredPlayableCatalog.TacticalTotalPower,
            beforeControl);
        if (!decision.Accepted)
            return RejectCommandVisibly(decision.ErrorCode!, type, "出力配分を変更できません");

        var commitSubordinates=_battles.PrepareControlledCorpsUpdate(_worldGridCellId,decision.State!.Value);
        if(_store is IOriginalFleetUnitStoreProvider provider &&
            !await provider.FleetUnits.SavePlayerCorpsAsync(_accountId,_worldGridUnitId,CurrentShipGeneration,
                beforeControl,decision.State.Value,cancellationToken))
            return RejectCommandVisibly("TACTICAL_CONTROL_SAVE_CONFLICT",type,"艦の情報を再読み込みしてください");
        _tacticalPlayerCorps = decision.State;
        _battles.RecordPlayerControl(_worldGridUnitId, CurrentShipGeneration, decision.State!.Value);
        commitSubordinates();
        // ORIGINAL_STATIC: request-kind 0x36 expects a 0x040C command response.
        // NEW DESIGN: apply immediately after persistence for PostgreSQL stores.
        // Recharge simulation is not implemented by this distribution path.
        return EncodeApplicationResponse(OriginalTacticalControlCodec.EncodeImmediateResponse(payload),
            type, includeLobbyPrefix: true) with
        {
            ResponseMetadata = $"tactical-control-accepted;unit={command.UnitId};beam={command.Beam};gun={command.Gun};engine={command.Engine};warp={command.Warp};sensor={command.Sensor};design=new",
            AdditionalResponses =
            [
                EncodeApplicationPush(OriginalSystemSceneCodec.EncodeTacticalCorps(CurrentTacticalCorps())),
            ],
        };
    }

    private async Task<NaturalAuthoritySessionResult> ProcessTacticalRelayAsync(
        byte[] payload,
        ushort type,
        CancellationToken cancellationToken)
    {
        if (!_worldEntered || _worldGridUnitId == 0)
        {
            return RejectCommandVisibly("original.tactical-command.world-not-entered", type,
                "この命令は実行できません");
        }

        var restoreError = await RestorePersistedGridUnitAsync(cancellationToken);
        if (restoreError is not null)
        {
            if (restoreError == "original.flagship.scene-refresh-required")
                return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED",type,"艦の情報を再読み込みしてください");
            return Invalid(restoreError, type);
        }

        using var battleLease = await _battles.LockAsync(_worldGridCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
        if (!HasCurrentShipIncarnation)
            return RejectCommandVisibly("SHIP_SCENE_REFRESH_REQUIRED",type,"艦の情報を再読み込みしてください");
        var appliedAt = _gameClock.Tick;
        if (TacticalScheduleRefusal(type, appliedAt) is { } relayRefusal) return relayRefusal;
        OriginalTacticalTargetDecision authorization;
        string commandName;
        OriginalTacticalAttackedNotification? attackedNotification = null;
        byte weaponSelection = 0;
        byte targetKind = 1;
        OriginalTacticalParticipantSnapshot? targetParticipant = null;
        switch (type)
        {
            case OriginalTacticalCommandCodec.AttackShipCommandType:
                if (!OriginalTacticalCommandCodec.TryDecodeAttackShipCommand(
                        payload,
                        out var attack))
                {
                    return RejectCommandVisibly("original.tactical-attack-ship.request-shape", type,
                "この命令は実行できません");
                }
                authorization = OriginalTacticalCommandAuthority.AuthorizeAttackCommand(
                    attack.UnitIds,
                    _worldGridUnitId,
                    attack.TargetId,
                    CurrentAutomaticAttackTarget(),
                    attack.Kind);
                // The client names its own modes: 一斉攻撃 / 連続攻撃 / 攻撃停止.
                commandName = OriginalAttackModeCatalog.NameOf(attack.Kind) is { } mode
                    ? $"attack-ship-kind-{attack.Kind}-{mode}"
                    : $"attack-ship-kind-{attack.Kind}";
                break;
            case OriginalTacticalCommandCodec.ShootShipCommandType:
                if (!OriginalTacticalCommandCodec.TryDecodeShootShipCommand(
                        payload,
                        out var shoot))
                {
                    return RejectCommandVisibly("original.tactical-shoot-ship.request-shape", type,
                "この命令は実行できません");
                }
                authorization = OriginalTacticalCommandAuthority.AuthorizeTargetCommand(
                    shoot.UnitIds,
                    _worldGridUnitId,
                    shoot.TargetId);
                commandName = "shoot-ship";
                weaponSelection = shoot.Arms;
                targetKind = shoot.TargetKind;
                break;
            case OriginalTacticalCommandCodec.StopCommandType:
                if (!OriginalTacticalCommandCodec.TryDecodeStopCommand(
                        payload,
                        out var stop))
                {
                    return RejectCommandVisibly("original.tactical-stop.request-shape", type,
                "この命令は実行できません");
                }
                authorization = stop.UnitIds.Count == 1 &&
                    stop.UnitIds[0] == _worldGridUnitId &&
                    stop.BaseIds.All(id =>
                        ActiveBattlefieldCatalog.ProjectTacticalBases(_worldGridCellId, [id]).Count == 1)
                    ? new OriginalTacticalTargetDecision(true, null)
                    : new OriginalTacticalTargetDecision(
                        false,
                        "TACTICAL_UNIT_NOT_CONTROLLED");
                commandName = "stop";
                break;
            default:
                return Invalid("original.tactical-command.unhandled", type);
        }

        if (!authorization.Accepted)
        {
            return RejectCommandVisibly(
                authorization.ErrorCode ?? "TACTICAL_COMMAND_REJECTED",
                type,
                "この戦術命令は実行できません");
        }

        if (!_tacticalEncounter.HasSurvivors(_worldGridUnitId))
            return RejectCommandVisibly("TACTICAL_ACTOR_DESTROYED", type, "この艦隊は撃破されています");
        if (authorization.TargetId != 0 && type is
            (OriginalTacticalCommandCodec.AttackShipCommandType or OriginalTacticalCommandCodec.ShootShipCommandType))
        {
            var npc = authorization.TargetId == OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId;
            targetParticipant = npc ? null : OtherBattleParticipants().FirstOrDefault(p => p.Unit.Id == authorization.TargetId);
            if (targetKind != 1 || (npc && !HasPrimaryTacticalNpc) || (!npc && targetParticipant is null))
                return RejectCommandVisibly("TACTICAL_TARGET_UNAVAILABLE", type, "目標を確認できません");
            var targetPower = npc ? CurrentBattlefieldTemplate().EnemyPower : targetParticipant!.Power;
            // Current faction IDs, not a complete diplomacy/camp rule engine.
            var targetCamp = npc ? (byte)0 : targetParticipant!.Camp;
            if (_createdCharacter is OriginalCreateCharacterCommand actor && actor.Power == targetPower && targetCamp == 0)
                return RejectCommandVisibly("TACTICAL_TARGET_FRIENDLY", type, "味方を攻撃できません");
            if (!_tacticalEncounter.HasSurvivors(authorization.TargetId))
                return RejectCommandVisibly("TACTICAL_TARGET_DESTROYED", type, "目標はすでに撃破されています");
            var arms = OriginalTacticalCommandAuthority.ResolveShotArms(
                weaponSelection, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities);
            if (arms is null)
                return RejectCommandVisibly("TACTICAL_WEAPON_NOT_EQUIPPED", type, "使用できる兵装がありません");
            var firingCorps = CurrentPlayerCorps;
            // Match the temporary NPC power prerequisite. This is not a
            // recovered recharge curve or a substitute for range/cooldown checks.
            var weaponPower = weaponSelection == 0 ? firingCorps.PowerBeam : firingCorps.PowerGun;
            if (weaponPower == 0)
                return RejectCommandVisibly("TACTICAL_WEAPON_POWER_INSUFFICIENT", type, "兵装への出力配分が不足しています");
            var positions = CurrentTacticalBattlefield(includeDefeated: true);
            var firingShip = positions.Records[0];
            var targetShip = npc ? positions.Records[1] : targetParticipant!.Ship;
            var rangeTable = (_staticArms ?? OriginalAuthoredPlayableCatalog.TacticalArms).EncodeResponse();
            var maximumRange = 0;
            if (arms.Value < 27)
                for (var bin = 7; bin >= 0; bin--)
                    if (BinaryPrimitives.ReadInt16BigEndian(rangeTable.AsSpan(6 + (arms.Value * 8 + bin) * 2)) > 0)
                    { maximumRange = bin + 1; break; }
            // Wire X/Y are the original render X/Z plane (004C4240).
            // Match the original strict outer range boundary. The authored
            // curve is not an original probability or damage calculation.
            var dx = (double)targetShip.X - firingShip.X;
            var dy = (double)targetShip.Y - firingShip.Y;
            var distance = Math.Sqrt(dx * dx + dy * dy);
            if (!double.IsFinite(distance) || distance >= maximumRange)
                return RejectCommandVisibly("TACTICAL_TARGET_OUT_OF_RANGE", type, "目標が射程外です");
            var capabilities = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities;
            var angleMask = weaponSelection switch
            {
                0 => capabilities.BeamAngleMask,
                1 => capabilities.GunAngleMask,
                _ => capabilities.MissileAngleMask,
            };
            // Same six-sector model as the NPC, grounded in004F1180/E074.
            // Reject degenerate/nonfinite bearings instead of treating them
            // as an arbitrary valid sector. This is not x87 edge emulation.
            if (distance == 0 || !float.IsFinite(firingShip.Direction) ||
                (angleMask & (1 << OriginalTacticalNpcController.Sector(
                    MathF.Atan2((float)dx,(float)dy),firingShip.Direction))) == 0)
                return RejectCommandVisibly("TACTICAL_TARGET_OUTSIDE_WEAPON_ARC", type, "目標が兵装の射界外です");
            if (_battles.IsPlayerWeaponRecharging(_worldGridUnitId, CurrentShipGeneration, appliedAt))
                return RejectCommandVisibly("TACTICAL_WEAPON_RECHARGING", type, "兵装の再充填中です");
            var damage = OriginalTacticalCommandAuthority.ApplyAuthoredDamage(
                _tacticalEncounter.GetUnitDamage(authorization.TargetId),
                _tacticalEncounter.UnitNumber(authorization.TargetId));
            if (targetParticipant is not null)
                targetParticipant = new(_tacticalEncounter.ProjectUnit(targetParticipant.Unit),
                    targetParticipant.Ship, targetParticipant.Corps, targetParticipant.CharacterFrame.ToArray(),
                    targetParticipant.Power,targetParticipant.ShipGeneration,targetParticipant.Outfit,
                    targetParticipant.CommanderMerit);
            await _battles.CommitUnitDamageAsync(_worldGridCellId,
                OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number,
                authorization.TargetId,damage,cancellationToken,targetParticipant?.ShipGeneration ?? 0);
            _battles.RecordPlayerShot(_worldGridUnitId, CurrentShipGeneration, appliedAt);
            MarkOwnNpcSceneImported(acceptedAttack: true);
            var morale = npc ? CurrentTacticalBattlefield(includeDefeated: true).Records[1].Morale : targetParticipant!.Ship.Morale;
            attackedNotification = OriginalTacticalCommandAuthority.CreateDamageNotification(
                appliedAt, _worldGridUnitId, arms.Value, targetKind, authorization.TargetId, damage, morale);
        }

        _receipt.Record(
            "tactical-command",
            $"accepted;name={commandName};unit={_worldGridUnitId};enemyDamage={_tacticalEncounter.EnemyDamage.Damaged};enemyDestroyed={_tacticalEncounter.EnemyDamage.Destroyed}",
            _accountId);
        var commandResponse = OriginalTacticalCommandCodec.EncodeCommandEcho(payload);
        if (type is OriginalTacticalCommandCodec.AttackShipCommandType or
            OriginalTacticalCommandCodec.ShootShipCommandType)
        {
            // ORIGINAL_STATIC 004B8B00: attack/shoot execute at Time+Wait;
            // 0426 executes at Time. 004B4110 supplies client-clock Time,
            // not an authoritative execution timestamp. NEW DESIGN: this
            // encounter currently applies damage immediately, so its reply
            // and damage event share the application tick, with no wait.
            var timing = commandResponse.AsSpan(OriginalLoginCodec.MessageCodeSize + sizeof(ushort));
            BinaryPrimitives.WriteUInt32BigEndian(timing, appliedAt);
            timing.Slice(sizeof(uint), sizeof(uint)).Clear();
        }
        if (type == OriginalTacticalCommandCodec.StopCommandType && _worldGridUnitId != 0)
            _battles.ClearExecuting(_worldGridUnitId);
        RecordTacticalCommand(type, appliedAt);
        var accepted = EncodeApplicationResponse(
            commandResponse,
            type,
            includeLobbyPrefix: true);
        NaturalAuthorityPush[]? additionalResponses = null;
        if (attackedNotification is OriginalTacticalAttackedNotification attacked)
        {
            var damageFrames = OriginalTacticalCommandCodec.EncodeTacticalDamageUpdates(
                attacked,
                EncodeTacticalBattlefieldUnits(includeDefeated: true),
                OriginalSystemSceneCodec.EncodeTacticalUnitShips(CurrentTacticalBattlefield(includeDefeated: true)));
            var completionFrames = _createdCharacter is OriginalCreateCharacterCommand actor &&
                !OtherBattleParticipants().Any(p => p.IsHostileTo(actor.Power,0) && _tacticalEncounter.HasSurvivors(p.Unit.Id))
                ? _tacticalEncounter.TryComplete(_worldGridCellId, actor.Power, 0,
                    ActiveBattlefieldCatalog.ProjectBaseObjectives(_worldGridCellId),
                    npcIsHostile: HasPrimaryTacticalNpc && CurrentBattlefieldTemplate().EnemyPower != actor.Power)
                : Array.Empty<byte[]>();
            // Existing single connection owner encodes and writes all sequences:
            // final casualty projection precedes the once-only scene transition.
            additionalResponses = damageFrames.Concat(completionFrames)
                .Select(frame => EncodeApplicationPush(frame)).ToArray();
            // Other viewers have their own projections. Share the target's
            // casualty/transition and import only previously unseen combatants.
            _battles.Publish(_worldGridCellId, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number,
                PendingNotifications.Writer, new[] { damageFrames[0] }.Concat(completionFrames).ToArray(),
                _worldGridUnitId, EncodeCurrentActorEntry(), attacked.TargetId,
                targetParticipant is null ? null : EncodeParticipantEntry(targetParticipant));
        }
        return accepted with
        {
            AdditionalResponses = additionalResponses,
            ResponseMetadata =
                $"tactical-command-accepted;name={commandName};unit={_worldGridUnitId};enemy-damage={_tacticalEncounter.EnemyDamage.Damaged};enemy-destroyed={_tacticalEncounter.EnemyDamage.Destroyed};encounter-completed={_tacticalEncounter.IsCompleted};design=new;target-unit={attackedNotification?.TargetId ?? 0};target-damage={attackedNotification?.Damaged ?? 0};target-destroyed={attackedNotification?.Destroyed ?? 0}"
        };
    }

    /// <summary>
    /// Starts the command's own 実行待機時間. 停止 is the original's only command
    /// with a wait of 0, and it is also the only one the live client accepts
    /// without consuming its command permission byte, so it starts no wait here
    /// either.
    /// </summary>
    private void RecordTacticalCommand(ushort type, uint appliedAt)
    {
        if (_worldGridUnitId == 0) return;
        var duration = OriginalTacticalCommandTiming.DurationTicks(type);
        if (duration != 0)
            _battles.RecordExecuting(_worldGridUnitId, CurrentShipGeneration, unchecked(appliedAt + duration));
        if (IsWeaponCommand(type) || OriginalTacticalCommandTiming.WaitTicks(type) == 0) return;
        _battles.RecordCommand(_worldGridUnitId, type, CurrentShipGeneration, appliedAt);
    }

    /// <summary>
    /// True while the unit is still inside the 実行待機時間 its last accepted
    /// tactical command started. The wait is the original's own number for that
    /// command (<see cref="OriginalTacticalCommandTiming"/>), in G秒 = ticks;
    /// a command with no recovered schedule is never gated.
    /// </summary>
    private bool IsTacticalCommandWaiting(ushort type, uint appliedAt) =>
        _worldGridUnitId != 0 && !IsWeaponCommand(type) &&
        _battles.IsCommandWaiting(_worldGridUnitId, type, CurrentShipGeneration,
            appliedAt, OriginalTacticalCommandTiming.WaitTicks(type));

    /// <summary>
    /// True while the unit is still carrying out a command with a recovered
    /// 実行所要時間. 停止 - 「行動をキャンセルする」 - is never blocked by it and
    /// clears it instead.
    /// </summary>
    private bool IsTacticalCommandExecuting(ushort type, uint appliedAt) =>
        _worldGridUnitId != 0 && type != OriginalTacticalCommandCodec.StopCommandType &&
        _battles.IsExecuting(_worldGridUnitId, CurrentShipGeneration, appliedAt);

    /// <summary>
    /// The visible refusal for a command that cannot be issued yet, or null when
    /// it can. One place, so every tactical command answers the same way.
    /// </summary>
    private NaturalAuthoritySessionResult? TacticalScheduleRefusal(ushort type, uint appliedAt)
    {
        if (IsTacticalCommandExecuting(type, appliedAt))
            return RejectCommandVisibly(OriginalTacticalCommandTiming.ExecutingErrorCode, type,
                OriginalTacticalCommandTiming.ExecutingText);
        if (IsTacticalCommandWaiting(type, appliedAt))
            return RejectCommandVisibly(OriginalTacticalCommandTiming.WaitingErrorCode, type,
                OriginalTacticalCommandTiming.WaitingText);
        return null;
    }

    // 攻撃 and 射撃 are the same weapon: one recharge covers both, and it keeps
    // its own player-visible text 「兵装の再充填中です」.
    private static bool IsWeaponCommand(ushort type) =>
        type is OriginalTacticalCommandCodec.AttackShipCommandType or
            OriginalTacticalCommandCodec.ShootShipCommandType;

    private async Task<string?> RestorePersistedGridUnitAsync(
        CancellationToken cancellationToken, bool allowNewIncarnation = false)
    {
        if (_createdCharacter is null || _worldCharacterId == 0 || _worldGridUnitId == 0)
        {
            return null;
        }

        OriginalGridUnitRecord? persisted;
        try
        {
            persisted = await _store.FindOriginalGridUnitAsync(
                _accountId,
                _worldCharacterId,
                _worldGridUnitId,
                cancellationToken);
        }
        catch (NotSupportedException)
        {
            // Test and compatibility stores predating the movement slice keep
            // the initial authored projection. Actual authority stores must
            // return a persisted row or fail closed below.
            return null;
        }

        if (persisted is null)
        {
            return "original.move-grid.unit-unavailable";
        }

        if (persisted.CharacterId != _worldCharacterId ||
            persisted.UnitId != _worldGridUnitId ||
            persisted.AuthorityCardId != OriginalAuthoredPlayableCatalog.AuthorityCardId)
        {
            return "original.move-grid.persisted-state-shape";
        }

        if (_persistedGridUnit is not null && persisted.ShipGeneration != CurrentShipGeneration && !allowNewIncarnation)
            return "original.flagship.scene-refresh-required";
        using var incarnationLease=await _battles.LockAsync(persisted.CurrentCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number,cancellationToken);
        if (!_battles.ObserveShipGeneration(persisted.UnitId,persisted.ShipGeneration))
            return "original.flagship.scene-refresh-required";
        if (persisted.ShipGeneration != CurrentShipGeneration)
        {
            var character=(await _store.ListCharactersAsync(_accountId,cancellationToken))
                .SingleOrDefault(c=>c.CharacterId==persisted.CharacterId);
            if (character is null) return "original.flagship.character-unavailable";
            _createdCharacter=RestoreCharacter(character);
        }
        ApplyPersistedGridUnit(persisted);
        return null;
    }

    private OriginalTacticalUnitShipResponse CurrentTacticalBattlefield(bool includeDefeated = false,
        IReadOnlyList<OriginalTacticalParticipantSnapshot>? participants = null)
    {
        var battlefield = OriginalSystemSceneCodec.CreateTacticalBattlefield(
            _worldGridUnitId,
            _worldCharacterId,
            CurrentBattlefieldTemplate());
        _tacticalUnitShip ??= battlefield.Records[0];
        // Morale is persisted on the grid unit; the scene record is only its
        // projection, so it must never drift from the row the store holds.
        if (_persistedGridUnit is { } moraleSource && _tacticalUnitShip.Value.Morale != moraleSource.Morale)
            _tacticalUnitShip = _tacticalUnitShip.Value with { Morale = moraleSource.Morale };
        return new OriginalTacticalUnitShipResponse(ProjectCurrentParticipants(
            _tacticalUnitShip.Value,
            _battles.NpcSnapshot(_worldGridCellId, OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId)?.Ship ??
                battlefield.Records[1], includeDefeated)
            .Concat((participants ?? OtherBattleParticipants())
                .Select(p => IsCommandedSupportOutfit(p.Unit.Outfit)
                    ? p.Ship with { Character = _worldCharacterId }
                    : p.Ship)).ToArray());
    }

    /// <summary>
    /// Whether an outfit is one of the authored support vessels the player is
    /// meant to be able to command - the 工作艦 of 修理's description and the
    /// 補給艦 of 補給's.
    /// </summary>
    /// <remarks>
    /// EXPERIMENT, 2026-09-10, and labelled as one. The band that builds the
    /// tactical selection picks with mask 0x10581 (0x0050D9E9), and
    /// FUN_004F12B0 resolves that mask to a demand for node flag **0x0400** -
    /// carried by the player's own unit (0x00010481) and by no authored allied
    /// ship (0x00010901). See <c>evidence/band-mask-solved-v395.md</c>.
    ///
    /// The player's ship record and an authored fleet's come from the *same*
    /// builder, so the only thing that differs is what this authority puts in the
    /// record's <c>Character</c> word: the player's own character for his unit,
    /// the outfit id for a fleet. This serves the support vessels with the
    /// player's character instead, on the hypothesis that 0x0400 is the client
    /// marking 「a unit of the character I am logged in as」.
    ///
    /// It is a hypothesis, not a conclusion. Four earlier ones about this exact
    /// question were falsified by measurement, so this one is to be checked the
    /// same way: deploy, re-read the hit list, and confirm bit 0x0400 on
    /// 2113929483 **before** any mouse input.
    /// </remarks>
    private bool IsCommandedSupportOutfit(uint outfit) =>
        outfit != 0 &&
        (ActiveBattlefieldCatalog.OutfitCarriesRole(_worldGridCellId, outfit, "repair") ||
         ActiveBattlefieldCatalog.OutfitCarriesRole(_worldGridCellId, outfit, "supply"));

    private IReadOnlyList<byte[]>? EncodeCurrentActorEntry()
    {
        if (_createdCharacter is not OriginalCreateCharacterCommand character) return null;
        var ship = CurrentTacticalBattlefield(includeDefeated: true).Records[0];
        var corps = CurrentPlayerCorps;
        // ORIGINAL_STATIC E062: 0B0A imports the staged character/unit/ship data
        // and binds the new model. Never include the viewer or NPC in this set:
        // duplicate IDs reuse their slot but reinitialize its motion state.
        return [
            OriginalWorldEntryCodec.EncodeGridEnterBoundary(0x0B09),
            EncodeLocatedCharacter(_worldCharacterId, _worldGridUnitId, EffectiveWorldCardId, character),
            OriginalWorldEntryCodec.EncodeUnits([CurrentPlayerInformationUnit()]),
            OriginalSystemSceneCodec.EncodeTacticalCorps(new([corps])),
            OriginalTacticalShieldCodec.Encode([OriginalTacticalShieldCodec.CreateAuthoredInitialState(ship.Id)]),
            OriginalSystemSceneCodec.EncodeTacticalUnitShips(new([ship])),
            OriginalWorldEntryCodec.EncodeGridEnterBoundary(0x0B0A)
        ];
    }

    private OriginalInformationUnitProjection CurrentPlayerInformationUnit()
    {
        var cruising = _persistedGridUnit?.Cruising ?? OriginalMoveGridAuthority.MinimalWorldStartingCruising;
        var damage = _tacticalEncounter.GetUnitDamage(_worldGridUnitId);
        return new(_worldGridUnitId, _worldGridCellId, _worldGridBaseId, 100, damage.Damaged, damage.Destroyed,
            _persistedGridUnit?.Supplies ?? 100, 100, cruising,
            Kind: _createdCharacter?.FlagshipKind ?? 0,
            Mode: _persistedGridUnit?.Mode ?? 0, Outfit: _playerOutfitMembership?.Outfit.Id ?? 0);
    }

    private IReadOnlyList<byte[]> EncodeParticipantEntry(OriginalTacticalParticipantSnapshot participant) =>
        participant.EncodeEntry();

    private OriginalTacticalCorpsResponse CurrentTacticalCorps(
        IReadOnlyList<OriginalTacticalParticipantSnapshot>? participants = null) =>
        _worldCharacterId == 0
            ? new OriginalTacticalCorpsResponse(
                Array.Empty<OriginalTacticalCorpsRecord>())
            : new OriginalTacticalCorpsResponse(ProjectCurrentParticipants(
                CurrentPlayerCorps,
                OriginalSystemSceneCodec.CreatePlayableTacticalCorps(
                    OriginalAuthoredPlayableCatalog.TacticalEnemyCharacterId))
                .Concat((participants ?? OtherBattleParticipants()).Select(p => p.Corps))
                .DistinctBy(corps => corps.Id).ToArray());

    private uint CurrentAutomaticAttackTarget()
    {
        // Deterministic authored target choice, not an autonomous NPC AI tick.
        var power = _createdCharacter?.Power;
        if (HasPrimaryTacticalNpc && _tacticalEncounter.EnemyHasSurvivors &&
            (power is null || CurrentBattlefieldTemplate().EnemyPower != power))
            return OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId;
        return OtherBattleParticipants().FirstOrDefault(p => p.IsHostileTo(power ?? 0,0) &&
            _tacticalEncounter.HasSurvivors(p.Unit.Id))?.Unit.Id ?? 0;
    }

    private byte[] EncodeTacticalBattlefieldUnits(bool includeDefeated = false,
        IReadOnlyList<OriginalTacticalParticipantSnapshot>? participants = null)
    {
        var damage = _tacticalEncounter.EnemyDamage;
        return OriginalWorldEntryCodec.EncodeUnits(ProjectCurrentParticipants(
            CurrentPlayerInformationUnit(),
            new OriginalInformationUnitProjection(
                OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId,
                _worldGridCellId,
                0,
                100,
                damage.Damaged,
                damage.Destroyed,
                100,
                100,
                10,
                Kind: CurrentEnemyRegularShipKind()), includeDefeated)
            .Concat((participants ?? OtherBattleParticipants()).Select(p => _tacticalEncounter.ProjectUnit(p.Unit))).ToArray());
    }

    private IReadOnlyList<byte[]> EncodeBattlefieldCharacters(OriginalCreateCharacterCommand character,
        IReadOnlyList<OriginalTacticalParticipantSnapshot>? participants = null) =>
        ProjectCurrentParticipants(
            EncodeLocatedCharacter(_worldCharacterId, _worldGridUnitId, EffectiveWorldCardId, character),
            EncodeLocatedCharacter(OriginalAuthoredPlayableCatalog.TacticalEnemyCharacterId,
                OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId, 0, CreateTacticalEnemyCharacter(character)))
            .Concat((participants ?? OtherBattleParticipants()).Where(p => !p.CharacterFrame.IsEmpty)
                .Select(p => p.CharacterFrame.ToArray())).ToArray();

    private void AddFleetInformation(List<byte[]> frames,
        IReadOnlyList<OriginalTacticalParticipantSnapshot> participants)
    {
        var candidates = participants.Where(p => p.Outfit.HasValue).Select(p => p.Outfit!.Value)
            .Concat(_playerOutfitMembership is { } membership ? [membership.Outfit] : []);
        var groups = candidates.GroupBy(o => o.Id).ToArray();
        if (groups.Any(group => group.Distinct().Count() != 1))
            throw new InvalidDataException("OUTFIT_METADATA_CONFLICT");
        var outfits = groups.Select(group => group.First()).ToArray();
        if (outfits.Length > 0) frames.Add(OriginalInformationOutfitCodec.Encode(outfits));
    }

    private IReadOnlyList<byte[]> EncodeTacticalSceneBootstrapFrames(
        IReadOnlyList<OriginalTacticalParticipantSnapshot>? participants = null)
    {
        participants ??= OtherBattleParticipants();
        var battlefield = CurrentTacticalBattlefield(participants: participants);
        var template = CurrentBattlefieldTemplate();
        var catalog = ActiveBattlefieldCatalog;
        var frames = new List<byte[]>();
        frames.AddRange(catalog.EncodeBaseInformationFrames(_worldGridCellId));
        frames.Add(catalog.EncodeInstitutionFrame(_worldGridCellId));
        frames.AddRange(
        [
            OriginalSystemSceneCodec.EncodeTacticalCharacters(battlefield.Records.Select(ship => ship.Character).Distinct().ToArray()),
            OriginalSystemSceneCodec.EncodeTacticalCorps(CurrentTacticalCorps(participants)),
            OriginalTacticalShieldCodec.Encode(battlefield.Records.Select(ship =>
                OriginalTacticalShieldCodec.CreateAuthoredInitialState(ship.Id)).ToArray()),
            OriginalSystemSceneCodec.EncodeTacticalBases(
                new(catalog.ProjectTacticalBases(_worldGridCellId))),
            OriginalSystemSceneCodec.EncodeObstacles(
                template.ProjectObstacles(_worldGridCellId)),
            OriginalSystemSceneCodec.EncodeUnitPositions(battlefield),
        ]);
        frames.AddRange(catalog.EncodeBasePositionFrames(_worldGridCellId));
        return frames;
    }

    private OriginalBattlefieldTemplate CurrentBattlefieldTemplate() =>
        ActiveBattlefieldCatalog.Resolve(_worldGridCellId);

    private bool HasPrimaryTacticalNpc => !IsRecoveringFromInjury && (CurrentBattlefieldTemplate().SpawnEnemy ||
        _battles.NpcSnapshot(_worldGridCellId, OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId) is not null);

    private bool IsCurrentTacticalFieldActive
    {
        get
        {
            // NEW_DESIGN recovery adapter: the injured character is no longer a
            // combat participant. Only its own scene is strategic; other sessions'
            // battle state/notifications are never ended by this projection.
            if (IsRecoveringFromInjury) return false;
            // Also used when establishing a subscription: do not recursively
            // enter the subscription-producing _tacticalEncounter getter.
            var encounter = _battles.GetEncounter(_worldGridCellId,
                OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number);
            return !encounter.IsCompleted && (HasPrimaryTacticalNpc || OtherBattleParticipants().Any(p =>
                p.IsHostileTo(_createdCharacter?.Power ?? 0,0) && encounter.HasSurvivors(p.Unit.Id)));
        }
    }

    private byte[] EncodeCurrentGridState(ushort requestedGrid) =>
        OriginalWorldBootstrapCodec.EncodeInformationGrid(requestedGrid,
            requestedGrid == _worldGridCellId && IsCurrentTacticalFieldActive ? (byte)1 : (byte)0);

    private IReadOnlyList<T> ProjectCurrentParticipants<T>(T own, T primaryNpc, bool includeDefeated = false) =>
        HasPrimaryTacticalNpc ? _tacticalEncounter.ProjectParticipants(own, primaryNpc, includeDefeated) : [own];

    /// <summary>
    /// Give an authored fleet the character record the client needs to label it.
    /// </summary>
    /// <remarks>
    /// The tactical ship record carries no name - only a character id - and
    /// 0x0337 answers with ids alone, so a fleet the client cannot resolve to a
    /// character draws no label at all. Until this, only the viewer's own fleet
    /// and the primary NPC had one. The name is authored content; everything
    /// else in the projected record follows the same shape the primary NPC uses.
    /// </remarks>
    private OriginalTacticalParticipantSnapshot WithCommanderCharacter(
        OriginalTacticalParticipantSnapshot participant, OriginalBattlefieldFleet fleet)
    {
        var frame = CommanderFrame(fleet, participant.Unit.Id, participant.Ship.Character);
        if (frame is null) return participant;
        return new OriginalTacticalParticipantSnapshot(participant.Unit, participant.Ship,
            participant.Corps, frame, participant.Power, participant.ShipGeneration, participant.Outfit,
            participant.CommanderMerit);
    }

    /// <summary>
    /// The character frame that labels an authored fleet, or null when the content
    /// names no commander for it.
    /// </summary>
    internal byte[]? CommanderFrame(OriginalBattlefieldFleet? fleet, uint unitId, uint character)
    {
        if (fleet?.Commander is not { Length: > 0 } commander)
        {
            return null;
        }
        return EncodeLocatedCharacter(character, unitId, 0,
            OriginalAuthoredNpcProfiles.FleetCommander(fleet, commander));
    }

    private OriginalCreateCharacterCommand CreateTacticalEnemyCharacter(
        OriginalCreateCharacterCommand source) =>
        OriginalAuthoredNpcProfiles.Defaults() with
        {
            CharacterId = OriginalAuthoredPlayableCatalog.TacticalEnemyCharacterId,
            Rank = CurrentBattlefieldTemplate().EnemyRank,
            Achievement = CurrentBattlefieldTemplate().EnemyAchievement,
            Power = CurrentBattlefieldTemplate().EnemyPower ??
                throw new InvalidOperationException("Battlefield NPC affiliation is missing"),
            LastName = "敵艦隊",
            FirstName = string.Empty,
            // This authored NPC has no assigned ship name. Do not expose the
            // viewer's stored name when projecting the opposing character.
            FlagshipName = string.Empty,
            ReturnBaseId = 0,
            FlagshipType = 0,
            FlagshipKind = CurrentEnemyRegularShipKind(),
        };

    // AUTHORED selection: the user requested ordinary ships on both sides.
    // Original constmsg/thumbnail kind0 and89 identify the faction regular
    // hulls. NPC assignment belongs to field content, never the viewer's hull.
    private ushort CurrentEnemyRegularShipKind() => CurrentBattlefieldTemplate().EnemyPower switch
    {
        OriginalFaction.Empire => 0,
        OriginalFaction.Alliance => 89,
        // Preserve generic-kind0 compatibility for existing nonmilitary/pirate
        // field content; their canonical hull assignments are not recovered.
        0 or 1 or 4 => 0,
        _ => throw new InvalidOperationException("Battlefield NPC affiliation is missing or invalid"),
    };

    // LOGH7_WORLD_CARD_ID force-overrides the served card for probes; otherwise the persisted card is authoritative.
    private ushort EffectiveWorldCardId =>
        ushort.TryParse(Environment.GetEnvironmentVariable("LOGH7_WORLD_CARD_ID"), out var forced)
            ? forced
            : _worldCardId;

    private async Task LoadPersistedCardAsync(CancellationToken cancellationToken)
    {
        var cards = await _store.ListCharacterCardsAsync(_accountId, cancellationToken);
        var held = cards.FirstOrDefault(card => card.CharacterId == _worldCharacterId);
        _worldCardId = held is not null
            ? checked((ushort)held.CardId)
            : OriginalAuthoredPlayableCatalog.AuthorityCardId;
    }

    private async Task<string?> RestorePersistedCharacterAsync(
        CancellationToken cancellationToken)
    {
        if (_createdCharacter is not null)
        {
            return null;
        }

        var characters = await _store.ListCharactersAsync(_accountId, cancellationToken);
        if (characters.Count > 1)
        {
            if (_lobbySelectionValue is null)
            {
                return "original.character.selection.multiple-not-instrumented";
            }

            var selected = ResolveSelectedCharacter(characters, _lobbySelectionValue.Value);
            if (selected is null)
            {
                return "original.character.selection.unavailable";
            }

            _worldCharacterId = checked((uint)selected.CharacterId);
            _worldGridUnitId = _worldCharacterId;
            _createdCharacter = RestoreCharacter(selected);
            await LoadPersistedCardAsync(cancellationToken);
            _receipt.Record($"character-context", $"restored-slot-{selected.Slot};card={_worldCardId}", _accountId);
            return null;
        }

        if (characters.Count == 1)
        {
            var character = characters[0];
            _worldCharacterId = checked((uint)character.CharacterId);
            _worldGridUnitId = _worldCharacterId;
            _createdCharacter = RestoreCharacter(character);
            await LoadPersistedCardAsync(cancellationToken);
            _receipt.Record("character-context", $"restored-slot-0;card={_worldCardId}", _accountId);
        }

        return null;
    }

    private static CharacterReadRecord? ResolveSelectedCharacter(
        IReadOnlyList<CharacterReadRecord> characters,
        ushort selectionValue)
    {
        var selectedByCharacterId = characters.SingleOrDefault(
            character => character.CharacterId == selectionValue);
        if (selectedByCharacterId is not null)
        {
            return selectedByCharacterId;
        }

        var legacySlot = checked((short)(selectionValue - 1));
        return characters.SingleOrDefault(character => character.Slot == legacySlot);
    }

    private bool IsEligibleForAuthoredOrderSuggestCard(CharacterReadRecord selected) =>
        _worldEntered &&
        _createdCharacter is not null &&
        selected.CharacterId == _worldCharacterId &&
        selected.Rank is > 0 and <= OriginalAuthoredPlayableCatalog.StartingRank;

    private static string OrderSuggestDisplayName(CharacterReadRecord character)
    {
        const int maximumCharacters = 13;
        var combined = $"{character.FirstName}・{character.LastName}";
        if (combined.Length <= maximumCharacters)
        {
            return combined;
        }

        var fallback = string.IsNullOrEmpty(character.LastName)
            ? character.FirstName
            : character.LastName;
        return fallback.Length <= maximumCharacters
            ? fallback
            : fallback[..maximumCharacters];
    }

    private OriginalCreateCharacterCommand RestoreCharacter(CharacterReadRecord character)
    {
        var restored = ProjectStoredCharacter(character);
        _worldPcp = character.Pcp;
        _worldMcp = character.Mcp;
        return restored;
    }

    // Pure projection also used for another fleet's persisted controller.
    // Reading that character must not change the viewing session's balances.
    private static OriginalCreateCharacterCommand ProjectStoredCharacter(CharacterReadRecord character) =>
        OriginalStoredCharacterProjection.Create(character);

    private NaturalAuthoritySessionResult ProcessSessionServerLogin(
        OriginalClientInnerFrameDecodeResult decoded,
        ushort? type)
    {
        var sessionLogin = OriginalSessionServerCodec.DecodeLogin(decoded.Payload!);
        if (!sessionLogin.Success ||
            !TryNormalizeLogin(sessionLogin.Message!.Value.AccountElements, out var normalized))
        {
            return Invalid(sessionLogin.ErrorCode ?? "original.session-server.login.account");
        }

        _clientSequenceBaseline = decoded.Sequence;
        var connectionToken = _pendingHandoffToken;
        var loginToken = sessionLogin.Message.Value.HandoffToken;
        var acceptedByWireToken = TryConsumeSessionHandoff(
            connectionToken, normalized, out _accountId, out var selectionValue);
        if (!acceptedByWireToken)
        {
            acceptedByWireToken = TryConsumeSessionHandoff(
                loginToken, normalized, out _accountId, out selectionValue);
        }
        if (!acceptedByWireToken &&
            !_handoffs.TryConsumeOnlyOutstandingForLogin(
                normalized, out _accountId, out selectionValue))
        {
            _receipt.Record("session-server-handoff", "rejected");
            return Invalid("original.session-server.handoff.rejected");
        }

        _pendingHandoffToken = 0;
        _lobbySelectionValue = selectionValue;
        _normalizedLogin = normalized;
        _receipt.Record(
            "session-server-handoff",
            acceptedByWireToken ? "accepted-token" : "accepted-only-account-bound",
            _accountId);
        State = NaturalAuthoritySessionState.SessionServerReady;
        // Experiment: choose the SSLoginOK leading 4-byte code by env var so several candidates can be tried
        // without a rebuild. LOGH7_SS_LOGINOK: unset/"zero"=stub (current), "token"=client HandoffToken echo,
        // "conn"=pending wire token, "sel"=session selection value, "one"=0x00000001.
        var loginOkCode = (Environment.GetEnvironmentVariable("LOGH7_SS_LOGINOK") ?? "zero") switch
        {
            "token" => loginToken,
            "conn" => connectionToken,
            "sel" => (uint)(_lobbySelectionValue ?? 0),
            "one" => 1u,
            _ => 0u,
        };
        _receipt.Record("session-server-loginok-code", loginOkCode.ToString("X8"), _accountId);
        return EncodeApplicationResponse(
            loginOkCode == 0u
                ? OriginalSessionServerCodec.EncodeLoginOk()
                : OriginalSessionServerCodec.EncodeLoginOk(loginOkCode),
            type,
            includeLobbyPrefix: true);
    }

    private bool TryConsumeSessionHandoff(
        uint wireToken,
        string normalizedLogin,
        out Guid accountId,
        out ushort? selectionValue)
    {
        if (wireToken == 0)
        {
            accountId = Guid.Empty;
            selectionValue = null;
            return false;
        }

        if (_handoffs.TryConsume(
                wireToken, normalizedLogin, out accountId, out selectionValue))
        {
            return true;
        }

        // The original client returns the 0x200A token through its native
        // connection API as a host-order DWORD. Keep the handoff single-use
        // and account-bound while accepting that exact four-byte swap.
        return _handoffs.TryConsume(
            BinaryPrimitives.ReverseEndianness(wireToken),
            normalizedLogin,
            out accountId,
            out selectionValue);
    }

    private OriginalClientInnerFrameDecodeResult DecodeApplication(ReadOnlySpan<byte> payload) =>
        OriginalClientInnerFrameCodec.Decode(payload, _clientOutboundKey!, _clientSequenceBaseline);

    private NaturalAuthoritySessionResult EncodeApplicationResponse(
        ReadOnlySpan<byte> applicationPayload,
        ushort? observedType,
        bool includeLobbyPrefix,
        OriginalLoginInputShape? originalLoginInputShape = null)
    {
        var encrypted = OriginalClientInnerFrameCodec.Encode(
            applicationPayload, _serverOutboundKey, _nextServerApplicationSequence++);
        return Success(
            0x0030,
            encrypted,
            observedType,
            includeLobbyPrefix ? [0, 0, 0, 0] : null,
            originalLoginInputShape);
    }

    internal NaturalAuthorityPush EncodeApplicationPush(ReadOnlySpan<byte> applicationPayload)
    {
        var encrypted = OriginalClientInnerFrameCodec.Encode(
            applicationPayload,
            _serverOutboundKey,
            _nextServerApplicationSequence++);
        return new NaturalAuthorityPush(0x0030, [0, 0, 0, 0], encrypted);
    }

    private static ushort? ReadType(ReadOnlySpan<byte> payload) =>
        payload.Length >= sizeof(ushort) ? BinaryPrimitives.ReadUInt16BigEndian(payload) : null;

    private static bool TryNormalizeLogin(ReadOnlySpan<ushort> elements, out string normalized)
    {
        normalized = string.Empty;
        if (elements.IsEmpty || elements[^1] != 0 || elements[..^1].Contains((ushort)0) ||
            !LoginNamePolicy.TryNormalize(elements[..^1], out var value))
        {
            return false;
        }

        normalized = value;
        return true;
    }

    private static NaturalAuthoritySessionResult Success(
        ushort? outerControl,
        byte[]? payload,
        ushort? observedType,
        byte[]? prefix,
        OriginalLoginInputShape? originalLoginInputShape = null) =>
        new(NaturalAuthoritySessionStatus.Success, outerControl, prefix, payload, observedType, null, originalLoginInputShape);


    // AUTHORED_PLACEHOLDER texts (Japanese, CP932-compatible) until the original move-failure rows are identified.
    private static readonly IReadOnlyDictionary<string, (ushort Code, string Text)> MoveGridRejectionMessages =
        new Dictionary<string, (ushort, string)>(StringComparer.Ordinal)
        {
            ["MOVE_GRID_DESTINATION_NOT_LEGAL"] = (1, "指定グリッドにはワープできません"),
            ["MOVE_GRID_SOURCE_STALE"] = (2, "現在位置が更新されました。もう一度選択してください"),
            ["MOVE_GRID_CARD_NOT_AUTHORIZED"] = (3, "この職務権限カードではワープできません"),
            ["MOVE_GRID_ACTION_NOT_AUTHORIZED"] = (4, "このコマンドは実行できません"),
            ["MOVE_GRID_UNIT_NOT_OWNED"] = (5, "この部隊は指揮できません"),
            ["MOVE_GRID_COMMAND_POINTS_INSUFFICIENT"] = (6, "コマンドポイントが足りません"),
        };

    // Personnel commands (0x0704 family): a store rejection becomes the client's 0x0500 NotifyInvalidMessage with a
    // Japanese reason instead of a dropped connection (condition 7: visible rejection reasons). NEW_DESIGN text.
    // At most one settlement attempt per this interval. The grant is computed
    // from the stored timestamp, so a delayed attempt still pays everything
    // owed; this only keeps a polling client from opening a locking transaction
    // on every frame.
    private static readonly TimeSpan CommandPointSettlementInterval = TimeSpan.FromSeconds(30);
    private DateTimeOffset _commandPointsSettledAt = DateTimeOffset.MinValue;

    /// <summary>
    /// Applies whatever regeneration the authored policy owes this character.
    /// It is a passive effect: it advances no authority version, writes nothing
    /// when no whole interval has elapsed, and a store without the economy is
    /// simply left alone.
    /// </summary>
    /// <summary>
    /// The 0x0F1F the client needs to arm its tactical command UI, returned once
    /// per entry into an active tactical field. Null whenever the field is not
    /// active or the assertion has already been made for this grid.
    /// </summary>
    private byte[]? TacticalFieldNotificationToReassert()
    {
        if (!_worldEntered || _worldGridCellId == 0) return null;
        if (!IsCurrentTacticalFieldActive)
        {
            _tacticsNotifyAssertedGrid = 0;
            return null;
        }
        if (_tacticsNotifyAssertedGrid == _worldGridCellId) return null;
        _tacticsNotifyAssertedGrid = _worldGridCellId;
        return OriginalTacticalCommandCodec.EncodeNotifyTactics(1, _worldGridCellId);
    }

    private uint _tacticsNotifyAssertedGrid;

    private async Task SettleCommandPointRegenerationAsync(CancellationToken cancellationToken)
    {
        if (_worldCharacterId == 0) return;
        var now = _gameClock.Now;
        if (now - _commandPointsSettledAt < CommandPointSettlementInterval) return;
        _commandPointsSettledAt = now;
        try
        {
            var settled = await _store.AccrueCommandPointsAsync(_accountId, _worldCharacterId,
                CommandPointPolicy.Value, now, IsCurrentTacticalFieldActive, cancellationToken);
            if (!settled.Applied) return;
            _worldPcp = settled.Political;
            _worldMcp = settled.Military;
            _receipt.Record("command-points",
                FormattableString.Invariant($"regenerated;pcp={settled.Political};mcp={settled.Military}"),
                _accountId);
        }
        catch (NotSupportedException)
        {
            // Compatibility stores predating the economy keep their balances.
        }
    }

    /// <summary>
    /// The base a strategic arrival joins: the destination grid's own base of
    /// the player's side, in its own camp, that actually has a public flagship
    /// port. Zero when the grid has none, which arrives in open space.
    /// </summary>
    private uint ArrivalBaseId(uint grid)
    {
        var power = _createdCharacter?.Power ?? 0;
        foreach (var candidate in ActiveBattlefieldCatalog.Resolve(grid).ProjectBaseInformation(grid))
            if (candidate.Power == power && candidate.Camp == 0 &&
                FindPublicFlagshipPort(grid, candidate.Id) != 0)
                return candidate.Id;
        return 0;
    }

    private NaturalAuthoritySessionResult RejectCommandVisibly(string errorCode, ushort type, string text)
    {
        _receipt.Record("command-reject", $"type=0x{type:X4};{errorCode}", _accountId);
        var response = EncodeApplicationResponse(
            OriginalNotifyMessageCodec.EncodeInvalidMessage(0xFF, text),
            type,
            includeLobbyPrefix: true);
        return response with
        {
            ResponseMetadata = FormattableString.Invariant($"command-reject={errorCode};type=0x{type:X4};notify-invalid-message-error=255;design=new")
        };
    }

    private NaturalAuthoritySessionResult RejectMoveGridVisibly(string errorCode, ushort type)
    {
        var (code, text) = MoveGridRejectionMessages.TryGetValue(errorCode, out var known)
            ? known
            : ((ushort)0xFF, "コマンドは拒否されました");
        _receipt.Record("move-grid", $"rejected-{errorCode}", _accountId);
        var response = EncodeApplicationResponse(
            OriginalNotifyMessageCodec.EncodeInvalidMessage(code, text),
            type,
            includeLobbyPrefix: true);
        return response with
        {
            ResponseMetadata = FormattableString.Invariant($"move-grid-reject={errorCode};notify-invalid-message-error={code};design=new")
        };
    }

    private NaturalAuthoritySessionResult Invalid(
        string code,
        ushort? observedApplicationType = null,
        string? rejectedApplicationPayloadHex = null)
    {
        State = NaturalAuthoritySessionState.Rejected;
        return new NaturalAuthoritySessionResult(
            NaturalAuthoritySessionStatus.Invalid,
            null,
            null,
            null,
            observedApplicationType,
            code,
            null,
            rejectedApplicationPayloadHex);
    }
}
