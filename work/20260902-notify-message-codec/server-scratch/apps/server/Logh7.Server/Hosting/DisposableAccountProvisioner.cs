using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Logh7.Server.Authority;
using Logh7.Server.Security;
using Logh7.Server.Storage;

namespace Logh7.Server.Hosting;

public interface ICredentialProtector
{
    byte[] Protect(ReadOnlySpan<byte> plaintext);
}

public sealed class WindowsDpapiCredentialProtector : ICredentialProtector
{
    public byte[] Protect(ReadOnlySpan<byte> plaintext)
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException("WINDOWS_DPAPI_REQUIRED");
        }

        return ProtectedData.Protect(
            plaintext.ToArray(),
            optionalEntropy: null,
            DataProtectionScope.CurrentUser);
    }
}

public sealed class DisposableAccountProvisioner
{
    private static ReadOnlySpan<char> HexAlphabet => "0123456789abcdef";

    private readonly IPasswordHasher _hasher;
    private readonly ICredentialProtector _protector;
    private readonly TimeProvider _timeProvider;

    public DisposableAccountProvisioner(
        IPasswordHasher hasher,
        ICredentialProtector protector,
        TimeProvider timeProvider)
    {
        _hasher = hasher ?? throw new ArgumentNullException(nameof(hasher));
        _protector = protector ?? throw new ArgumentNullException(nameof(protector));
        _timeProvider = timeProvider ?? throw new ArgumentNullException(nameof(timeProvider));
    }

    public async Task ProvisionAsync(
        IAccountStore store,
        string secretPath,
        string receiptPath,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(store);
        RequireNewAbsolutePath(secretPath);
        RequireNewAbsolutePath(receiptPath);
        if (string.Equals(
                Path.GetFullPath(secretPath),
                Path.GetFullPath(receiptPath),
                StringComparison.OrdinalIgnoreCase))
        {
            throw new IOException("ACCOUNT_PROVISION_OUTPUT_COLLISION");
        }

        var login = "t" + Convert.ToHexString(RandomNumberGenerator.GetBytes(4)).ToLowerInvariant()[..7];
        var password = NewHexCharacters(8);
        byte[]? plaintext = null;
        byte[]? protectedBytes = null;
        try
        {
            var hash = await _hasher.HashAsync(password, cancellationToken);
            var account = await store.ProvisionAsync(
                new AccountProvision(login, hash, AccountStatus.Active),
                cancellationToken);
            plaintext = SerializeCredential(login, password.Span);
            protectedBytes = _protector.Protect(plaintext);

            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(secretPath))!);
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(receiptPath))!);
            await WriteNewAsync(secretPath, protectedBytes, cancellationToken);

            var runId = Guid.NewGuid();
            var referenceKey = RandomNumberGenerator.GetBytes(32);
            Span<byte> accountBytes = stackalloc byte[16];
            account.AccountId.TryWriteBytes(accountBytes);
            var reference = HMACSHA256.HashData(referenceKey, accountBytes);
            var receipt = JsonSerializer.SerializeToUtf8Bytes(new
            {
                status = "ACCOUNT_PROVISIONED",
                runId,
                accountReference = Convert.ToHexStringLower(reference.AsSpan(0, 12)),
                createdAtUtc = _timeProvider.GetUtcNow(),
                store = "PostgreSQL",
                operations = new { accountRowsInserted = 1 },
                secretValuesRedacted = true
            }, new JsonSerializerOptions { WriteIndented = true });
            try
            {
                await WriteNewAsync(receiptPath, receipt, cancellationToken);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(referenceKey);
                CryptographicOperations.ZeroMemory(reference);
                CryptographicOperations.ZeroMemory(receipt);
            }
        }
        finally
        {
            password.Span.Clear();
            if (plaintext is not null)
            {
                CryptographicOperations.ZeroMemory(plaintext);
            }

            if (protectedBytes is not null)
            {
                CryptographicOperations.ZeroMemory(protectedBytes);
            }
        }
    }

    private static Memory<char> NewHexCharacters(int count)
    {
        var bytes = RandomNumberGenerator.GetBytes(count);
        var characters = new char[count];
        try
        {
            for (var index = 0; index < count; index++)
            {
                characters[index] = HexAlphabet[bytes[index] & 0x0f];
            }

            return characters;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(bytes);
        }
    }

    private static byte[] SerializeCredential(string login, ReadOnlySpan<char> password)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteString("login", login);
            writer.WriteString("password", password);
            writer.WriteEndObject();
        }

        return stream.ToArray();
    }

    private static void RequireNewAbsolutePath(string path)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        if (!Path.IsPathFullyQualified(path))
        {
            throw new IOException("ACCOUNT_PROVISION_OUTPUT_NOT_ABSOLUTE");
        }

        if (File.Exists(path) || Directory.Exists(path))
        {
            throw new IOException("ACCOUNT_PROVISION_OUTPUT_EXISTS");
        }
    }

    private static async Task WriteNewAsync(
        string path,
        ReadOnlyMemory<byte> contents,
        CancellationToken cancellationToken)
    {
        await using var stream = new FileStream(
            path,
            FileMode.CreateNew,
            FileAccess.Write,
            FileShare.None,
            bufferSize: 4096,
            FileOptions.Asynchronous | FileOptions.WriteThrough);
        await stream.WriteAsync(contents, cancellationToken);
        await stream.FlushAsync(cancellationToken);
    }
}
