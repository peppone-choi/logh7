using Logh7.Server.Authority;
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
    short[] AbilityValues);

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
    short Rank = 20);

public sealed record CharacterRankUpWrite(
    string RequestFingerprint,
    long CharacterId,
    short ExpectedRank,
    short PromotedRank,
    string EventType = "CharacterRankPromoted",
    long ActorCharacterId = 0);

public readonly record struct CharacterRankUpStoreResult(
    long CharacterId,
    short Rank,
    bool Updated,
    long AuthorityVersion);

public sealed record CharacterCardRecord(
    long CharacterId,
    int CardId,
    long AppointedByCharacterId,
    long AuthorityVersion);

public sealed record CardAppointmentWrite(
    string RequestFingerprint,
    long CharacterId,
    int CardId,
    long TargetCharacterId);

public readonly record struct CardAppointmentStoreResult(
    long TargetCharacterId,
    int CardId,
    bool Updated,
    long AuthorityVersion);

public sealed record CardDismissalWrite(
    string RequestFingerprint,
    long CharacterId,
    int CardId,
    long TargetCharacterId);

public readonly record struct CardDismissalStoreResult(
    long TargetCharacterId,
    int CardId,
    bool Updated,
    long AuthorityVersion);

public sealed record CardResignationWrite(
    string RequestFingerprint,
    long CharacterId,
    int SourceCardId);

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
    long AuthorityVersion);

public sealed record OriginalMoveGridWrite(
    string RequestFingerprint,
    long CharacterId,
    uint UnitId,
    ushort AuthorityCardId,
    uint ExpectedCurrentCellId,
    uint SourceCellId,
    uint DestinationCellId,
    ushort Action);

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

public interface IAccountStore
{
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
