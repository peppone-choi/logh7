using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Security.Cryptography;

namespace Logh7.Server.OriginalGateway;

public sealed class HandoffRegistry
{
    private readonly ConcurrentDictionary<uint, Entry> _entries = new();
    private readonly TimeProvider _timeProvider;
    private readonly TimeSpan _lifetime;

    public HandoffRegistry(TimeProvider timeProvider, TimeSpan lifetime)
    {
        _timeProvider = timeProvider ?? throw new ArgumentNullException(nameof(timeProvider));
        if (lifetime <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(lifetime));
        }

        _lifetime = lifetime;
    }

    public uint Issue(Guid accountId, string normalizedLogin)
        => IssueCore(accountId, normalizedLogin, selectionValue: null);

    public uint Issue(Guid accountId, string normalizedLogin, ushort selectionValue)
        => IssueCore(accountId, normalizedLogin, selectionValue);

    private uint IssueCore(Guid accountId, string normalizedLogin, ushort? selectionValue)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(normalizedLogin);
        Span<byte> bytes = stackalloc byte[sizeof(uint)];
        while (true)
        {
            RandomNumberGenerator.Fill(bytes);
            var token = BinaryPrimitives.ReadUInt32BigEndian(bytes);
            if (token != 0 && _entries.TryAdd(
                    token,
                    new Entry(
                        accountId,
                        normalizedLogin,
                        selectionValue,
                        _timeProvider.GetUtcNow() + _lifetime)))
            {
                return token;
            }
        }
    }

    public bool TryConsume(uint token, string normalizedLogin, out Guid accountId)
        => TryConsume(token, normalizedLogin, out accountId, out _);

    public bool TryConsume(
        uint token,
        string normalizedLogin,
        out Guid accountId,
        out ushort? selectionValue)
    {
        accountId = Guid.Empty;
        selectionValue = null;
        if (!_entries.TryRemove(token, out var entry) ||
            entry.ExpiresAt < _timeProvider.GetUtcNow() ||
            !string.Equals(entry.NormalizedLogin, normalizedLogin, StringComparison.Ordinal))
        {
            return false;
        }

        accountId = entry.AccountId;
        selectionValue = entry.SelectionValue;
        return true;
    }

    public bool TryConsumeOnlyOutstandingForLogin(
        string normalizedLogin,
        out Guid accountId)
        => TryConsumeOnlyOutstandingForLogin(normalizedLogin, out accountId, out _);

    public bool TryConsumeOnlyOutstandingForLogin(
        string normalizedLogin,
        out Guid accountId,
        out ushort? selectionValue)
    {
        accountId = Guid.Empty;
        selectionValue = null;
        ArgumentException.ThrowIfNullOrWhiteSpace(normalizedLogin);
        var now = _timeProvider.GetUtcNow();
        var candidates = _entries
            .Where(pair =>
                pair.Value.ExpiresAt >= now &&
                string.Equals(
                    pair.Value.NormalizedLogin, normalizedLogin, StringComparison.Ordinal))
            .Select(pair => pair.Key)
            .Take(2)
            .ToArray();
        return candidates.Length == 1 &&
            TryConsume(candidates[0], normalizedLogin, out accountId, out selectionValue);
    }

    private sealed record Entry(
        Guid AccountId,
        string NormalizedLogin,
        ushort? SelectionValue,
        DateTimeOffset ExpiresAt);
}
