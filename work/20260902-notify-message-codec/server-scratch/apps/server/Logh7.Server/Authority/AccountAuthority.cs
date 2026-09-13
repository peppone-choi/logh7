using System.Security.Cryptography;
using Logh7.Server.Security;
using Logh7.Server.Storage;

namespace Logh7.Server.Authority;

public enum LoginDecisionCode
{
    Accepted,
    Rejected
}

public readonly record struct LoginDecision(LoginDecisionCode Code, Guid AccountId, string? NormalizedLogin)
{
    public static LoginDecision Rejected => new(LoginDecisionCode.Rejected, Guid.Empty, null);
}

public interface IAccountAuthority
{
    Task<LoginDecision> VerifyAsync(
        ReadOnlyMemory<ushort> accountElements,
        ReadOnlyMemory<ushort> passwordElements,
        CancellationToken cancellationToken);
}

public sealed class AccountAuthority : IAccountAuthority
{
    private readonly IAccountStore _store;
    private readonly IPasswordHasher _hasher;
    private readonly PasswordHashRecord _missingAccountHash;

    public AccountAuthority(IAccountStore store, IPasswordHasher hasher)
    {
        _store = store ?? throw new ArgumentNullException(nameof(store));
        _hasher = hasher ?? throw new ArgumentNullException(nameof(hasher));
        _missingAccountHash = new PasswordHashRecord(
            RandomNumberGenerator.GetBytes(Argon2PasswordHasher.SaltLength),
            RandomNumberGenerator.GetBytes(Argon2PasswordHasher.HashLength),
            Argon2PasswordHasher.DefaultMemoryKiB,
            Argon2PasswordHasher.DefaultIterations,
            Argon2PasswordHasher.DefaultParallelism);
    }

    public async Task<LoginDecision> VerifyAsync(
        ReadOnlyMemory<ushort> accountElements,
        ReadOnlyMemory<ushort> passwordElements,
        CancellationToken cancellationToken)
    {
        if (!TryRemoveTerminator(accountElements.Span, 1, OriginalGateway.OriginalLoginCodec.MaximumAccountElements, out var account) ||
            !TryRemoveTerminator(passwordElements.Span, 1, OriginalGateway.OriginalLoginCodec.MaximumPasswordElements, out var password) ||
            !LoginNamePolicy.TryNormalize(account, out var normalized) ||
            !IsAsciiPassword(password))
        {
            return LoginDecision.Rejected;
        }

        var passwordCharacters = new char[password.Length];
        for (var index = 0; index < password.Length; index++)
        {
            passwordCharacters[index] = (char)password[index];
        }

        try
        {
            var record = await _store.FindAccountAsync(normalized, cancellationToken);
            var verified = await _hasher.VerifyAsync(
                passwordCharacters.AsMemory(),
                record?.Password ?? _missingAccountHash,
                cancellationToken);
            if (!verified || record?.Status != AccountStatus.Active)
            {
                return LoginDecision.Rejected;
            }

            return new LoginDecision(LoginDecisionCode.Accepted, record.AccountId, normalized);
        }
        finally
        {
            Array.Clear(passwordCharacters);
        }
    }

    private static bool TryRemoveTerminator(
        ReadOnlySpan<ushort> elements,
        int minimumCount,
        int maximumCount,
        out ReadOnlySpan<ushort> value)
    {
        value = default;
        if (elements.Length < minimumCount || elements.Length > maximumCount || elements[^1] != 0)
        {
            return false;
        }

        value = elements[..^1];
        return !value.Contains((ushort)0);
    }

    private static bool IsAsciiPassword(ReadOnlySpan<ushort> password)
    {
        if (password.IsEmpty)
        {
            return false;
        }

        foreach (var element in password)
        {
            if (element is < 0x21 or > 0x7e)
            {
                return false;
            }
        }

        return true;
    }
}
