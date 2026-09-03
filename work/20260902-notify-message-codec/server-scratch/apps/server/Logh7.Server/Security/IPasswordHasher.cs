namespace Logh7.Server.Security;

public sealed record PasswordHashRecord(
    byte[] Salt,
    byte[] Hash,
    int MemoryKiB,
    int Iterations,
    int Parallelism);

public interface IPasswordHasher
{
    Task<PasswordHashRecord> HashAsync(
        ReadOnlyMemory<char> password,
        CancellationToken cancellationToken);

    Task<bool> VerifyAsync(
        ReadOnlyMemory<char> password,
        PasswordHashRecord record,
        CancellationToken cancellationToken);
}
