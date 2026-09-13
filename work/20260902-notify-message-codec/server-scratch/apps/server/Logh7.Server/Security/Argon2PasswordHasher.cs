using System.Security.Cryptography;
using System.Text;
using Konscious.Security.Cryptography;

namespace Logh7.Server.Security;

public sealed class Argon2PasswordHasher : IPasswordHasher
{
    public const int SaltLength = 16;
    public const int HashLength = 32;
    public const int DefaultMemoryKiB = 65_536;
    public const int DefaultIterations = 3;
    public const int DefaultParallelism = 1;

    public async Task<PasswordHashRecord> HashAsync(
        ReadOnlyMemory<char> password,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var salt = RandomNumberGenerator.GetBytes(SaltLength);
        var hash = await DeriveAsync(
            password,
            salt,
            DefaultMemoryKiB,
            DefaultIterations,
            DefaultParallelism,
            cancellationToken);
        return new PasswordHashRecord(
            salt,
            hash,
            DefaultMemoryKiB,
            DefaultIterations,
            DefaultParallelism);
    }

    public async Task<bool> VerifyAsync(
        ReadOnlyMemory<char> password,
        PasswordHashRecord record,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(record);
        if (record.Salt.Length != SaltLength || record.Hash.Length != HashLength ||
            record.MemoryKiB <= 0 || record.Iterations <= 0 || record.Parallelism <= 0)
        {
            return false;
        }

        var candidate = await DeriveAsync(
            password,
            record.Salt,
            record.MemoryKiB,
            record.Iterations,
            record.Parallelism,
            cancellationToken);
        try
        {
            return CryptographicOperations.FixedTimeEquals(candidate, record.Hash);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(candidate);
        }
    }

    private static async Task<byte[]> DeriveAsync(
        ReadOnlyMemory<char> password,
        byte[] salt,
        int memoryKiB,
        int iterations,
        int parallelism,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var passwordCharacters = password.ToArray();
        var passwordBytes = Encoding.UTF8.GetBytes(passwordCharacters);
        try
        {
            var argon2 = new Argon2id(passwordBytes)
            {
                Salt = salt.ToArray(),
                MemorySize = memoryKiB,
                Iterations = iterations,
                DegreeOfParallelism = parallelism
            };
            var result = await argon2.GetBytesAsync(HashLength);
            cancellationToken.ThrowIfCancellationRequested();
            return result;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(passwordBytes);
            Array.Clear(passwordCharacters);
        }
    }
}
