using System.Buffers.Binary;
using System.Security.Cryptography;
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

public sealed class NaturalAuthoritySession
{
    private readonly byte[] _serverOutboundKey;
    private readonly uint _lobbyServerIpv4;
    private readonly uint _sessionServerIpv4;
    private readonly ushort _sessionServerPort;
    private readonly OriginalLoginAuthority _loginAuthority;
    private readonly HandoffRegistry _handoffs;
    private readonly IAccountStore _store;
    private readonly MetadataOnlyGatewayReceipt _receipt;
    private readonly string? _serverNotice;

    private byte[]? _clientOutboundKey;
    private byte[]? _lobbyCode;
    private uint _clientSequenceBaseline;
    private uint _nextServerApplicationSequence = 1;
    private uint _pendingHandoffToken;
    private Guid _accountId;
    private string? _normalizedLogin;
    private OriginalCreateCharacterCommand? _createdCharacter;
    // 2026-09-03: the character's CURRENT post card, loaded from original_character_card by
    // RestorePersistedCharacterAsync (falling back to the authored AuthorityCardId when no appointment row exists).
    // 辞任 (0x0709) writes card 0 = 個人 here, which the client renders as 「皇宮 ： 個人」 with no commands.
    private ushort _worldCardId = OriginalAuthoredPlayableCatalog.AuthorityCardId;
    private uint _worldCharacterId;
    private uint _worldGridUnitId;
    private uint _worldGridCellId = OriginalAuthoredPlayableCatalog.CurrentGridCell;
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
        string? serverNotice = null)
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
        _serverNotice = string.IsNullOrEmpty(serverNotice) ? null : serverNotice;
    }

    public NaturalAuthoritySessionState State { get; private set; }

    public Task<NaturalAuthoritySessionResult> ProcessAsync(
        ushort outerControl,
        ReadOnlyMemory<byte> payload,
        CancellationToken cancellationToken) =>
        State switch
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
        };

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
        if (type == OriginalMoveGridCodec.RequestType)
        {
            return await ProcessMoveGridAsync(
                decoded.Payload!, type.Value, cancellationToken);
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
                        OriginalWorldEntryCodec.EncodeUnit(
                            _worldGridUnitId,
                            _worldGridCellId)),
                    EncodeApplicationPush(
                        OriginalWorldEntryCodec.EncodeCharacter(
                            _worldCharacterId,
                            _worldGridUnitId,
                            EffectiveWorldCardId,
                            character)),
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
            var gridFrames = new List<byte[]>
            {
                OriginalWorldEntryCodec.EncodeCharacterContext(_worldCharacterId),
                OriginalWorldEntryCodec.EncodeGridEnterBoundary(0x0b09),
                OriginalWorldEntryCodec.EncodeUnit(
                    _worldGridUnitId,
                    _worldGridCellId),
                OriginalWorldEntryCodec.EncodeCharacter(
                    _worldCharacterId,
                    _worldGridUnitId,
                    EffectiveWorldCardId,
                    gridCharacter),
                OriginalWorldEntryCodec.EncodeGridEnterBoundary(0x0b0a),
                OriginalWorldBootstrapCodec.EncodeStaticGridTypes(),
                OriginalWorldBootstrapCodec.EncodeStaticGrid(),
                OriginalWorldBootstrapCodec.EncodeStatus(0x0f03)
            };
            gridFrames.AddRange(ownedRosterFrames);
            _receipt.Record("world-bootstrap", "grid-initialize-spawn", _accountId);
            var gridInitialize = EncodeApplicationResponse(
                gridFrames[0],
                type,
                includeLobbyPrefix: true);
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
                var posts = hierarchy
                    .Where(pair => pair.Value == OriginalAuthoredPlayableCatalog.AuthorityCardId)
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

            // PROBE (2026-09-03, LOGH7_LIST_KIND_PROBE=<sel>:1204|1205|1206): serve one authored record of the mapped kind so a
            // picker whose kind is unknown (作戦計画 selector 0x0021, 発令 0x000B) can be identified live. Grid = the current
            // grid unit cell, Base = base 1 of the served system scene, Strategy = ids 1..2 (作戦 catalog unknown). Read-only.
            var probeKind = _worldEntered ? OriginalSimpleRankCodec.ListKindProbe(simpleInformationSelector) : (ushort)0;
            if (probeKind is 0x1204 or 0x1205 or 0x1206 or 0x1207)
            {
                var probeFrames = probeKind switch
                {
                    0x1207 => OriginalSimpleRankCodec.EncodeUnitListTransaction(decoded.Payload.AsSpan(sizeof(ushort)), new (uint, byte, ushort)[] { (_worldGridUnitId, 0, 0) }),
                    0x1205 => OriginalSimpleRankCodec.EncodeGridListTransaction(decoded.Payload.AsSpan(sizeof(ushort)), new uint[] { 101, 102 }),
                    0x1204 => OriginalSimpleRankCodec.EncodeBaseListTransaction(decoded.Payload.AsSpan(sizeof(ushort)), new (uint, ushort, ushort)[] { (1, 0, 0), (2, 0, 0) }),
                    _ => OriginalSimpleRankCodec.EncodeStrategyListTransaction(decoded.Payload.AsSpan(sizeof(ushort)), new (uint, ushort, byte, byte)[] { (1, 0, 0, 0), (2, 0, 0, 0) }),
                };
                _receipt.Record("simple-information", $"list-kind-probe-served;selector=0x{simpleInformationSelector:X4};kind=0x{probeKind:X4}", _accountId);
                var probeBefore = probeFrames.Take(probeFrames.Count - 1).Select(frame => EncodeApplicationPush(frame)).ToArray();
                var probeEnd = EncodeApplicationResponse(probeFrames[^1], type, includeLobbyPrefix: true);
                return probeEnd with
                {
                    ResponsesBeforePrimary = probeBefore,
                    ResponseMetadata = $"list-kind-probe-served;selector=0x{simpleInformationSelector:X4};kind=0x{probeKind:X4};records=2"
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
                (requestedId == _worldCharacterId || requestedId == 0))
            {
                _receipt.Record("information-character", $"served;requested={requestedId}", _accountId);
                return EncodeApplicationResponse(
                    OriginalWorldEntryCodec.EncodeCharacter(
                        _worldCharacterId,
                        _worldGridUnitId,
                        EffectiveWorldCardId,
                        infoCharacter),
                    type,
                    includeLobbyPrefix: true) with
                {
                    ResponseMetadata = $"information-character-served;requested={requestedId};payloadHex={Convert.ToHexString(infoPayload)}"
                };
            }

            return Invalid("original.information-character.unknown-id", type, Convert.ToHexString(infoPayload));
        }

        // 0x0707 CommandCardAppointment (2026-09-03, run 044421Z payload
        // 0707 00000000 00000002 00000000 00000000 00000002 0028 0000 00000000 00000000 00000000 0000):
        // {u32 time, u32 characterId, u32 pcp, u32 mcp, u32 targetCharacterId, u16 cardId, ...} after the 2-byte type.
        // The client handler FUN_004BFCD0 ignores the response body (it clears the pending-command cells and
        // refreshes), so the accepted response echoes the command like 0x0704. Persistence: NEW_DESIGN table
        // original_card_appointment / original_character_card + domain event CharacterCardAppointed.
        if (type == 0x0707 && _worldEntered)
        {
            var appointPayload = decoded.Payload ?? Array.Empty<byte>();
            if (appointPayload.Length < 24)
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
            var cardId = checked((ushort)System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(appointPayload.AsSpan(22)));
            if (appointerId != _worldCharacterId || targetId == 0 || cardId == 0)
            {
                return Invalid($"original.card-appointment.identity;appointer={appointerId};world={_worldCharacterId};target={targetId};card={cardId}", type, Convert.ToHexString(appointPayload));
            }

            var fingerprint = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
                System.Text.Encoding.UTF8.GetBytes($"original-card-appointment/v1\n{_accountId:D}\n{appointerId}\n{cardId}\n{targetId}\n{Convert.ToHexString(appointPayload)}"))).ToLowerInvariant();
            CardAppointmentStoreResult stored;
            try
            {
                stored = await _store.AppointCardAsync(
                    _accountId,
                    new CardAppointmentWrite(fingerprint, appointerId, cardId, targetId),
                    cancellationToken);
            }
            catch (InvalidOperationException error)
            {
                return Invalid($"original.card-appointment.{error.Message.ToLowerInvariant().Replace('_', '-')}", type, Convert.ToHexString(appointPayload));
            }

            _receipt.Record("card-appointment", $"appointed;card={cardId};target={targetId};version={stored.AuthorityVersion};updated={stored.Updated}", _accountId);
            var accepted = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + 160];
            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(accepted.AsSpan(4), 0x0707);
            appointPayload.AsSpan(2, Math.Min(appointPayload.Length - 2, 160)).CopyTo(accepted.AsSpan(6));
            return EncodeApplicationResponse(accepted, type, includeLobbyPrefix: true) with
            {
                ResponseMetadata = $"card-appointment-accepted;card={cardId};target={targetId};appointer={appointerId};authorityVersion={stored.AuthorityVersion};payloadHex={Convert.ToHexString(appointPayload)}"
            };
        }

        // 罷免 CommandCardDismissal (0x0708): the inverse of 任命 — the acting character removes a target character's
        // current appointment (captured live 2026-09-03 run 20260903T065309Z; same field offsets as 0x0707:
        // appointer u32@+6, target u32@+18, card u32@+22). NEW_DESIGN persistence: delete original_character_card,
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
            var dismissCardId = checked((ushort)System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(dismissPayload.AsSpan(22)));
            if (dismissAppointerId != _worldCharacterId || dismissTargetId == 0 || dismissCardId == 0)
            {
                return Invalid($"original.card-dismissal.identity;appointer={dismissAppointerId};world={_worldCharacterId};target={dismissTargetId};card={dismissCardId}", type, Convert.ToHexString(dismissPayload));
            }

            var dismissFingerprint = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
                System.Text.Encoding.UTF8.GetBytes($"original-card-dismissal/v1\n{_accountId:D}\n{dismissAppointerId}\n{dismissCardId}\n{dismissTargetId}\n{Convert.ToHexString(dismissPayload)}"))).ToLowerInvariant();
            CardDismissalStoreResult dismissed;
            try
            {
                dismissed = await _store.DismissCardAsync(
                    _accountId,
                    new CardDismissalWrite(dismissFingerprint, dismissAppointerId, dismissCardId, dismissTargetId),
                    cancellationToken);
            }
            catch (Exception exception) when (exception is InvalidOperationException or ArgumentException or Npgsql.PostgresException)
            {
                var dismissReason = exception is Npgsql.PostgresException pg ? $"postgres-{pg.SqlState}" : exception.Message.ToLowerInvariant().Replace('_', '-');
                return RejectCommandVisibly($"original.card-dismissal.{dismissReason}", type.GetValueOrDefault(), "罷免できません（対象が任命されていないか、既に処理済みです）");
            }

            _receipt.Record("card-dismissal", $"dismissed;card={dismissCardId};target={dismissTargetId};version={dismissed.AuthorityVersion};updated={dismissed.Updated}", _accountId);
            var dismissAccepted = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + 160];
            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(dismissAccepted.AsSpan(4), 0x0708);
            dismissPayload.AsSpan(2, Math.Min(dismissPayload.Length - 2, 160)).CopyTo(dismissAccepted.AsSpan(6));
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
                System.Text.Encoding.UTF8.GetBytes($"original-card-resignation/v1\n{_accountId:D}\n{resignCommand.ActorId}\n{resignCommand.CardId}\n{Convert.ToHexString(resignPayload)}"))).ToLowerInvariant();
            CardResignationStoreResult resigned;
            try
            {
                resigned = await _store.ResignCardAsync(
                    _accountId,
                    new CardResignationWrite(resignFingerprint, resignCommand.ActorId, checked((int)resignCommand.CardId)),
                    OriginalAuthoredPlayableCatalog.AuthorityCardId,
                    cancellationToken);
            }
            catch (Exception exception) when (exception is InvalidOperationException or ArgumentException or Npgsql.PostgresException)
            {
                var resignReason = exception is Npgsql.PostgresException pg ? $"postgres-{pg.SqlState}" : exception.Message.ToLowerInvariant().Replace('_', '-');
                return RejectCommandVisibly($"original.card-resignation.{resignReason}", type.GetValueOrDefault(), "辞任できません（現在の職務と一致しないか、既に処理済みです）");
            }

            _worldCardId = 0;
            _receipt.Record("card-resignation", $"resigned;from={resignCommand.CardId};character={resignCommand.ActorId};version={resigned.AuthorityVersion};updated={resigned.Updated}", _accountId);
            var resignAccepted = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + 160];
            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(resignAccepted.AsSpan(4), 0x0709);
            resignPayload.AsSpan(2, Math.Min(resignPayload.Length - 2, 160)).CopyTo(resignAccepted.AsSpan(6));
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

            var downFingerprint = Convert.ToHexStringLower(SHA256.HashData(decoded.Payload!));
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
                        _worldCharacterId),
                    cancellationToken);
            }
            catch (Exception exception) when (exception is InvalidOperationException or ArgumentException or Npgsql.PostgresException)
            {
                var downReason = exception is Npgsql.PostgresException pg ? $"postgres-{pg.SqlState}" : exception.Message.ToLowerInvariant().Replace('_', '-');
                return RejectCommandVisibly($"original.rank-down.{downReason}", type.GetValueOrDefault(), "降等できません（階級が一致しないか、既に処理済みです）");
            }

            if (downStored.CharacterId == _worldCharacterId && downStored.Rank is > 0 and <= byte.MaxValue)
            {
                _createdCharacter = downActor with { Rank = checked((byte)downStored.Rank) };
            }

            _receipt.Record(
                "character-rank-down",
                (downStored.Updated ? "rank-demoted" : "idempotent-replay") + $";target={down.TargetCharacterId};rank={down.TargetRank}->{downStored.Rank}",
                _accountId);
            var downAccepted = EncodeApplicationResponse(
                OriginalRankDownCodec.EncodeAccepted(down),
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
                        OriginalWorldEntryCodec.EncodeCharacter(
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

            var specialFingerprint = Convert.ToHexStringLower(SHA256.HashData(decoded.Payload!));
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
                        _worldCharacterId),
                    cancellationToken);
            }
            catch (Exception exception) when (exception is InvalidOperationException or ArgumentException or Npgsql.PostgresException)
            {
                var specialReason = exception is Npgsql.PostgresException pg ? $"postgres-{pg.SqlState}" : exception.Message.ToLowerInvariant().Replace('_', '-');
                return RejectCommandVisibly($"original.special-rank-up.{specialReason}", type.GetValueOrDefault(), "抜擢できません（階級が一致しないか、既に処理済みです）");
            }

            if (specialStored.CharacterId == _worldCharacterId && specialStored.Rank is > 0 and <= byte.MaxValue)
            {
                _createdCharacter = specialActor with { Rank = checked((byte)specialStored.Rank) };
            }

            _receipt.Record(
                "character-special-rank-up",
                (specialStored.Updated ? "rank-promoted" : "idempotent-replay") + $";target={special.TargetCharacterId};rank={special.TargetRank}->{specialStored.Rank}",
                _accountId);
            var specialAccepted = EncodeApplicationResponse(
                OriginalSpecialRankUpCodec.EncodeAccepted(special),
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
                        OriginalWorldEntryCodec.EncodeCharacter(
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

            var requestFingerprint = Convert.ToHexStringLower(
                SHA256.HashData(decoded.Payload!));
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

            _createdCharacter = character with { Rank = checked((byte)stored.Rank) };
            _receipt.Record(
                "character-rank-up",
                stored.Updated ? "rank-promoted" : "idempotent-replay",
                _accountId);
            var accepted = EncodeApplicationResponse(
                OriginalRankUpCodec.EncodeAccepted(command),
                type,
                includeLobbyPrefix: true);
            return accepted with
            {
                AdditionalResponses =
                [
                    EncodeApplicationPush(
                        OriginalWorldEntryCodec.EncodeCharacter(
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
            return EncodeApplicationResponse(
                OriginalWorldBootstrapCodec.EncodeMessengerInformation(records),
                type,
                includeLobbyPrefix: true) with
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

        if (OriginalWorldBootstrapCodec.TryEncodeResponse(decoded.Payload!, out var bootstrap))
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
                command.AbilityValues.Select(value => (short)value).ToArray());
            var stored = await _store.CreateCharacterAsync(_accountId, write, cancellationToken);
            var storedId = checked((uint)stored.CharacterId);
            _worldCharacterId = storedId;
            _worldGridUnitId = storedId;
            _createdCharacter = command;
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

        return Invalid(
            "original.session-server.unexpected-application-type",
            type,
            Convert.ToHexString(decoded.Payload!));
    }

    private async Task<NaturalAuthoritySessionResult> ProcessMoveGridAsync(
        byte[] payload,
        ushort type,
        CancellationToken cancellationToken)
    {
        if (!OriginalMoveGridCodec.TryDecodeRequest(payload, out var request))
        {
            return Invalid(
                "original.move-grid.request-shape",
                type,
                Convert.ToHexString(payload));
        }

        if (!_worldEntered ||
            _createdCharacter is null ||
            _worldCharacterId == 0 ||
            _worldGridUnitId == 0)
        {
            return Invalid("original.move-grid.world-not-entered", type);
        }

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
                request.Id,
                cancellationToken);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.move-grid.persistence-not-supported", type);
        }

        if (persisted is null ||
            persisted.CharacterId != selected.CharacterId ||
            persisted.UnitId != request.Id ||
            request.Id != _worldGridUnitId)
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
            UnitId: request.Id,
            AuthorityCardId: request.Card,
            SourceCellId: persisted.CurrentCellId,
            DestinationCellId: request.Grid,
            Action: OriginalMoveGridAuthority.MinimalWorldWarpAction);
        var decision = OriginalMoveGridAuthority.Transition(
            new OriginalMoveGridAuthorityState(
                persisted.UnitId,
                persisted.AuthorityCardId,
                persisted.CurrentCellId),
            command);
        if (decision.Status != OriginalMoveGridAuthorityStatus.Allowed ||
            decision.Notification is null)
        {
            // NEW_DESIGN soft rejection: keep the session open and tell the player why,
            // using the original client's 0x0500 NotifyInvalidMessage (ORIGINAL_STATIC wire).
            return RejectMoveGridVisibly(decision.ErrorCode ?? "authority-rejected", type);
        }

        var requestFingerprint = Convert.ToHexStringLower(SHA256.HashData(payload));
        OriginalMoveGridStoreResult stored;
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
                    command.Action),
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
            movedUnit.CurrentCellId != decision.State.CellId)
        {
            return Invalid("original.move-grid.persisted-state-shape", type);
        }

        _worldGridCellId = movedUnit.CurrentCellId;
        _receipt.Record(
            "move-grid",
            $"new-design-unit-{movedUnit.UnitId}-cell-{movedUnit.CurrentCellId}-persisted",
            _accountId);
        var accepted = EncodeApplicationResponse(
            OriginalMoveGridCodec.EncodeNotification(decision.Notification.Value),
            type,
            includeLobbyPrefix: true);
        return accepted with
        {
            AdditionalResponses =
            [
                EncodeApplicationPush(
                    OriginalWorldEntryCodec.EncodeUnit(
                        movedUnit.UnitId,
                        movedUnit.CurrentCellId))
            ],
            ResponseMetadata = FormattableString.Invariant(
                $"move-grid-unit={movedUnit.UnitId};source-cell={command.SourceCellId};destination-cell={movedUnit.CurrentCellId};authority-version={stored.AuthorityVersion};design=new")
        };
    }

    private async Task<string?> RestorePersistedGridUnitAsync(
        CancellationToken cancellationToken)
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

        _worldGridCellId = persisted.CurrentCellId;
        return null;
    }

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

    private static OriginalCreateCharacterCommand RestoreCharacter(CharacterReadRecord character)
    {
        if (character.CharacterId is <= 0 or > uint.MaxValue ||
            character.Faction is < 0 or > byte.MaxValue ||
            character.Blood is < 0 or > byte.MaxValue ||
            character.Sex is < 0 or > byte.MaxValue ||
            character.Face < 0 ||
            character.AbilityValues.Length != 8 ||
            character.AbilityValues.Any(value => value is < 0 or > byte.MaxValue) ||
            character.Rank is <= 0 or > byte.MaxValue)
        {
            throw new InvalidOperationException("PERSISTED_CHARACTER_WIRE_RANGE");
        }

        return new OriginalCreateCharacterCommand(
            RequestCategory: 4,
            CharacterId: checked((uint)character.CharacterId),
            Power: checked((byte)character.Faction),
            Blood: checked((byte)character.Blood),
            Sex: checked((byte)character.Sex),
            LastName: character.LastName,
            FirstName: character.FirstName,
            Age: 18,
            BirthMonth: 1,
            BirthDay: 1,
            Face: checked((uint)character.Face),
            AbilityValues: character.AbilityValues.Select(value => checked((byte)value)).ToArray(),
            BonusPoint: 0,
            Title: 0,
            Rank: checked((byte)character.Rank),
            FlagshipClass: 0,
            FlagshipModel: 0,
            FlagshipId: 0,
            FlagshipName: character.FlagshipName,
            Check: 0,
            RawPayload: []);
    }

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

    private NaturalAuthorityPush EncodeApplicationPush(ReadOnlySpan<byte> applicationPayload)
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
        };

    // Personnel commands (0x0704 family): a store rejection becomes the client's 0x0500 NotifyInvalidMessage with a
    // Japanese reason instead of a dropped connection (condition 7: visible rejection reasons). NEW_DESIGN text.
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
