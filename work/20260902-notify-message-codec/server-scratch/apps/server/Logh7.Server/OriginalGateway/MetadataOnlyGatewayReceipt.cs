using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Logh7.Server.OriginalGateway;

public sealed class MetadataOnlyGatewayReceipt
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    private readonly object _gate = new();
    private readonly List<ReceiptEvent> _events = [];
    private readonly TimeProvider _timeProvider;
    private readonly byte[] _referenceKey = RandomNumberGenerator.GetBytes(32);

    public MetadataOnlyGatewayReceipt(TimeProvider timeProvider)
    {
        _timeProvider = timeProvider ?? throw new ArgumentNullException(nameof(timeProvider));
    }

    public void Record(string eventName, string outcome, Guid? accountId = null)
    {
        var reference = accountId is Guid value ? AccountReference(value) : null;
        lock (_gate)
        {
            _events.Add(new ReceiptEvent(_timeProvider.GetUtcNow(), eventName, outcome, reference));
        }
    }

    public string SerializeLines()
    {
        lock (_gate)
        {
            return string.Join('\n', _events.Select(value => JsonSerializer.Serialize(value, JsonOptions)));
        }
    }

    private string AccountReference(Guid accountId)
    {
        Span<byte> accountBytes = stackalloc byte[16];
        accountId.TryWriteBytes(accountBytes);
        var digest = HMACSHA256.HashData(_referenceKey, accountBytes);
        return Convert.ToHexStringLower(digest.AsSpan(0, 12));
    }

    private sealed record ReceiptEvent(
        DateTimeOffset TimestampUtc,
        string EventName,
        string Outcome,
        string? AccountReference);
}
