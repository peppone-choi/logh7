using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalTacticalPathTests
{
    private static readonly OriginalTacticalPose Origin = new(new(0, 7, 0), 0);

    [Fact]
    public void NormalMoveBuildsInitialTurnTranslationAndFinalTurn()
    {
        var path = Build(2, [new(new(24, 7, 0), 0)]);
        Assert.Equal(new[] { 3, 2, 3 }, path.Segments.Select(s => s.Kind));
        Assert.InRange(path.Segments[0].Duration, 1.57079f, 1.57080f);
        Assert.Equal(new OriginalTacticalVector(0, MathF.PI / 2, 0), path.Segments[0].Target);
        Assert.Equal(1, path.Segments[1].Duration);
        Assert.Equal(new OriginalTacticalVector(24, 7, 0), path.Segments[1].Target);
        Assert.InRange(path.Segments[2].Duration, 1.57079f, 1.57080f);
        Assert.Equal(new OriginalTacticalVector(0, 0, 0), path.Segments[2].Target);
    }

    [Fact]
    public void ParallelMoveDoesNotTurnTowardTravelDirection()
    {
        var path = Build(4, [new(new(24, 7, 0), .5f)]);
        Assert.Equal(new[] { 4, 3 }, path.Segments.Select(s => s.Kind));
        Assert.Equal(1, path.Segments[0].Duration);
        Assert.Equal(.5f, path.Segments[1].Duration);
    }

    [Fact]
    public void ModeThreeUsesOnlyFirstDestinationAndTurnsBeforeTranslating()
    {
        var path = Build(3, [new(new(0, 7, 24), .5f), new(new(0, 7, 72), 2)]);
        Assert.Equal(new[] { 3, 4 }, path.Segments.Select(s => s.Kind));
        Assert.Equal(.5f, path.Segments[0].Duration);
        Assert.Equal(new OriginalTacticalVector(0, .5f, 0), path.Segments[0].Target);
        Assert.Equal(new OriginalTacticalVector(0, 7, 24), path.Segments[1].Target);
        Assert.Equal(1, path.Segments[1].Duration);
        Assert.Equal(2, path.Waypoints.Count); // Entity waypoint list is not rewritten by the builder.
    }

    [Fact]
    public void NormalMultipleDestinationsTurnAtEachLegAndUseLastFinalHeading()
    {
        var path = Build(2, [new(new(0, 7, 24), 2), new(new(24, 7, 24), .5f)]);
        Assert.Equal(new[] { 3, 2, 3, 2, 3 }, path.Segments.Select(s => s.Kind));
        Assert.Equal(0, path.Segments[0].Duration);
        Assert.Equal(1, path.Segments[1].Duration);
        Assert.InRange(path.Segments[2].Duration, 1.57079f, 1.57080f);
        Assert.Equal(1, path.Segments[3].Duration);
        Assert.InRange(path.Segments[4].Duration, 1.07079f, 1.07080f);
        Assert.Equal(.5f, path.Segments[4].Target.Y);
    }

    [Fact]
    public void ParallelMultipleDestinationsKeepInitialHeadingUntilFinalTurn()
    {
        var path = Build(4, [new(new(0, 7, 24), 2), new(new(24, 7, 24), .5f)]);
        Assert.Equal(new[] { 4, 4, 3 }, path.Segments.Select(s => s.Kind));
        Assert.Equal(.5f, path.Segments[2].Duration);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(255)]
    public void OtherModesUsePathOriginCorrectionInsteadOfRequestedDestination(byte mode)
    {
        var initial = new OriginalTacticalPose(new(0, 7, 24), .5f);
        var path = OriginalTacticalTrajectory.Build(mode, Origin, initial,
            [new(new(100, 7, 100), 2)], 1, 1f / 24);
        Assert.Equal(initial, path.Initial);
        Assert.Equal(new[] { 3, 4 }, path.Segments.Select(s => s.Kind));
        Assert.Equal(.5f, path.Segments[0].Duration);
        Assert.Equal(initial.Position, path.Segments[1].Target);
        Assert.Equal(1, path.Segments[1].Duration);
    }

    [Theory]
    [InlineData(2)]
    [InlineData(3)]
    [InlineData(4)]
    public void NoDestinationUsesCorrectionFallback(byte mode)
    {
        var path = Build(mode, []);
        Assert.Equal(new[] { 3, 4 }, path.Segments.Select(s => s.Kind));
        Assert.All(path.Segments, s => Assert.Equal(0, s.Duration));
        Assert.Equal(Origin.Position, path.Segments[1].Target);
    }

    [Fact]
    public void TranslationDurationAndInitialBearingIncludeHeight()
    {
        var path = Build(2, [new(new(0, 16, 12), 0)]);
        Assert.Equal(.625f, path.Segments[1].Duration); // XYZ distance15 / speed24.
        Assert.InRange(path.Segments[0].Target.Y, .64349f, .64351f);
    }

    [Fact]
    public void CoincidentLegKeepsCurrentHeadingAndZeroDuration()
    {
        var actual = new OriginalTacticalPose(Origin.Position, 1);
        var path = OriginalTacticalTrajectory.Build(2, actual, actual, [actual], 0, 1f / 24);
        Assert.Equal(3, path.Segments.Count);
        Assert.All(path.Segments, s => Assert.Equal(0, s.Duration));
        Assert.Equal(1, path.Segments[0].Target.Y);
    }

    [Fact]
    public void FinalTurnDurationUsesShortestArcButKeepsRawTargetHeading()
    {
        var actual = new OriginalTacticalPose(Origin.Position, 6);
        var path = OriginalTacticalTrajectory.Build(4, actual, actual,
            [new(new(0, 7, 24), .2f)], 1, 1f / 24);
        Assert.InRange(path.Segments[1].Duration, .48318f, .48320f);
        Assert.Equal(.2f, path.Segments[1].Target.Y);
    }

    [Fact]
    public void ActualPositionDrivesFirstDurationButPathOriginRemainsInterpolationStart()
    {
        var saved = new OriginalTacticalPose(new(0, 7, -24), 1);
        var path = OriginalTacticalTrajectory.Build(4, Origin, saved,
            [new(new(0, 7, 24), 0)], 1, 1f / 24);
        Assert.Equal(1, path.Segments[0].Duration);
        Assert.Equal(saved, path.Initial);
        var frame = OriginalTacticalTrajectory.Step(path.Initial, path.Segments,
            path.Waypoints, new(0, 0, 0, null), .5f);
        Assert.Equal(new OriginalTacticalVector(0, 7, 0), frame.Desired.Position);
    }

    [Fact]
    public void BuiltNormalPathFeedsCursorAndActualPursuitWithoutTeleport()
    {
        var path = Build(2, [new(new(0, 7, 24), 0)]);
        var cursor = new OriginalTacticalCursor(0, 0, -1, 0);
        var ack = OriginalTacticalTrajectory.Step(path.Initial, path.Segments, path.Waypoints, cursor, .25f);
        Assert.Equal(1, ack.Cursor.Segment); // Zero turn completes; fresh ack consumes remainder.
        var frame = OriginalTacticalTrajectory.Step(path.Initial, path.Segments, path.Waypoints, ack.Cursor, .25f);
        var actual = OriginalTacticalTrajectory.Pursue(Origin, frame.Desired, 1, 1f / 24, .25f, 1, true, true);
        Assert.Equal(new OriginalTacticalVector(0, 7, 6), actual.Actual.Position);
    }

    [Theory]
    [InlineData(float.NaN, 1)]
    [InlineData(-1, 1)]
    [InlineData(1, 0)]
    [InlineData(1, -1)]
    [InlineData(1, float.PositiveInfinity)]
    [InlineData(float.MaxValue, 1)]
    public void InvalidRatesAreRejected(float speed, float turn)
        => Assert.Throws<ArgumentOutOfRangeException>(() =>
            OriginalTacticalTrajectory.Build(2, Origin, Origin, [], speed, turn));

    [Fact]
    public void NonzeroDistanceWithZeroSpeedIsRejectedRatherThanCreatingInfiniteSegment()
        => Assert.Throws<ArgumentOutOfRangeException>(() =>
            OriginalTacticalTrajectory.Build(4, Origin, Origin, [new(new(0, 7, 24), 0)], 0, 1));

    [Fact]
    public void NonfiniteDestinationIsRejected()
        => Assert.Throws<ArgumentOutOfRangeException>(() => Build(2, [new(new(0, float.NaN, 0), 0)]));

    private static OriginalTacticalPath Build(byte mode, OriginalTacticalPose[] destinations)
        => OriginalTacticalTrajectory.Build(mode, Origin, Origin, destinations, 1, 1f / 24);
}
