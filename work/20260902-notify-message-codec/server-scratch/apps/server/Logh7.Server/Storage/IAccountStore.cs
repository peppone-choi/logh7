using Logh7.Server.Authority;
using Logh7.Server.OriginalGateway;
using System.Security.Cryptography;
using System.Text;

namespace Logh7.Server.Storage;

public sealed record CharacterCreateWrite(
    string RequestFingerprint,
    string PayloadHash,
    short Faction,
    short Blood,
    short Sex,
    string LastName,
    string FirstName,
    string FlagshipName,
    int Face,
    short[] AbilityValues,
    byte? FlagshipType = null,
    ushort? FlagshipKind = null);

public readonly record struct CharacterCreateStoreResult(
    long CharacterId,
    bool Created,
    long AuthorityVersion);

public sealed record CharacterReadRecord(
    long CharacterId,
    short Slot,
    short Faction,
    short Blood,
    short Sex,
    string LastName,
    string FirstName,
    string FlagshipName,
    int Face,
    short[] AbilityValues,
    short Rank = 20,
    byte? FlagshipType = null,
    ushort? FlagshipKind = null,
    uint ReturnBaseId = 0,
    uint Pcp = 0,
    uint Mcp = 0,
    uint Achievement = 0);

public sealed record OriginalReturnBaseWrite(
    string RequestFingerprint,
    long CharacterId,
    uint ReturnBaseId);

public readonly record struct OriginalReturnBaseStoreResult(
    uint CurrentReturnBaseId,
    bool Updated,
    long AuthorityVersion);

public sealed record CharacterRankUpWrite(
    string RequestFingerprint,
    long CharacterId,
    short ExpectedRank,
    short PromotedRank,
    string EventType = "CharacterRankPromoted",
    long ActorCharacterId = 0,
    OriginalCommandPointPolicy? PointPolicy = null,
    DateTimeOffset? Now = null,
    bool InTactics = false);

public readonly record struct CharacterRankUpStoreResult(
    long CharacterId,
    short Rank,
    bool Updated,
    long AuthorityVersion,
    uint Achievement = 0);

public sealed record CharacterCardRecord(
    long CharacterId,
    int CardId,
    long AppointedByCharacterId,
    long AuthorityVersion);

public sealed record CardAppointmentWrite(
    string RequestFingerprint,
    long CharacterId,
    int CardId,
    long TargetCharacterId,
    OriginalCommandPointPolicy? PointPolicy = null,
    DateTimeOffset? Now = null,
    bool InTactics = false,
    ushort? RequiredAppointerCard = null,
    ushort BootstrapActorCard = 39);

public readonly record struct CardAppointmentStoreResult(
    long TargetCharacterId,
    int CardId,
    bool Updated,
    long AuthorityVersion);

public sealed record CardDismissalWrite(
    string RequestFingerprint,
    long CharacterId,
    int CardId,
    long TargetCharacterId,
    OriginalCommandPointPolicy? PointPolicy = null,
    DateTimeOffset? Now = null,
    bool InTactics = false);

public readonly record struct CardDismissalStoreResult(
    long TargetCharacterId,
    int CardId,
    bool Updated,
    long AuthorityVersion);

public sealed record CardResignationWrite(
    string RequestFingerprint,
    long CharacterId,
    int SourceCardId,
    OriginalCommandPointPolicy? PointPolicy = null,
    DateTimeOffset? Now = null,
    bool InTactics = false);

public readonly record struct CardResignationStoreResult(
    long CharacterId,
    int SourceCardId,
    bool Updated,
    long AuthorityVersion);

public sealed record CharacterDeleteWrite(
    string RequestFingerprint,
    long CharacterId,
    uint SessionId);

public readonly record struct CharacterDeleteStoreResult(
    long CharacterId,
    short SourceSlot,
    bool Deleted,
    long AuthorityVersion);

public sealed record OriginalMailSendWrite(
    string RequestFingerprint,
    long SenderCharacterId,
    long RecipientCharacterId,
    string Title,
    string Body);

public readonly record struct OriginalMailSendStoreResult(
    long MailId,
    bool Created,
    long AuthorityVersion);

public readonly record struct OriginalMailReadStoreResult(
    long MailId,
    bool Updated,
    long AuthorityVersion);

public readonly record struct OriginalMailDeleteStoreResult(
    long MailId,
    bool Updated,
    long AuthorityVersion);

public sealed record OriginalOrderSuggestReplyWrite(
    string RequestFingerprint,
    long CharacterId,
    int CardId,
    byte ReplyValue);

public readonly record struct OriginalOrderSuggestReplyStoreResult(
    long CharacterId,
    int CardId,
    byte ReplyValue,
    bool Updated,
    long AuthorityVersion);

public sealed record OriginalOrderSuggestReplyRecord(
    long CharacterId,
    int CardId,
    byte ReplyValue,
    string RequestFingerprint,
    long AuthorityVersion,
    DateTimeOffset RespondedAt);

public sealed record OriginalMailRecord(
    long MailId,
    long SenderCharacterId,
    long RecipientCharacterId,
    string Title,
    string Body,
    long AuthorityVersion,
    DateTimeOffset SentAt,
    bool IsRead = false,
    DateTimeOffset? ReadAt = null,
    bool SenderDeleted = false,
    bool RecipientDeleted = false);

public sealed record OriginalMessengerMessageWrite(
    string RequestFingerprint,
    long SenderCharacterId,
    long RecipientCharacterId,
    string Message,
    byte[] WirePayload);

public readonly record struct OriginalMessengerMessageStoreResult(
    long MessageId,
    bool Created,
    long AuthorityVersion);

public sealed record OriginalMessengerMessageRecord(
    long MessageId,
    Guid SenderAccountId,
    long SenderCharacterId,
    long RecipientCharacterId,
    string Message,
    byte[] WirePayload,
    string RequestFingerprint,
    long AuthorityVersion,
    DateTimeOffset SentAt);

public sealed record OriginalGridUnitRecord(
    long CharacterId,
    uint UnitId,
    ushort AuthorityCardId,
    uint CurrentCellId,
    long AuthorityVersion,
    uint BaseId = 0,
    ushort Damaged = 0,
    ushort Destroyed = 0,
    Guid? InjuryReturnId = null,
    long ShipGeneration = 0,
    float Cruising = 10,
    byte Mode = 0,
    ushort UnitNumber = 100,
    uint Supplies = 100,
    byte Morale = 100);

public sealed record OriginalInjuryReturnWrite(
    Guid DefeatId, long CharacterId, uint UnitId, long ExpectedUnitVersion,
    uint SourceGrid, uint DestinationGrid, uint DestinationBase,
    ushort Number, ushort Damaged, ushort Destroyed);

public readonly record struct OriginalInjuryReturnStoreResult(OriginalGridUnitRecord Unit, bool Updated);

public sealed record OriginalDepartureWrite(
    string RequestFingerprint, long CharacterId, uint UnitId,
    long ExpectedUnitVersion, long ExpectedShipGeneration, uint ExpectedBaseId,
    byte TargetMode = 4);
public readonly record struct OriginalDepartureStoreResult(OriginalGridUnitRecord Unit, bool Updated);

public sealed record OriginalUnitDamageWrite(long CharacterId,uint UnitId,uint Grid,
    long ShipGeneration,ushort Number,ushort Damaged,ushort Destroyed);
public readonly record struct OriginalUnitDamageStoreResult(OriginalGridUnitRecord Unit,bool Updated);

/// <summary>
/// The command points one grid move consumes, charged inside the move's own
/// transaction so a warp can never be paid for without moving or moved without
/// being paid for.
/// </summary>
public sealed record OriginalMoveGridPointCharge(
    OriginalCommandPointPool Pool,
    uint Cost,
    OriginalCommandPointPolicy Policy,
    DateTimeOffset Now,
    bool InTactics);

public sealed record OriginalMoveGridWrite(
    string RequestFingerprint,
    long CharacterId,
    uint UnitId,
    ushort AuthorityCardId,
    uint ExpectedCurrentCellId,
    uint SourceCellId,
    uint DestinationCellId,
    ushort Action)
{
    /// <summary>
    /// Command points this move consumes, spent in the move's own transaction.
    /// Null leaves the move free, which is what compatibility stores and the
    /// pre-economy tests expect.
    /// </summary>
    public OriginalMoveGridPointCharge? Points { get; init; }

    /// <summary>
    /// Base the unit joins on arrival, resolved by the caller from the
    /// destination grid's own content; 0 arrives in open space.
    /// </summary>
    public uint DestinationBaseId { get; init; }
}

public enum OriginalMoveGridStoreStatus
{
    Moved,
    Replayed,
    Rejected,
}

public readonly record struct OriginalMoveGridStoreResult(
    OriginalMoveGridStoreStatus Status,
    OriginalGridUnitRecord? Unit,
    long AuthorityVersion,
    string? ErrorCode);

public enum OriginalCharacterLotteryEntryStatus
{
    Pending,
    Awarded
}

public sealed record OriginalCharacterLotteryEntryWrite(
    string RequestFingerprint,
    uint[] CandidateCharacterIds);

public readonly record struct OriginalCharacterLotteryEntryStoreResult(
    long EntryId,
    bool Created,
    long AuthorityVersion);

public sealed record OriginalCharacterLotteryAwardWrite(
    long EntryId,
    uint ResultCandidateCharacterId,
    string Provenance,
    CharacterCreateWrite Character);

public readonly record struct OriginalCharacterLotteryAwardStoreResult(
    long EntryId,
    uint ResultCandidateCharacterId,
    long CharacterId,
    bool Awarded,
    long AuthorityVersion);

public static class OriginalCharacterLotteryAwardIdentity
{
    public static string CharacterRequestFingerprint(long entryId, uint candidateCharacterId) =>
        Hash(FormattableString.Invariant(
            $"original-character-lottery-award/v1\n{entryId}\n{candidateCharacterId:x8}"));

    public static string CharacterPayloadHash(
        long entryId,
        uint candidateCharacterId,
        string provenance) =>
        Hash(FormattableString.Invariant(
            $"original-character-lottery-payload/v1\n{entryId}\n{candidateCharacterId:x8}\n{provenance}"));

    private static string Hash(string value) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
}

public sealed record OriginalCharacterLotteryEntryRecord(
    long EntryId,
    string RequestFingerprint,
    uint[] CandidateCharacterIds,
    OriginalCharacterLotteryEntryStatus Status,
    uint? ResultCharacterId,
    long AuthorityVersion,
    DateTimeOffset SubmittedAt);

/// <summary>One 陸戦 / 陸戦解除 move of a unit's landing force.</summary>
public sealed record OriginalTroopMoveWrite(
    long CharacterId, uint UnitId, uint BaseId, uint GridId);

public sealed record OriginalTroopMoveResult(
    bool Applied, long Carried, long Landed, long AuthorityVersion, string? ErrorCode = null);

/// <summary>Where a unit's landing force stands: carried, ashore, and at which base.</summary>
public readonly record struct OriginalTroopState(long Carried, long Landed, uint BaseId);

/// <summary>One 緊急補給 grant: a base makes a unit resupplyable in its grid.</summary>
public sealed record OriginalEmergencySupplyGrantWrite(
    long CharacterId, uint UnitId, uint BaseId, uint GridId);

public sealed record OriginalEmergencySupplyGrantResult(
    bool Granted, bool AlreadyGranted, long AuthorityVersion, string? ErrorCode = null);

public interface IAccountStore
{
    Task<OriginalOutfitMembership?> FindOriginalOutfitMembershipAsync(Guid accountId,long characterId,
        CancellationToken cancellationToken) => Task.FromResult<OriginalOutfitMembership?>(null);
    Task<OriginalInjuryReturnStoreResult> RecoverOriginalFlagshipAsync(
        Guid accountId, long characterId, uint unitId, Guid returnId,
        long expectedUnitVersion, CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<OriginalWarehouseSnapshot> ReadOriginalWarehouseAsync(
        Guid accountId, long characterId, OriginalWarehouseKey key, CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<OriginalInjuryReturnStoreResult> ReturnInjuredOriginalUnitAsync(
        Guid accountId, OriginalInjuryReturnWrite write, CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<OriginalReturnBaseStoreResult> SetOriginalReturnBaseAsync(
        Guid accountId,
        OriginalReturnBaseWrite write,
        CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<AccountRecord> ProvisionAsync(
        AccountProvision provision,
        CancellationToken cancellationToken);

    Task<AccountRecord?> FindAccountAsync(
        string normalizedLogin,
        CancellationToken cancellationToken);

    Task<int> CountCharactersAsync(
        Guid accountId,
        CancellationToken cancellationToken);

    Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(
        Guid accountId,
        CancellationToken cancellationToken);

    Task<CharacterCreateStoreResult> CreateCharacterAsync(
        Guid accountId,
        CharacterCreateWrite write,
        CancellationToken cancellationToken);

    Task<IReadOnlyList<CharacterCardRecord>> ListCharacterCardsAsync(
        Guid accountId,
        CancellationToken cancellationToken);

    Task<CardAppointmentStoreResult> AppointCardAsync(
        Guid accountId,
        CardAppointmentWrite write,
        CancellationToken cancellationToken);

    Task<CardDismissalStoreResult> DismissCardAsync(
        Guid accountId,
        CardDismissalWrite write,
        CancellationToken cancellationToken);

    Task<CardResignationStoreResult> ResignCardAsync(
        Guid accountId,
        CardResignationWrite write,
        int defaultCardId,
        CancellationToken cancellationToken);

    Task<CharacterRankUpStoreResult> PromoteCharacterAsync(
        Guid accountId,
        CharacterRankUpWrite write,
        CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<CharacterDeleteStoreResult> DeleteCharacterAsync(
        Guid accountId,
        CharacterDeleteWrite write,
        CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<OriginalMailSendStoreResult> SendOriginalMailAsync(
        Guid accountId,
        OriginalMailSendWrite write,
        CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<IReadOnlyList<OriginalMailRecord>> ListOriginalMailAsync(
        Guid accountId,
        CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<OriginalMessengerMessageStoreResult> SaveOriginalMessengerMessageAsync(
        Guid accountId,
        OriginalMessengerMessageWrite write,
        CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<IReadOnlyList<OriginalMessengerMessageRecord>> ListOriginalMessengerMessagesAsync(
        Guid accountId,
        long viewerCharacterId,
        long peerCharacterId,
        CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<OriginalGridUnitRecord?> FindOriginalGridUnitAsync(
        Guid accountId,
        long characterId,
        uint unitId,
        CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<OriginalMoveGridStoreResult> MoveOriginalGridUnitAsync(
        Guid accountId,
        OriginalMoveGridWrite write,
        CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    /// <summary>
    /// Settles elapsed command point regeneration. Passive: it advances no
    /// authority version and writes nothing when no whole interval elapsed.
    /// </summary>
    Task<OriginalCommandPointState> AccrueCommandPointsAsync(
        Guid accountId,
        long characterId,
        OriginalCommandPointPolicy policy,
        DateTimeOffset now,
        bool inTactics,
        CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<OriginalDepartureStoreResult> DepartOwnOriginalUnitAsync(
        Guid accountId, OriginalDepartureWrite write, CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    /// <summary>
    /// 完全修復 for the player's own flagship: the repair, its command-point
    /// charge and its history row commit together, or none of them do.
    /// </summary>
    Task<OriginalFlagshipRepairResult> RepairOwnOriginalFlagshipAsync(
        Guid accountId, OriginalFlagshipRepairWrite write, CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    /// <summary>
    /// 補給 (0x0414): the refill and its history row commit together, or neither
    /// does. No command-point charge - the original states none for this command.
    /// </summary>
    Task<OriginalTacticalSupplyResult> SupplyOwnOriginalUnitAsync(
        Guid accountId, OriginalTacticalSupplyWrite write, CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    /// <summary>
    /// EncourageBase (0x041D): 鼓舞 aimed at a base. The morale write, its
    /// command-point charge and its history row commit together, or none of them do.
    /// </summary>
    Task<OriginalBaseEncourageResult> EncourageOriginalBaseAsync(
        Guid accountId, OriginalBaseEncourageWrite write, CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    /// <summary>
    /// AttackTroop (0x0417): a landing force ashore assaults the base it stands on.
    /// </summary>
    Task<OriginalBaseEncourageResult> AssaultOriginalBaseAsync(
        Guid accountId, OriginalBaseAssaultWrite write, CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    /// <summary>
    /// 陸戦 (0x040F): a unit anchored at a base puts its landing force ashore.
    /// </summary>
    Task<OriginalTroopMoveResult> LandOriginalTroopsAsync(
        Guid accountId, OriginalTroopMoveWrite write, CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    /// <summary>
    /// 陸戦解除 (0x0410): the landing force comes back aboard.
    /// </summary>
    Task<OriginalTroopMoveResult> RecallOriginalTroopsAsync(
        Guid accountId, OriginalTroopMoveWrite write, CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    /// <summary>
    /// MoveTroop (0x0416): a landing force ashore marches to another area.
    /// </summary>
    Task<OriginalTroopMoveResult> RelocateOriginalTroopsAsync(
        Guid accountId, OriginalTroopMoveWrite write, uint destinationBaseId,
        CancellationToken cancellationToken) => throw new NotSupportedException();

    /// <summary>空戦 (0x040E): a sortie costs the unit part of its carried craft.</summary>
    Task<OriginalSimpleStoreResult> SpendOriginalBoatsAsync(
        Guid accountId, OriginalBoatSpendWrite write, CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    /// <summary>Admission (0x040B) / AdmissionBase (0x041A): a host admits a unit.</summary>
    Task<OriginalSimpleStoreResult> GrantOriginalAdmissionAsync(
        Guid accountId, OriginalAdmissionWrite write, CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    /// <summary>MoveFortress (0x041F): where a fortress stands after it moved.</summary>
    Task<OriginalSimpleStoreResult> MoveOriginalBaseAsync(
        Guid accountId, OriginalBasePositionWrite write, CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    /// <summary>
    /// 白兵戦 (0x0407): a boarding party costs the unit part of its complement.
    /// </summary>
    Task<OriginalTroopMoveResult> SpendOriginalTroopsAsync(
        Guid accountId, OriginalTroopMoveWrite write, long amount,
        CancellationToken cancellationToken) => throw new NotSupportedException();

    /// <summary>Where a unit's landing force stands right now.</summary>
    Task<OriginalTroopState> ReadOriginalTroopStateAsync(
        Guid accountId, long characterId, uint unitId, CancellationToken cancellationToken) =>
        Task.FromResult(new OriginalTroopState(0, 0, 0));

    /// <summary>
    /// 緊急補給 (0x0422): record that a base has made a unit resupplyable.
    /// </summary>
    /// <remarks>
    /// constmsg group 0 row 27 is 「緊急補給可能にする」 - it makes an emergency resupply
    /// *possible*, it does not perform one, so this writes a grant and refills
    /// nothing. Granting the same (character, unit, base) twice is the same grant.
    /// </remarks>
    Task<OriginalEmergencySupplyGrantResult> GrantOriginalEmergencySupplyAsync(
        Guid accountId, OriginalEmergencySupplyGrantWrite write, CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    /// <summary>
    /// Whether a base in this grid has granted the unit an emergency resupply,
    /// which is what lets 補給 proceed without a 補給艦.
    /// </summary>
    Task<bool> HasOriginalEmergencySupplyGrantAsync(
        Guid accountId, long characterId, uint unitId, uint gridId, CancellationToken cancellationToken) =>
        Task.FromResult(false);

    /// <summary>
    /// 鼓舞 for the player's own flagship: the morale write, its command-point
    /// charge and its history row commit together, or none of them do.
    /// </summary>
    Task<OriginalFlagshipEncourageResult> EncourageOwnOriginalFlagshipAsync(
        Guid accountId, OriginalFlagshipEncourageWrite write, CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<OriginalUnitDamageStoreResult> SaveOriginalUnitDamageAsync(
        Guid accountId,OriginalUnitDamageWrite write,CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<OriginalMailReadStoreResult> MarkOriginalMailReadAsync(
        Guid accountId,
        long characterId,
        long mailId,
        CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<OriginalMailDeleteStoreResult> DeleteOriginalMailAsync(
        Guid accountId,
        long characterId,
        long mailId,
        byte box,
        CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<OriginalOrderSuggestReplyStoreResult> SaveOriginalOrderSuggestReplyAsync(
        Guid accountId,
        OriginalOrderSuggestReplyWrite write,
        CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<OriginalOrderSuggestReplyRecord?> FindOriginalOrderSuggestReplyAsync(
        Guid accountId,
        long characterId,
        int cardId,
        CancellationToken cancellationToken) =>
        throw new NotSupportedException();

    Task<OriginalCharacterLotteryEntryStoreResult> EnterOriginalCharacterLotteryAsync(
        Guid accountId,
        OriginalCharacterLotteryEntryWrite write,
        CancellationToken cancellationToken);

    Task<OriginalCharacterLotteryEntryRecord?> FindPendingOriginalCharacterLotteryAsync(
        Guid accountId,
        CancellationToken cancellationToken);

    Task<OriginalCharacterLotteryAwardStoreResult> AwardOriginalCharacterLotteryAsync(
        Guid accountId,
        OriginalCharacterLotteryAwardWrite write,
        CancellationToken cancellationToken);
}
