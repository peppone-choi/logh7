using System.Security.Cryptography;
using System.Text;

namespace Logh7.Server.Authority;

public static class AuthorityStateHash
{
    private static readonly byte[] Prefix = "logh7-authority-state/v1\n"u8.ToArray();

    public static string EmptyAccount(Guid accountId)
    {
        var canonicalJson = FormattableString.Invariant(
            $"{{\"accountId\":\"{accountId:D}\",\"authorityVersion\":0,\"characters\":[]}}");
        var jsonBytes = Encoding.UTF8.GetBytes(canonicalJson);
        var input = new byte[Prefix.Length + jsonBytes.Length];
        Prefix.CopyTo(input, 0);
        jsonBytes.CopyTo(input, Prefix.Length);
        try
        {
            return Convert.ToHexStringLower(SHA256.HashData(input));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(input);
        }
    }

    public static string CharacterCreated(
        Guid accountId,
        long authorityVersion,
        long characterId,
        string requestFingerprint)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(authorityVersion);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(characterId);
        ArgumentException.ThrowIfNullOrWhiteSpace(requestFingerprint);
        var canonicalJson = FormattableString.Invariant(
            $"{{\"accountId\":\"{accountId:D}\",\"authorityVersion\":{authorityVersion},\"latestCharacterId\":{characterId},\"requestFingerprint\":\"{requestFingerprint}\"}}");
        return HashCanonicalJson(canonicalJson);
    }

    public static string OriginalCharacterLotteryEntered(
        Guid accountId,
        long authorityVersion,
        long entryId,
        string requestFingerprint)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(authorityVersion);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(entryId);
        ArgumentException.ThrowIfNullOrWhiteSpace(requestFingerprint);
        var canonicalJson = FormattableString.Invariant(
            $"{{\"accountId\":\"{accountId:D}\",\"authorityVersion\":{authorityVersion},\"latestOriginalCharacterLotteryEntryId\":{entryId},\"requestFingerprint\":\"{requestFingerprint}\"}}");
        return HashCanonicalJson(canonicalJson);
    }

    public static string CharacterRankPromoted(
        Guid accountId,
        long authorityVersion,
        long characterId,
        short sourceRank,
        short promotedRank,
        string requestFingerprint)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(authorityVersion);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(characterId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(sourceRank);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(promotedRank);
        ArgumentException.ThrowIfNullOrWhiteSpace(requestFingerprint);
        var canonicalJson = FormattableString.Invariant(
            $"{{\"accountId\":\"{accountId:D}\",\"authorityVersion\":{authorityVersion},\"characterId\":{characterId},\"sourceRank\":{sourceRank},\"promotedRank\":{promotedRank},\"requestFingerprint\":\"{requestFingerprint}\"}}");
        return HashCanonicalJson(canonicalJson);
    }

    public static string CharacterCardAppointed(
        Guid accountId,
        long authorityVersion,
        long characterId,
        int cardId,
        long targetCharacterId,
        string requestFingerprint)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(authorityVersion);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(characterId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(cardId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(targetCharacterId);
        ArgumentException.ThrowIfNullOrWhiteSpace(requestFingerprint);
        var canonicalJson = FormattableString.Invariant(
            $"{{\"accountId\":\"{accountId:D}\",\"authorityVersion\":{authorityVersion},\"characterId\":{characterId},\"cardId\":{cardId},\"targetCharacterId\":{targetCharacterId},\"requestFingerprint\":\"{requestFingerprint}\"}}");
        return HashCanonicalJson(canonicalJson);
    }

    public static string CharacterCardDismissed(
        Guid accountId,
        long authorityVersion,
        long characterId,
        int cardId,
        long targetCharacterId,
        string requestFingerprint)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(authorityVersion);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(characterId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(cardId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(targetCharacterId);
        ArgumentException.ThrowIfNullOrWhiteSpace(requestFingerprint);
        var canonicalJson = FormattableString.Invariant(
            $"{{\"accountId\":\"{accountId:D}\",\"authorityVersion\":{authorityVersion},\"characterId\":{characterId},\"cardId\":{cardId},\"dismissedCharacterId\":{targetCharacterId},\"requestFingerprint\":\"{requestFingerprint}\"}}");
        return HashCanonicalJson(canonicalJson);
    }

    public static string CharacterCardResigned(
        Guid accountId,
        long authorityVersion,
        long characterId,
        int sourceCardId,
        string requestFingerprint)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(authorityVersion);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(characterId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(sourceCardId);
        ArgumentException.ThrowIfNullOrWhiteSpace(requestFingerprint);
        var canonicalJson = FormattableString.Invariant(
            $"{{\"accountId\":\"{accountId:D}\",\"authorityVersion\":{authorityVersion},\"characterId\":{characterId},\"sourceCardId\":{sourceCardId},\"resultingCardId\":0,\"requestFingerprint\":\"{requestFingerprint}\"}}");
        return HashCanonicalJson(canonicalJson);
    }

    public static string CharacterDeleted(
        Guid accountId,
        long authorityVersion,
        long characterId,
        short sourceSlot,
        uint sessionId,
        string requestFingerprint)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(authorityVersion);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(characterId);
        ArgumentOutOfRangeException.ThrowIfNegative(sourceSlot);
        ArgumentOutOfRangeException.ThrowIfZero(sessionId);
        ArgumentException.ThrowIfNullOrWhiteSpace(requestFingerprint);
        var canonicalJson = FormattableString.Invariant(
            $"{{\"accountId\":\"{accountId:D}\",\"authorityVersion\":{authorityVersion},\"deletedCharacterId\":{characterId},\"sourceSlot\":{sourceSlot},\"sessionId\":{sessionId},\"requestFingerprint\":\"{requestFingerprint}\"}}");
        return HashCanonicalJson(canonicalJson);
    }

    public static string OriginalMailSent(
        Guid accountId,
        long authorityVersion,
        long mailId,
        long senderCharacterId,
        long recipientCharacterId,
        string requestFingerprint)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(authorityVersion);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(mailId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(senderCharacterId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(recipientCharacterId);
        ArgumentException.ThrowIfNullOrWhiteSpace(requestFingerprint);
        var canonicalJson = FormattableString.Invariant(
            $"{{\"accountId\":\"{accountId:D}\",\"authorityVersion\":{authorityVersion},\"latestMailId\":{mailId},\"senderCharacterId\":{senderCharacterId},\"recipientCharacterId\":{recipientCharacterId},\"requestFingerprint\":\"{requestFingerprint}\"}}");
        return HashCanonicalJson(canonicalJson);
    }

    public static string OriginalMailRead(
        Guid accountId,
        long authorityVersion,
        long mailId,
        long characterId)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(authorityVersion);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(mailId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(characterId);
        var canonicalJson = FormattableString.Invariant(
            $"{{\"accountId\":\"{accountId:D}\",\"authorityVersion\":{authorityVersion},\"readMailId\":{mailId},\"characterId\":{characterId}}}");
        return HashCanonicalJson(canonicalJson);
    }

    public static string OriginalMessengerMessageSent(
        Guid accountId,
        long authorityVersion,
        long messageId,
        long senderCharacterId,
        long recipientCharacterId,
        string requestFingerprint)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(authorityVersion);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(messageId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(senderCharacterId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(recipientCharacterId);
        ArgumentException.ThrowIfNullOrWhiteSpace(requestFingerprint);
        var canonicalJson = FormattableString.Invariant(
            $"{{\"accountId\":\"{accountId:D}\",\"authorityVersion\":{authorityVersion},\"latestMessengerMessageId\":{messageId},\"senderCharacterId\":{senderCharacterId},\"recipientCharacterId\":{recipientCharacterId},\"requestFingerprint\":\"{requestFingerprint}\"}}");
        return HashCanonicalJson(canonicalJson);
    }

    public static string OriginalMailDeleted(
        Guid accountId,
        long authorityVersion,
        long mailId,
        long characterId,
        byte box)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(authorityVersion);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(mailId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(characterId);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(box, (byte)1);
        var canonicalJson = FormattableString.Invariant(
            $"{{\"accountId\":\"{accountId:D}\",\"authorityVersion\":{authorityVersion},\"deletedMailId\":{mailId},\"characterId\":{characterId},\"box\":{box}}}");
        return HashCanonicalJson(canonicalJson);
    }

    public static string OriginalOrderSuggestReplied(
        Guid accountId,
        long authorityVersion,
        long characterId,
        int cardId,
        byte replyValue,
        string requestFingerprint)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(authorityVersion);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(characterId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(cardId);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(replyValue, (byte)2);
        ArgumentException.ThrowIfNullOrWhiteSpace(requestFingerprint);
        var canonicalJson = FormattableString.Invariant(
            $"{{\"accountId\":\"{accountId:D}\",\"authorityVersion\":{authorityVersion},\"characterId\":{characterId},\"cardId\":{cardId},\"replyValue\":{replyValue},\"requestFingerprint\":\"{requestFingerprint}\"}}");
        return HashCanonicalJson(canonicalJson);
    }

    public static string OriginalCharacterLotteryAwarded(
        Guid accountId,
        long authorityVersion,
        long entryId,
        uint resultCandidateCharacterId,
        long characterId)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(authorityVersion);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(entryId);
        ArgumentOutOfRangeException.ThrowIfZero(resultCandidateCharacterId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(characterId);
        var canonicalJson = FormattableString.Invariant(
            $"{{\"accountId\":\"{accountId:D}\",\"authorityVersion\":{authorityVersion},\"latestOriginalCharacterLotteryEntryId\":{entryId},\"resultCandidateCharacterId\":{resultCandidateCharacterId},\"latestCharacterId\":{characterId}}}");
        return HashCanonicalJson(canonicalJson);
    }

    private static string HashCanonicalJson(string canonicalJson)
    {
        var jsonBytes = Encoding.UTF8.GetBytes(canonicalJson);
        var input = new byte[Prefix.Length + jsonBytes.Length];
        Prefix.CopyTo(input, 0);
        jsonBytes.CopyTo(input, Prefix.Length);
        try
        {
            return Convert.ToHexStringLower(SHA256.HashData(input));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(input);
        }
    }
}
