using Logh7.Server.Security;

namespace Logh7.Server.Authority;

public enum AccountStatus
{
    Active,
    Suspended
}

public sealed record AccountProvision(
    string NormalizedLogin,
    PasswordHashRecord Password,
    AccountStatus Status);

public sealed record AccountRecord(
    Guid AccountId,
    string NormalizedLogin,
    PasswordHashRecord Password,
    AccountStatus Status,
    long AuthorityVersion,
    string AuthorityStateHash);

public sealed class AccountAlreadyExistsException : Exception
{
    public AccountAlreadyExistsException()
        : base("An account with the normalized login already exists.")
    {
    }

    public string Code => "ACCOUNT_ALREADY_EXISTS";
}
