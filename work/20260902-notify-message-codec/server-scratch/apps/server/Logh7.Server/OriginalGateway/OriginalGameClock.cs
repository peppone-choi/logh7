namespace Logh7.Server.OriginalGateway;

public sealed class OriginalGameClock
{
    public const uint TicksPerSecond = 24;
    private readonly TimeProvider _timeProvider;
    private readonly long _startedAt;

    public OriginalGameClock(TimeProvider timeProvider)
    {
        ArgumentNullException.ThrowIfNull(timeProvider);
        _timeProvider = timeProvider;
        _startedAt = timeProvider.GetTimestamp();
    }

    // Original FUN_004C5A70 advances by floor(elapsed milliseconds * 24 / 1000).
    // The process-local epoch is an authored policy, not a recovered campaign date.
    /// <summary>
    /// Wall-clock now from the same provider the tick comes from. Command point
    /// regeneration is measured against it, so a test clock moves both together.
    /// </summary>
    public DateTimeOffset Now => _timeProvider.GetUtcNow();

    public uint Tick => unchecked((uint)(
        _timeProvider.GetElapsedTime(_startedAt).Ticks * TicksPerSecond /
        TimeSpan.TicksPerSecond));
}
